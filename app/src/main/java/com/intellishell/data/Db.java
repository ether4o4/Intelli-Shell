package com.intellishell.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The whole persistence layer: scripts, notes, and AI memory.
 * <p>
 * Plain {@code android.database.sqlite} on purpose. The dataset is a single user's
 * personal library — tens to low hundreds of rows — so an ORM would cost more in
 * build weight and indirection than it saves.
 * <p>
 * <b>Threading.</b> Every method here is synchronous and blocking. {@link SQLiteDatabase}
 * serialises its own access, so these calls are safe from any thread; they are
 * <em>not</em> safe on the UI thread, and callers should hand them to an executor.
 * <p>
 * <b>Case sensitivity.</b> SQLite's {@code LOWER()} only folds ASCII. Free-text search
 * therefore matches case-insensitively for ASCII and case-sensitively beyond it.
 * Tag and hashtag matching sidesteps this: hashtags are lowercased in Java before
 * they are stored, and script tags are compared in Java after the row is loaded.
 */
public final class Db extends SQLiteOpenHelper {

    private static final String DB_NAME = "intellishell.db";
    private static final int DB_VERSION = 1;

    private static final String T_SCRIPTS = "scripts";
    private static final String S_ID = "id";
    private static final String S_NAME = "name";
    private static final String S_BODY = "body";
    private static final String S_LANG = "language";
    private static final String S_TAGS = "tags";
    private static final String S_RUN = "run_command";
    private static final String S_CREATED = "created_at";

    private static final String T_NOTES = "notes";
    private static final String N_ID = "id";
    private static final String N_TITLE = "title";
    private static final String N_BODY = "body";
    private static final String N_TAGS = "hashtags";
    private static final String N_UPDATED = "updated_at";

    private static final String T_MEMORY = "memory";
    private static final String M_ID = "id";
    private static final String M_CATEGORY = "category";
    private static final String M_CONTENT = "content";
    private static final String M_UPDATED = "updated_at";

    private static final String[] COLS_SCRIPT =
        {S_ID, S_NAME, S_BODY, S_LANG, S_TAGS, S_RUN, S_CREATED};
    private static final String[] COLS_NOTE =
        {N_ID, N_TITLE, N_BODY, N_TAGS, N_UPDATED};
    private static final String[] COLS_MEMORY =
        {M_ID, M_CATEGORY, M_CONTENT, M_UPDATED};

    private static final String ORDER_SCRIPTS = S_CREATED + " DESC, " + S_ID + " DESC";
    private static final String ORDER_NOTES = N_UPDATED + " DESC, " + N_ID + " DESC";

    /** Bucket used when the assistant remembers something without naming a category. */
    private static final String DEFAULT_CATEGORY = "important-facts";

    /** Backslash escape for {@code LIKE}, so a literal {@code _} in a tag cannot act as a wildcard. */
    private static final String LIKE_ESCAPE = " ESCAPE '\\'";

    private static volatile Db instance;

    /**
     * The process-wide helper.
     * <p>
     * A single instance matters: separate {@link SQLiteOpenHelper}s open separate
     * connections to the same file and lose the internal locking that makes the
     * methods below thread-safe.
     */
    public static Db get(Context context) {
        Db local = instance;
        if (local == null) {
            synchronized (Db.class) {
                local = instance;
                if (local == null) {
                    // Application context: this outlives every Activity that asks for it.
                    local = new Db(context.getApplicationContext());
                    instance = local;
                }
            }
        }
        return local;
    }

    private Db(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
        // Lets the dashboard keep reading while a save is in flight.
        setWriteAheadLoggingEnabled(true);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        createSchema(db);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Never drop: these tables hold notes the user typed by hand. Future versions
        // add columns via ALTER TABLE; for now just make sure nothing is missing.
        createSchema(db);
    }

    private static void createSchema(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS " + T_SCRIPTS + " ("
            + S_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, "
            + S_NAME + " TEXT NOT NULL DEFAULT '', "
            + S_BODY + " TEXT NOT NULL DEFAULT '', "
            + S_LANG + " TEXT NOT NULL DEFAULT '', "
            + S_TAGS + " TEXT NOT NULL DEFAULT '', "
            + S_RUN + " TEXT NOT NULL DEFAULT '', "
            + S_CREATED + " INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_scripts_created ON "
            + T_SCRIPTS + "(" + S_CREATED + ")");

        db.execSQL("CREATE TABLE IF NOT EXISTS " + T_NOTES + " ("
            + N_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, "
            + N_TITLE + " TEXT NOT NULL DEFAULT '', "
            + N_BODY + " TEXT NOT NULL DEFAULT '', "
            // Derived from the body on every save. Denormalised so filtering by tag and
            // listing all tags never have to re-parse every note.
            + N_TAGS + " TEXT NOT NULL DEFAULT '', "
            + N_UPDATED + " INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_notes_updated ON "
            + T_NOTES + "(" + N_UPDATED + ")");

        db.execSQL("CREATE TABLE IF NOT EXISTS " + T_MEMORY + " ("
            + M_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, "
            + M_CATEGORY + " TEXT NOT NULL DEFAULT '', "
            + M_CONTENT + " TEXT NOT NULL DEFAULT '', "
            + M_UPDATED + " INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_memory_category ON "
            + T_MEMORY + "(" + M_CATEGORY + ")");
    }

    // ---------------------------------------------------------------- scripts

    /**
     * Inserts when {@code s.id <= 0}, otherwise updates that row. Returns the row id,
     * or {@code -1} if nothing could be written.
     * <p>
     * The passed object is updated in place with its id and creation stamp so the
     * caller can keep using it after the save.
     */
    public long saveScript(Script s) {
        if (s == null) return -1L;

        long created = s.createdAt > 0 ? s.createdAt : System.currentTimeMillis();

        ContentValues v = new ContentValues();
        v.put(S_NAME, nz(s.name));
        v.put(S_BODY, nz(s.body));
        v.put(S_LANG, nz(s.language));
        v.put(S_TAGS, joinTags(s.tags));
        v.put(S_RUN, nz(s.runCommand));
        v.put(S_CREATED, created);

        SQLiteDatabase db = getWritableDatabase();
        if (s.id > 0) {
            int rows = db.update(T_SCRIPTS, v, S_ID + "=?", new String[]{String.valueOf(s.id)});
            // The row was deleted from another screen while this editor was open;
            // re-insert rather than silently discarding the user's edit.
            if (rows == 0) s.id = db.insert(T_SCRIPTS, null, v);
        } else {
            s.id = db.insert(T_SCRIPTS, null, v);
        }
        s.createdAt = created;
        return s.id;
    }

    public void deleteScript(long id) {
        if (id <= 0) return;
        getWritableDatabase().delete(T_SCRIPTS, S_ID + "=?", new String[]{String.valueOf(id)});
    }

    /** All scripts, newest first. */
    public List<Script> listScripts() {
        return queryScripts(null, null, null);
    }

    /**
     * Scripts whose name, body or language contains {@code query}, optionally narrowed
     * to those carrying {@code tagOrNull}. A null or blank query means "everything".
     */
    public List<Script> searchScripts(String query, String tagOrNull) {
        String needle = likeArg(query);
        String where = null;
        String[] args = null;
        if (needle != null) {
            where = "(LOWER(" + S_NAME + ") LIKE ?" + LIKE_ESCAPE
                + " OR LOWER(" + S_BODY + ") LIKE ?" + LIKE_ESCAPE
                + " OR LOWER(" + S_LANG + ") LIKE ?" + LIKE_ESCAPE + ")";
            args = new String[]{needle, needle, needle};
        }
        return queryScripts(where, args, blankToNull(tagOrNull));
    }

    public Script getScript(long id) {
        if (id <= 0) return null;
        List<Script> found = queryScripts(S_ID + "=?", new String[]{String.valueOf(id)}, null);
        return found.isEmpty() ? null : found.get(0);
    }

    /**
     * Shared read path. The tag filter runs in Java rather than SQL: matching a
     * comma-joined column with {@code LIKE} would depend on SQLite's ASCII-only
     * {@code LOWER()}, whereas {@link Script#hasTag} compares whole tags correctly.
     */
    private List<Script> queryScripts(String where, String[] args, String tagOrNull) {
        List<Script> out = new ArrayList<>();
        Cursor c = null;
        try {
            c = getReadableDatabase().query(
                T_SCRIPTS, COLS_SCRIPT, where, args, null, null, ORDER_SCRIPTS);
            while (c.moveToNext()) {
                Script s = new Script(
                    c.getLong(0), c.getString(1), c.getString(2), c.getString(3),
                    splitTags(c.getString(4)), c.getString(5), c.getLong(6));
                if (tagOrNull == null || s.hasTag(tagOrNull)) out.add(s);
            }
        } finally {
            closeQuietly(c);
        }
        return out;
    }

    // ------------------------------------------------------------------ notes

    /**
     * Inserts or updates, always stamping {@code updatedAt} with the current time —
     * a note that was touched is a note that just moved to the top of the list.
     * Returns the row id, or {@code -1} if nothing could be written.
     */
    public long saveNote(Note n) {
        if (n == null) return -1L;

        long now = System.currentTimeMillis();

        ContentValues v = new ContentValues();
        v.put(N_TITLE, nz(n.title));
        v.put(N_BODY, nz(n.body));
        v.put(N_TAGS, joinTags(Note.extractHashtags(n.body)));
        v.put(N_UPDATED, now);

        SQLiteDatabase db = getWritableDatabase();
        if (n.id > 0) {
            int rows = db.update(T_NOTES, v, N_ID + "=?", new String[]{String.valueOf(n.id)});
            if (rows == 0) n.id = db.insert(T_NOTES, null, v);
        } else {
            n.id = db.insert(T_NOTES, null, v);
        }
        n.updatedAt = now;
        return n.id;
    }

    public void deleteNote(long id) {
        if (id <= 0) return;
        getWritableDatabase().delete(T_NOTES, N_ID + "=?", new String[]{String.valueOf(id)});
    }

    /** All notes, most recently edited first. */
    public List<Note> listNotes() {
        return queryNotes(null, null);
    }

    /**
     * Notes whose title or body contains {@code query}, optionally narrowed to those
     * carrying {@code hashtagOrNull} (with or without its leading {@code #}).
     * A null or blank query means "everything".
     */
    public List<Note> searchNotes(String query, String hashtagOrNull) {
        List<String> clauses = new ArrayList<>();
        List<String> args = new ArrayList<>();

        String needle = likeArg(query);
        if (needle != null) {
            clauses.add("(LOWER(" + N_TITLE + ") LIKE ?" + LIKE_ESCAPE
                + " OR LOWER(" + N_BODY + ") LIKE ?" + LIKE_ESCAPE + ")");
            args.add(needle);
            args.add(needle);
        }

        String tag = normalizeHashtag(hashtagOrNull);
        if (tag != null) {
            // Both sides are already lowercase, so a padded LIKE is an exact whole-tag
            // match: ",kernel," can never be found inside ",kernel-panic,".
            clauses.add("(',' || " + N_TAGS + " || ',') LIKE ?" + LIKE_ESCAPE);
            args.add("%," + escapeLike(tag) + ",%");
        }

        if (clauses.isEmpty()) return listNotes();

        String where = join(clauses, " AND ");
        return queryNotes(where, args.toArray(new String[0]));
    }

    /**
     * Every hashtag in use, most-used first and alphabetical within a tie.
     * <p>
     * Frequency counts notes, not occurrences — a tag repeated five times in one note
     * should not outrank a tag that five separate notes agreed on.
     */
    public List<String> allHashtags() {
        final Map<String, Integer> counts = new HashMap<>();
        Cursor c = null;
        try {
            c = getReadableDatabase().query(
                T_NOTES, new String[]{N_TAGS}, null, null, null, null, null);
            while (c.moveToNext()) {
                for (String tag : splitTags(c.getString(0))) {
                    Integer prev = counts.get(tag);
                    counts.put(tag, prev == null ? 1 : prev + 1);
                }
            }
        } finally {
            closeQuietly(c);
        }

        List<String> tags = new ArrayList<>(counts.keySet());
        Collections.sort(tags, new Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                int byCount = counts.get(b) - counts.get(a);
                return byCount != 0 ? byCount : a.compareTo(b);
            }
        });
        return tags;
    }

    private List<Note> queryNotes(String where, String[] args) {
        List<Note> out = new ArrayList<>();
        Cursor c = null;
        try {
            c = getReadableDatabase().query(
                T_NOTES, COLS_NOTE, where, args, null, null, ORDER_NOTES);
            while (c.moveToNext()) {
                out.add(new Note(c.getLong(0), c.getString(1), c.getString(2), c.getLong(4)));
            }
        } finally {
            closeQuietly(c);
        }
        return out;
    }

    // ----------------------------------------------------------------- memory

    /**
     * Inserts or updates a memory entry, stamping {@code updatedAt}. Returns the row
     * id, or {@code -1} if nothing could be written.
     */
    public long saveMemory(MemoryEntry m) {
        if (m == null) return -1L;

        long now = System.currentTimeMillis();

        ContentValues v = new ContentValues();
        v.put(M_CATEGORY, normalizeCategory(m.category));
        v.put(M_CONTENT, nz(m.content).trim());
        v.put(M_UPDATED, now);

        SQLiteDatabase db = getWritableDatabase();
        if (m.id > 0) {
            int rows = db.update(T_MEMORY, v, M_ID + "=?", new String[]{String.valueOf(m.id)});
            if (rows == 0) m.id = db.insert(T_MEMORY, null, v);
        } else {
            m.id = db.insert(T_MEMORY, null, v);
        }
        m.updatedAt = now;
        return m.id;
    }

    public void deleteMemory(long id) {
        if (id <= 0) return;
        getWritableDatabase().delete(T_MEMORY, M_ID + "=?", new String[]{String.valueOf(id)});
    }

    /**
     * All memory, ordered so the list can be rendered as category sections without any
     * further sorting: categories follow {@link MemoryEntry#CATEGORIES}, unknown ones
     * trail at the end, and entries within a category are newest first.
     */
    public List<MemoryEntry> listMemory() {
        return queryMemory(null, null);
    }

    /** Memory in one category, newest first. */
    public List<MemoryEntry> listMemory(String category) {
        if (category == null) return listMemory();
        return queryMemory(CATEGORY_NORM + "=?", new String[]{normalizeCategory(category)});
    }

    /**
     * Renders the whole memory store as the block injected ahead of every AI chat:
     *
     * <pre>
     * [preferences]
     * - uses zsh
     *
     * [projects]
     * - IntelliShell: Android front-end over Termux
     * </pre>
     *
     * Multi-line facts keep their line breaks but are indented, so each fact still
     * begins with a {@code - } and the block stays trivially parseable. Returns an
     * empty string when there is nothing to say, which callers can skip entirely
     * rather than sending an empty header.
     */
    public String buildMemoryPrompt() {
        StringBuilder sb = new StringBuilder();
        String current = null;
        for (MemoryEntry m : listMemory()) {
            String content = nz(m.content).trim();
            if (content.isEmpty()) continue;

            String category = normalizeCategory(m.category);
            if (!category.equals(current)) {
                if (sb.length() > 0) sb.append('\n');
                sb.append('[').append(category).append("]\n");
                current = category;
            }
            sb.append("- ")
                .append(content.replace("\r\n", "\n").replace("\r", "\n").replace("\n", "\n  "))
                .append('\n');
        }
        return sb.toString().trim();
    }

    /**
     * Records a durable fact learned mid-conversation.
     * <p>
     * Assistants restate the same fact often, so an entry with the same category and
     * content (ignoring case and surrounding space) is refreshed instead of appended —
     * otherwise the prompt block would grow with near-identical lines every session.
     * Returns the row id, or {@code -1} for blank content.
     */
    public long rememberFact(String category, String content) {
        String text = nz(content).trim();
        if (text.isEmpty()) return -1L;

        String bucket = normalizeCategory(category);
        SQLiteDatabase db = getWritableDatabase();

        long existing = -1L;
        Cursor c = null;
        try {
            c = db.query(T_MEMORY, new String[]{M_ID},
                CATEGORY_NORM + "=? AND LOWER(TRIM(" + M_CONTENT + "))=?",
                new String[]{bucket, text.toLowerCase(Locale.ROOT)},
                null, null, M_ID + " ASC", "1");
            if (c.moveToFirst()) existing = c.getLong(0);
        } finally {
            closeQuietly(c);
        }

        MemoryEntry entry = new MemoryEntry(existing > 0 ? existing : 0L, bucket, text, 0L);
        return saveMemory(entry);
    }

    private List<MemoryEntry> queryMemory(String where, String[] args) {
        List<MemoryEntry> out = new ArrayList<>();
        Cursor c = null;
        try {
            c = getReadableDatabase().query(
                T_MEMORY, COLS_MEMORY, where, args, null, null, MEMORY_ORDER);
            while (c.moveToNext()) {
                out.add(new MemoryEntry(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3)));
            }
        } finally {
            closeQuietly(c);
        }
        return out;
    }

    /**
     * SQL mirror of {@link #normalizeCategory}. Sorting on the raw column instead would
     * let a stray untrimmed or empty category sort away from the bucket Java folds it
     * into, which would split one section into two headers in
     * {@link #buildMemoryPrompt()}.
     */
    private static final String CATEGORY_NORM =
        "(CASE WHEN TRIM(" + M_CATEGORY + ")='' THEN '" + DEFAULT_CATEGORY
            + "' ELSE TRIM(" + M_CATEGORY + ") END)";

    /**
     * {@code ORDER BY} that reproduces the canonical category order from
     * {@link MemoryEntry#CATEGORIES}, with unknown categories trailing alphabetically.
     * Built once; the category names are compile-time constants containing no quotes,
     * so inlining them is safe.
     */
    private static final String MEMORY_ORDER = buildMemoryOrder();

    private static String buildMemoryOrder() {
        StringBuilder sb = new StringBuilder("CASE " + CATEGORY_NORM);
        for (int i = 0; i < MemoryEntry.CATEGORIES.length; i++) {
            sb.append(" WHEN '").append(MemoryEntry.CATEGORIES[i]).append("' THEN ").append(i);
        }
        sb.append(" ELSE ").append(MemoryEntry.CATEGORIES.length).append(" END ASC, ")
            .append(CATEGORY_NORM).append(" ASC, ")
            .append(M_UPDATED).append(" DESC, ")
            .append(M_ID).append(" DESC");
        return sb.toString();
    }

    // ----------------------------------------------------------------- helpers

    private static String nz(String s) {
        return s != null ? s : "";
    }

    private static String blankToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /** Unknown or missing categories still need a home; they sort after the known ones. */
    private static String normalizeCategory(String category) {
        String c = blankToNull(category);
        return c == null ? DEFAULT_CATEGORY : c;
    }

    /** Accepts {@code "#linux"} or {@code "linux"}; returns the stored form, or null. */
    private static String normalizeHashtag(String tag) {
        String t = blankToNull(tag);
        if (t == null) return null;
        while (t.startsWith("#")) t = t.substring(1);
        t = t.trim();
        return t.isEmpty() ? null : t.toLowerCase(Locale.ROOT);
    }

    /** Comma is the record separator, so it cannot survive inside a tag. */
    private static String joinTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String tag : tags) {
            if (tag == null) continue;
            String t = tag.replace(',', ' ').trim();
            if (t.isEmpty()) continue;
            if (sb.length() > 0) sb.append(',');
            sb.append(t);
        }
        return sb.toString();
    }

    private static List<String> splitTags(String joined) {
        List<String> out = new ArrayList<>();
        if (joined == null || joined.isEmpty()) return out;
        for (String part : joined.split(",")) {
            String t = part.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    /** A {@code %needle%} pattern, or null when the query should not narrow anything. */
    private static String likeArg(String query) {
        String q = blankToNull(query);
        if (q == null) return null;
        return "%" + escapeLike(q.toLowerCase(Locale.ROOT)) + "%";
    }

    private static String escapeLike(String raw) {
        return raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static String join(List<String> parts, String sep) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (sb.length() > 0) sb.append(sep);
            sb.append(p);
        }
        return sb.toString();
    }

    private static void closeQuietly(Cursor c) {
        if (c != null) c.close();
    }
}
