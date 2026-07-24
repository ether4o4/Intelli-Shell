package com.intellishell.data;

/**
 * One durable fact the assistant is allowed to carry between chats.
 * <p>
 * Memory is intentionally coarse — a category and a sentence — rather than a
 * structured schema. The whole store is rendered into every AI request by
 * {@link Db#buildMemoryPrompt()}, so entries need to stay short and human-readable;
 * anything longer belongs in a Note.
 */
public class MemoryEntry {

    /**
     * The fixed set of buckets shown in the Memory tab.
     * <p>
     * Kept closed so the prompt block has a predictable shape and the model cannot
     * invent an endless tail of near-duplicate categories.
     */
    public static final String[] CATEGORIES = {
        "preferences", "projects", "devices", "models", "environment",
        "coding-style", "tool-config", "important-facts"
    };

    /** Row id; {@code <= 0} means "not saved yet". */
    public long id;

    /** One of {@link #CATEGORIES}; unknown values are tolerated and sorted last. */
    public String category;

    public String content;

    /** Epoch millis, refreshed on every save. */
    public long updatedAt;

    public MemoryEntry() {
        this(0L, CATEGORIES[0], "", 0L);
    }

    /** New, unsaved entry. */
    public MemoryEntry(String category, String content) {
        this(0L, category, content, 0L);
    }

    public MemoryEntry(long id, String category, String content, long updatedAt) {
        this.id = id;
        this.category = category;
        this.content = content;
        this.updatedAt = updatedAt;
    }

    /** True when {@code category} is one of the known buckets. */
    public static boolean isKnownCategory(String category) {
        if (category == null) return false;
        for (String c : CATEGORIES) {
            if (c.equals(category)) return true;
        }
        return false;
    }

    @Override
    public String toString() {
        return "[" + category + "] " + content;
    }
}
