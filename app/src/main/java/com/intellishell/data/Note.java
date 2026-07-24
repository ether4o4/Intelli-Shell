package com.intellishell.data;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A free-form note.
 * <p>
 * Notes have no explicit tag field: organisation comes from {@code #hashtags} typed
 * inline in the body. That keeps writing frictionless — there is no second UI to
 * visit — and it means the tag list can never drift out of sync with the prose.
 * {@link Db} derives and indexes the tags on save.
 */
public class Note {

    /**
     * Matches a hashtag that starts a word.
     * <p>
     * The lookbehind stops us claiming the {@code #} inside {@code foo#bar} or a URL
     * fragment, and requiring a letter/digit/underscore first rejects noise like
     * {@code #---}. A leading {@code # } (markdown heading) never matches because the
     * space is not part of the tag character class.
     */
    private static final Pattern HASHTAG =
        Pattern.compile("(?<![\\p{L}\\p{N}_])#([\\p{L}\\p{N}_][\\p{L}\\p{N}_-]*)");

    /** Row id; {@code <= 0} means "not saved yet". */
    public long id;

    public String title;
    public String body;

    /** Epoch millis, refreshed on every save. */
    public long updatedAt;

    public Note() {
        this(0L, "", "", 0L);
    }

    /** New, unsaved note. */
    public Note(String title, String body) {
        this(0L, title, body, 0L);
    }

    public Note(long id, String title, String body, long updatedAt) {
        this.id = id;
        this.title = title;
        this.body = body;
        this.updatedAt = updatedAt;
    }

    /**
     * Pulls the {@code #hashtags} out of {@code body}.
     * <p>
     * Tags come back lowercased and without the leading {@code #}, de-duplicated but
     * in the order they first appear, so the UI can show them the way the note reads.
     * Never returns null.
     */
    public static List<String> extractHashtags(String body) {
        List<String> out = new ArrayList<>();
        if (body == null || body.isEmpty()) return out;

        // LinkedHashSet does the de-duplication without disturbing first-seen order.
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        Matcher m = HASHTAG.matcher(body);
        while (m.find()) {
            seen.add(m.group(1).toLowerCase(Locale.ROOT));
        }
        out.addAll(seen);
        return out;
    }

    /** This note's hashtags, derived from the current body. */
    public List<String> hashtags() {
        return extractHashtags(body);
    }

    @Override
    public String toString() {
        return title != null && !title.isEmpty() ? title : "(untitled note)";
    }
}
