package com.intellishell.ui;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.intellishell.R;
import com.intellishell.data.Db;
import com.intellishell.data.Note;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The Notes tab: free-form writing, organised entirely by inline {@code #hashtags}.
 * <p>
 * There is no tag editor anywhere on this screen, and that is the point — tags come
 * out of the prose, so the chip strip is a view of what has been written rather than a
 * second thing to maintain. Tapping a chip filters; tapping it again clears.
 */
public class NotesFragment extends Fragment {

    private Db db;

    private ScrollView root;
    private EditText search;
    private EditText titleField;
    private EditText bodyField;
    private TextView editorTitle;
    private TextView editorTags;
    private TextView filterHint;
    private TextView emptyView;
    private LinearLayout listContainer;
    private LinearLayout tagStrip;

    /** Active hashtag filter (stored form, no leading '#'), or null for everything. */
    private String filterTag;

    /** Row being edited; {@code 0} means the editor is composing a new note. */
    private long editingId;

    /** Row timestamps. UI thread only — SimpleDateFormat is not thread-safe. */
    private final SimpleDateFormat stamp =
        new SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault());

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        db = Db.get(requireContext());

        root = (ScrollView) inflater.inflate(R.layout.fragment_notes, container, false);

        search = root.findViewById(R.id.notes_search);
        titleField = root.findViewById(R.id.notes_title);
        bodyField = root.findViewById(R.id.notes_body);
        editorTitle = root.findViewById(R.id.notes_editor_title);
        editorTags = root.findViewById(R.id.notes_editor_tags);
        filterHint = root.findViewById(R.id.notes_filter_hint);
        emptyView = root.findViewById(R.id.notes_empty);
        listContainer = root.findViewById(R.id.notes_list);
        tagStrip = root.findViewById(R.id.notes_tag_strip);

        keepHorizontalDrags(root.findViewById(R.id.notes_tag_scroll));

        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) { }

            @Override
            public void afterTextChanged(Editable s) {
                reload();
            }
        });

        // Live echo of the tags the body currently declares, so the user can see a
        // hashtag register the moment they type it.
        bodyField.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) { }

            @Override
            public void afterTextChanged(Editable s) {
                showEditorTags(s.toString());
            }
        });

        Button save = root.findViewById(R.id.notes_save);
        save.setOnClickListener(v -> saveEditor());

        Button clear = root.findViewById(R.id.notes_clear);
        clear.setOnClickListener(v -> clearEditor());

        showEditorTags("");
        return root;
    }

    @Override
    public void onResume() {
        super.onResume();
        reload();
    }

    // ------------------------------------------------------------------ loading

    private void reload() {
        final String query = search.getText().toString();
        final String tag = filterTag;

        DashboardActivity.io(() -> {
            final List<Note> matches = db.searchNotes(query, tag);
            final List<String> hashtags = db.allHashtags();

            DashboardActivity.main(() -> {
                if (!isAdded()) return;
                renderTagStrip(hashtags);
                renderList(matches, query, tag);
            });
        });
    }

    // ----------------------------------------------------------------- rendering

    private void renderTagStrip(List<String> hashtags) {
        tagStrip.removeAllViews();

        if (hashtags.isEmpty()) {
            TextView none = new TextView(requireContext());
            none.setText(R.string.dash_notes_no_hashtags);
            none.setTextSize(12f);
            none.setTextColor(ContextCompat.getColor(requireContext(), R.color.dash_muted));
            tagStrip.addView(none);
        }

        for (final String tag : hashtags) {
            boolean active = tag.equalsIgnoreCase(filterTag);
            TextView chip = newChip(tagStrip, "#" + tag, active);
            chip.setOnClickListener(v -> {
                filterTag = active ? null : tag;
                reload();
            });
            tagStrip.addView(chip);
        }

        if (filterTag == null) {
            filterHint.setVisibility(View.GONE);
        } else {
            filterHint.setVisibility(View.VISIBLE);
            filterHint.setText(getString(R.string.dash_notes_filtering, filterTag));
        }
    }

    private void renderList(List<Note> notes, String query, String tag) {
        listContainer.removeAllViews();

        if (notes.isEmpty()) {
            boolean narrowed = !query.trim().isEmpty() || tag != null;
            emptyView.setText(narrowed ? R.string.dash_notes_none_match : R.string.dash_notes_empty);
            emptyView.setVisibility(View.VISIBLE);
            return;
        }
        emptyView.setVisibility(View.GONE);

        LayoutInflater inflater = LayoutInflater.from(requireContext());
        for (final Note note : notes) {
            View row = inflater.inflate(R.layout.row_note, listContainer, false);

            TextView title = row.findViewById(R.id.row_note_title);
            title.setText(note.toString());

            TextView meta = row.findViewById(R.id.row_note_meta);
            meta.setText(getString(R.string.dash_notes_meta, when(note.updatedAt)));

            TextView preview = row.findViewById(R.id.row_note_preview);
            preview.setText(note.body == null ? "" : note.body.trim());

            TextView tags = row.findViewById(R.id.row_note_tags);
            List<String> hashtags = note.hashtags();
            if (hashtags.isEmpty()) {
                tags.setVisibility(View.GONE);
            } else {
                tags.setVisibility(View.VISIBLE);
                tags.setText(hashLine(hashtags));
            }

            // The whole row opens the note; the buttons are for people who prefer targets.
            row.setOnClickListener(v -> bindEditor(note));

            Button edit = row.findViewById(R.id.row_note_edit);
            edit.setOnClickListener(v -> bindEditor(note));

            Button delete = row.findViewById(R.id.row_note_delete);
            delete.setOnClickListener(v -> confirmDelete(note));

            listContainer.addView(row);
        }
    }

    private void showEditorTags(String body) {
        List<String> tags = Note.extractHashtags(body);
        if (tags.isEmpty()) {
            editorTags.setVisibility(View.GONE);
        } else {
            editorTags.setVisibility(View.VISIBLE);
            editorTags.setText(hashLine(tags));
        }
    }

    private static String hashLine(List<String> tags) {
        StringBuilder sb = new StringBuilder();
        for (String tag : tags) {
            if (sb.length() > 0) sb.append(' ');
            sb.append('#').append(tag);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------- actions

    private void bindEditor(Note note) {
        editingId = note.id;
        titleField.setText(note.title);
        bodyField.setText(note.body);
        showEditorTags(note.body == null ? "" : note.body);
        editorTitle.setText(getString(R.string.dash_notes_editing, note.toString()));
        root.smoothScrollTo(0, 0);
        titleField.requestFocus();
    }

    private void clearEditor() {
        editingId = 0L;
        titleField.setText("");
        bodyField.setText("");
        showEditorTags("");
        editorTitle.setText(R.string.dash_notes_editor);
    }

    private void saveEditor() {
        String title = titleField.getText().toString().trim();
        String body = bodyField.getText().toString();

        // A note with neither is nothing; a note with only one of them is still a note.
        if (title.isEmpty() && body.trim().isEmpty()) {
            toast(R.string.dash_notes_need_body);
            bodyField.requestFocus();
            return;
        }

        final Note note = new Note(editingId, title, body, 0L);
        DashboardActivity.io(() -> {
            db.saveNote(note);
            DashboardActivity.main(() -> {
                if (!isAdded()) return;
                toast(R.string.dash_notes_saved);
                clearEditor();
                reload();
            });
        });
    }

    private void confirmDelete(final Note note) {
        new AlertDialog.Builder(requireContext(),
            androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
            .setTitle(R.string.dash_delete_confirm_title)
            .setMessage(getString(R.string.dash_delete_confirm_named, note.toString()))
            .setNegativeButton(R.string.dash_cancel, null)
            .setPositiveButton(R.string.dash_delete, (dialog, which) -> delete(note))
            .show();
    }

    private void delete(final Note note) {
        final long id = note.id;
        DashboardActivity.io(() -> {
            db.deleteNote(id);
            DashboardActivity.main(() -> {
                if (!isAdded()) return;
                if (editingId == id) clearEditor();
                toast(R.string.dash_notes_deleted);
                reload();
            });
        });
    }

    // ------------------------------------------------------------------- helpers

    private TextView newChip(ViewGroup parent, CharSequence label, boolean selected) {
        TextView chip = (TextView) LayoutInflater.from(requireContext())
            .inflate(R.layout.view_dash_chip, parent, false);
        chip.setText(label);
        chip.setSelected(selected);
        return chip;
    }

    /** Keeps a sideways drag on the tag strip away from the pager. */
    @SuppressLint("ClickableViewAccessibility")
    private static void keepHorizontalDrags(HorizontalScrollView strip) {
        strip.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                v.getParent().requestDisallowInterceptTouchEvent(true);
            }
            return false;
        });
    }

    private String when(long epochMillis) {
        if (epochMillis <= 0) return "—";
        return stamp.format(new Date(epochMillis));
    }

    private void toast(int messageRes) {
        Toast.makeText(requireContext(), messageRes, Toast.LENGTH_SHORT).show();
    }
}
