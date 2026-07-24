package com.intellishell.ai;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Recognises commands worth stopping to confirm before they run in the user's
 * live shell.
 * <p>
 * The agent is deliberately allowed to work without nagging, so this list stays
 * narrow: only things that destroy data, overwrite devices, hand the shell to a
 * remote script, or take the environment apart. Ordinary work — editing files,
 * installing packages, running builds — never prompts.
 */
public final class DangerousCommands {

    private static final Pattern[] PATTERNS = {
        // Recursive/forced deletes.
        Pattern.compile("\\brm\\s+(-[a-z]*\\s+)*-[a-z]*[rf][a-z]*\\b"),
        // Wiping the filesystem root or a whole home/prefix.
        Pattern.compile("\\brm\\b[^|;&]*\\s(/|/\\*|~|\\$home|\\$prefix)(\\s|/\\*|$)"),
        // Raw device writes and filesystem creation.
        Pattern.compile("\\bdd\\b[^|;&]*\\bof=/dev/"),
        Pattern.compile("\\bmkfs(\\.[a-z0-9]+)?\\b"),
        Pattern.compile("\\bfdisk\\b|\\bparted\\b|\\bwipefs\\b"),
        // Piping a downloaded script straight into a shell.
        Pattern.compile("\\b(curl|wget)\\b[^|]*\\|\\s*(sudo\\s+)?(ba|z|k|d)?sh\\b"),
        // Removing packages or tearing down the Termux prefix.
        Pattern.compile("\\b(pkg|apt|apt-get)\\s+(remove|purge|autoremove)\\b"),
        Pattern.compile("\\btermux-reset\\b"),
        // Recursive ownership/permission changes from the root.
        Pattern.compile("\\bchmod\\s+(-[a-z]*\\s+)*-[a-z]*r[a-z]*\\b[^|;&]*\\s/(\\s|$)"),
        Pattern.compile("\\bchown\\s+(-[a-z]*\\s+)*-[a-z]*r[a-z]*\\b[^|;&]*\\s/(\\s|$)"),
        // Fork bomb.
        Pattern.compile(":\\(\\)\\s*\\{.*\\|.*&.*\\}\\s*;?\\s*:"),
        // Overwriting a block device or clearing history/keys wholesale.
        Pattern.compile(">\\s*/dev/(sd|mmcblk|block)"),
        // Factory-ish resets of the SSH identity store.
        Pattern.compile("\\brm\\b[^|;&]*\\.ssh\\b"),
    };

    private DangerousCommands() {}

    /**
     * Whether {@code command} should be confirmed before running. Checks each
     * line, so a destructive step buried in a multi-line block is still caught.
     */
    public static boolean isDangerous(String command) {
        if (command == null || command.trim().isEmpty()) return false;
        String normalised = command.toLowerCase(Locale.ROOT);
        for (String line : normalised.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            for (Pattern p : PATTERNS) {
                if (p.matcher(trimmed).find()) return true;
            }
        }
        return false;
    }

    /** A short, plain reason to show in the confirmation prompt. */
    public static String describe(String command) {
        String c = command == null ? "" : command.toLowerCase(Locale.ROOT);
        if (c.contains("mkfs") || c.contains("of=/dev/") || c.contains("fdisk")
            || c.contains("parted") || c.contains("wipefs")) {
            return "This writes directly to a device or creates a filesystem.";
        }
        if (c.contains("rm ")) {
            return "This deletes files recursively or by force.";
        }
        if (c.contains("|") && (c.contains("curl") || c.contains("wget"))) {
            return "This pipes a downloaded script straight into a shell.";
        }
        if (c.contains("remove") || c.contains("purge") || c.contains("termux-reset")) {
            return "This removes packages or resets the Termux environment.";
        }
        if (c.contains("chmod") || c.contains("chown")) {
            return "This changes permissions or ownership recursively from the root.";
        }
        return "This command can destroy data or break the environment.";
    }
}
