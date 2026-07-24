package com.intellishell.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The agent loop: turns a user message into model output, extracts any shell
 * commands, runs them in the user's live terminal, feeds the result back, and
 * repeats until the model stops asking for commands.
 * <p>
 * Unlike a sandboxed agent, this one types into the same pty the user is sitting
 * in — so its work is visible, interruptible, and shares the shell's state
 * (working directory, environment, live SSH sessions).
 */
public final class Agent {

    /** How the loop reaches the terminal and the UI. */
    public interface Host {
        /** Stream a model reply. Implementations block; the loop runs off the main thread. */
        void streamModel(List<LlmClient.Message> messages, LlmClient.Listener listener);

        /**
         * Run a command in the live terminal and return everything it printed.
         * Implementations should block until the shell is back at a prompt.
         */
        String runInTerminal(String command) throws InterruptedException;

        /**
         * Ask the user to approve a destructive command. Blocks until they answer.
         * Returning false skips the command and tells the model it was declined.
         */
        boolean confirmDangerous(String command, String reason) throws InterruptedException;

        /** A snapshot of the visible terminal, so the model can see where it is. */
        String terminalSnapshot();

        /** Long-lived facts to inject into every conversation; may be empty. */
        String memoryBlock();

        /** Record a durable fact the model asked to remember. */
        void rememberFact(String category, String content);

        void onAssistantDelta(String text);
        void onAssistantMessageComplete(String text);
        void onCommandStarted(String command);
        void onCommandOutput(String output);
        void onStatus(String status);
        void onError(String message);
        void onFinished();
    }

    /** Fenced blocks the model can emit. */
    private static final Pattern SHELL_BLOCK = Pattern.compile(
        "```[ \\t]*(sh|bash|shell|zsh|console|terminal)?[ \\t]*\\r?\\n([\\s\\S]*?)(?:```|$)",
        Pattern.CASE_INSENSITIVE);

    /** {@code <remember category="...">fact</remember>} — how the model writes memory. */
    private static final Pattern REMEMBER = Pattern.compile(
        "<remember\\s+category=\"([a-z\\-]+)\"\\s*>([\\s\\S]*?)</remember>",
        Pattern.CASE_INSENSITIVE);

    private static final int MAX_STEPS = 12;
    private static final int MAX_OUTPUT_FED_BACK = 4000;

    private final Host mHost;
    private final List<LlmClient.Message> mHistory = new ArrayList<>();
    private volatile boolean mStopped;

    public Agent(Host host) {
        mHost = host;
    }

    /** Drop the conversation but keep the host wiring. */
    public void resetConversation() {
        mHistory.clear();
    }

    /** Signal the loop to stop as soon as it can. */
    public void stop() {
        mStopped = true;
    }

    public boolean isStopped() {
        return mStopped;
    }

    /** Pre-load context, e.g. a saved script the user pulled into the chat. */
    public void addContext(String text) {
        mHistory.add(new LlmClient.Message("user", text));
    }

    public List<LlmClient.Message> history() {
        return mHistory;
    }

    /**
     * Run one user turn to completion. Blocking — call from a worker thread.
     */
    public void send(String userText) {
        mStopped = false;
        mHistory.add(new LlmClient.Message("user", userText));

        try {
            for (int step = 0; step < MAX_STEPS && !mStopped; step++) {
                mHost.onStatus("thinking");

                List<LlmClient.Message> request = new ArrayList<>();
                request.add(new LlmClient.Message("system", buildSystemPrompt()));
                request.addAll(mHistory);

                final StringBuilder reply = new StringBuilder();
                final String[] error = new String[1];
                mHost.streamModel(request, new LlmClient.Listener() {
                    @Override
                    public void onDelta(String text) {
                        if (mStopped) return;
                        reply.append(text);
                        mHost.onAssistantDelta(text);
                    }

                    @Override
                    public void onDone(String fullText) {
                        // Deltas already accumulated; nothing more to do.
                    }

                    @Override
                    public void onError(String message) {
                        error[0] = message;
                    }
                });

                if (mStopped) break;
                if (error[0] != null) {
                    mHost.onError(error[0]);
                    break;
                }

                String full = reply.toString();
                mHost.onAssistantMessageComplete(full);
                mHistory.add(new LlmClient.Message("assistant", full));

                harvestMemory(full);

                String command = extractCommand(full);
                if (command == null) break;

                if (DangerousCommands.isDangerous(command)) {
                    mHost.onStatus("waiting for confirmation");
                    boolean approved = mHost.confirmDangerous(command, DangerousCommands.describe(command));
                    if (!approved) {
                        mHistory.add(new LlmClient.Message("user",
                            "[the user declined to run that command — do not retry it; "
                                + "suggest a safer approach or ask what they want instead]"));
                        continue;
                    }
                }

                if (mStopped) break;
                mHost.onStatus("working");
                mHost.onCommandStarted(command);
                String output = mHost.runInTerminal(command);
                if (mStopped) break;
                mHost.onCommandOutput(output);

                String trimmed = output == null ? "" : output;
                if (trimmed.length() > MAX_OUTPUT_FED_BACK) {
                    trimmed = trimmed.substring(trimmed.length() - MAX_OUTPUT_FED_BACK)
                        + "\n…(earlier output truncated)";
                }
                mHistory.add(new LlmClient.Message("user", "[terminal output]\n" + trimmed));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            mHost.onStatus("idle");
            mHost.onFinished();
        }
    }

    /** Save any {@code <remember>} blocks the model emitted. */
    private void harvestMemory(String reply) {
        Matcher m = REMEMBER.matcher(reply);
        while (m.find()) {
            String category = m.group(1).toLowerCase();
            String content = m.group(2).trim();
            if (!content.isEmpty()) {
                mHost.rememberFact(category, content);
            }
        }
    }

    /**
     * Pull the first runnable shell block out of a reply, or null if there is none.
     * Comment-only and empty blocks count as none.
     */
    public static String extractCommand(String reply) {
        if (reply == null) return null;
        Matcher m = SHELL_BLOCK.matcher(reply);
        while (m.find()) {
            String tag = m.group(1);
            String bodyText = m.group(2);
            if (bodyText == null) continue;
            // An untagged fence is only a command block if it isn't obviously prose.
            if (tag == null && !looksLikeCommands(bodyText)) continue;

            StringBuilder cleaned = new StringBuilder();
            for (String line : bodyText.split("\\r?\\n")) {
                String stripped = line.replaceFirst("^[ \\t]*[$>][ \\t]+", "");
                cleaned.append(stripped).append('\n');
            }
            String result = cleaned.toString().trim();
            if (result.isEmpty()) continue;
            boolean hasRealLine = false;
            for (String line : result.split("\\r?\\n")) {
                String t = line.trim();
                if (!t.isEmpty() && !t.startsWith("#")) {
                    hasRealLine = true;
                    break;
                }
            }
            if (hasRealLine) return result;
        }
        return null;
    }

    /** Heuristic so untagged prose fences aren't executed. */
    private static boolean looksLikeCommands(String body) {
        for (String line : body.split("\\r?\\n")) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) continue;
            // Prose almost always contains a space-separated sentence ending in a period
            // and no shell-ish leading token.
            String first = t.split("[ \\t]", 2)[0];
            if (first.endsWith(".") || first.isEmpty()) return false;
            return true;
        }
        return false;
    }

    private String buildSystemPrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append("You are IntelliShell, an AI agent living inside the user's Android terminal. ")
          .append("You share their REAL, LIVE shell session running in Termux — the same one they ")
          .append("type in. Your working directory, environment and running processes are theirs.\n\n")

          .append("To run something, emit exactly ONE fenced sh block:\n\n")
          .append("```sh\npkg install git\n```\n\n")
          .append("The block runs in the live terminal and its output comes back to you as the next ")
          .append("message. Then you continue.\n\n")

          .append("Rules:\n")
          .append("- ACT rather than describe. If asked to do something, run it now.\n")
          .append("- One sh block per reply, and only when you actually want to run something.\n")
          .append("- Commands share the shell, so cd and variables persist between steps.\n")
          .append("- Keep chat replies short. The terminal already shows commands and output; ")
          .append("don't paste large output back.\n")
          .append("- Read the output and adapt. Diagnose real errors instead of giving up.\n")
          .append("- This is Termux: install with `pkg install <name>`. There is no sudo and no apt-get ")
          .append("outside the Termux prefix. Ping never works on Android — test with the real tool.\n")
          .append("- Destructive commands are shown to the user for confirmation before running, ")
          .append("so don't ask permission in prose; just emit the command.\n")
          .append("- When the task is done, reply with a short summary and NO sh block.\n\n")

          .append("To remember something durable about the user (a preference, project, device, ")
          .append("model, environment detail, coding style, tool config, or important fact), include:\n")
          .append("<remember category=\"preferences\">they prefer zsh over bash</remember>\n")
          .append("Valid categories: preferences, projects, devices, models, environment, ")
          .append("coding-style, tool-config, important-facts. Only record things that stay true.\n");

        String memory = mHost.memoryBlock();
        if (memory != null && !memory.trim().isEmpty()) {
            sb.append("\nWhat you already know about this user:\n").append(memory).append('\n');
        }

        String screen = mHost.terminalSnapshot();
        if (screen != null && !screen.trim().isEmpty()) {
            sb.append("\nCurrent terminal screen:\n---\n").append(screen).append("\n---\n");
        }

        return sb.toString();
    }
}
