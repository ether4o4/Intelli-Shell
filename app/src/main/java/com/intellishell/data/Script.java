package com.intellishell.data;

import java.util.ArrayList;
import java.util.List;

/**
 * A saved shell snippet in the Scripts tab.
 * <p>
 * Deliberately a plain mutable POJO: the dashboard editors bind directly to these
 * fields, and {@link Db} reads them back out. Nothing here touches the database, so
 * a {@code Script} can be built, edited and thrown away on the UI thread freely.
 * <p>
 * {@link #runCommand} is what actually gets sent to the terminal. It is kept apart
 * from {@link #body} because the two often differ: the body may be a Python file
 * while the run command is {@code python ~/foo.py}.
 */
public class Script {

    /**
     * Suggested tags offered in the editor.
     * <p>
     * These are only suggestions — {@link #tags} accepts anything the user types.
     * The list leans towards what actually gets run on a phone: remote access,
     * package wrangling, and local model tooling.
     */
    public static final String[] LABELS = {
        "SSH", "Bash", "Termux", "Python", "PowerShell", "ADB", "Git", "API",
        "LLM", "Ollama", "llama.cpp", "OSINT", "Network", "Automation",
        "Install", "Setup", "Fix", "Utility"
    };

    /** Row id; {@code <= 0} means "not saved yet". */
    public long id;

    public String name;
    public String body;

    /** Free-form language hint ("bash", "python", ...) used for display and search. */
    public String language;

    /** Never null after construction, though callers may assign null; {@link Db} tolerates it. */
    public List<String> tags;

    /** The line handed to the shell. May be empty, in which case the body is run as-is. */
    public String runCommand;

    /** Epoch millis. {@link Db#saveScript} stamps this on first insert. */
    public long createdAt;

    /** Empty script, ready for the editor. */
    public Script() {
        this(0L, "", "", "bash", new ArrayList<String>(), "", 0L);
    }

    /** New, unsaved script. */
    public Script(String name, String body, String language, List<String> tags, String runCommand) {
        this(0L, name, body, language, tags, runCommand, 0L);
    }

    public Script(long id, String name, String body, String language,
                  List<String> tags, String runCommand, long createdAt) {
        this.id = id;
        this.name = name;
        this.body = body;
        this.language = language;
        this.tags = tags != null ? new ArrayList<>(tags) : new ArrayList<String>();
        this.runCommand = runCommand;
        this.createdAt = createdAt;
    }

    /** True when this script carries {@code tag}, ignoring case. */
    public boolean hasTag(String tag) {
        if (tag == null || tags == null) return false;
        for (String t : tags) {
            if (t != null && t.equalsIgnoreCase(tag)) return true;
        }
        return false;
    }

    /** What to execute: the explicit run command, falling back to the body. */
    public String effectiveCommand() {
        if (runCommand != null && !runCommand.trim().isEmpty()) return runCommand;
        return body != null ? body : "";
    }

    @Override
    public String toString() {
        return name != null && !name.isEmpty() ? name : "(untitled script)";
    }
}
