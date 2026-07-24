package com.intellishell.ui;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
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
import androidx.fragment.app.Fragment;

import com.intellishell.R;
import com.intellishell.data.Db;
import com.intellishell.data.Script;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The Scripts tab: a personal library of shell snippets.
 * <p>
 * The list is rendered as plain rows inside a {@link ScrollView} rather than through a
 * {@code RecyclerView}. A hand-written library runs to tens of entries, so view
 * recycling would buy nothing, and this way the editor and the list share one scroll —
 * which is what makes "edit, look at the list, edit again" feel like one screen.
 * <p>
 * Running a script is not this tab's job. It hands the command back to the caller via
 * {@link DashboardActivity#finishWithScript}, because the terminal lives there.
 */
public class ScriptsFragment extends Fragment {

    private Db db;

    private ScrollView root;
    private EditText search;
    private EditText nameField;
    private EditText languageField;
    private EditText bodyField;
    private EditText runField;
    private TextView editorTitle;
    private TextView emptyView;
    private LinearLayout listContainer;
    private LinearLayout filterStrip;
    private LinearLayout tagStrip;

    /** Tags ticked in the editor. Insertion-ordered so they save the way they read. */
    private final Set<String> pickedTags = new LinkedHashSet<>();

    /** Active tag filter, or null for "All". */
    private String filterTag;

    /** Row being edited; {@code 0} means the editor is composing a new script. */
    private long editingId;

    /** Preserved across an edit so re-saving does not reset the script's age. */
    private long editingCreatedAt;

    /** Row timestamps. Only ever touched on the UI thread — SimpleDateFormat is not thread-safe. */
    private final SimpleDateFormat stamp =
        new SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault());

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        db = Db.get(requireContext());

        root = (ScrollView) inflater.inflate(R.layout.fragment_scripts, container, false);

        search = root.findViewById(R.id.scripts_search);
        nameField = root.findViewById(R.id.scripts_name);
        languageField = root.findViewById(R.id.scripts_language);
        bodyField = root.findViewById(R.id.scripts_body);
        runField = root.findViewById(R.id.scripts_run_command);
        editorTitle = root.findViewById(R.id.scripts_editor_title);
        emptyView = root.findViewById(R.id.scripts_empty);
        listContainer = root.findViewById(R.id.scripts_list);
        filterStrip = root.findViewById(R.id.scripts_filter_strip);
        tagStrip = root.findViewById(R.id.scripts_tag_strip);

        keepHorizontalDrags(root.findViewById(R.id.scripts_filter_scroll));
        keepHorizontalDrags(root.findViewById(R.id.scripts_tag_scroll));

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

        Button save = root.findViewById(R.id.scripts_save);
        save.setOnClickListener(v -> saveEditor());

        Button clear = root.findViewById(R.id.scripts_clear);
        clear.setOnClickListener(v -> clearEditor());

        renderTagPicker();
        return root;
    }

    @Override
    public void onResume() {
        super.onResume();
        // Another screen (or the assistant) may have saved a script since we last drew.
        reload();
    }

    // ------------------------------------------------------------------ loading

    private void reload() {
        final String query = search.getText().toString();
        final String tag = filterTag;

        DashboardActivity.io(() -> {
            final List<Script> matches = db.searchScripts(query, tag);
            // The filter strip offers tags that actually exist, not the full label set,
            // so every chip in it is guaranteed to return something.
            final List<String> inUse = tagsInUse(db.listScripts());

            DashboardActivity.main(() -> {
                if (!isAdded()) return;
                renderFilterStrip(inUse);
                renderList(matches, query, tag);
            });
        });
    }

    private static List<String> tagsInUse(List<Script> scripts) {
        Set<String> seen = new LinkedHashSet<>();
        for (Script s : scripts) {
            if (s.tags == null) continue;
            for (String t : s.tags) {
                if (t != null && !t.trim().isEmpty()) seen.add(t.trim());
            }
        }
        return new ArrayList<>(seen);
    }

    // ----------------------------------------------------------------- rendering

    private void renderFilterStrip(List<String> tags) {
        filterStrip.removeAllViews();

        TextView all = newChip(filterStrip, getString(R.string.dash_all), filterTag == null);
        all.setOnClickListener(v -> {
            filterTag = null;
            reload();
        });
        filterStrip.addView(all);

        for (final String tag : tags) {
            TextView chip = newChip(filterStrip, tag, tag.equalsIgnoreCase(filterTag));
            chip.setOnClickListener(v -> {
                // Tapping the active chip clears the filter, so the strip is a toggle
                // rather than a trap the user has to find their way out of.
                filterTag = tag.equalsIgnoreCase(filterTag) ? null : tag;
                reload();
            });
            filterStrip.addView(chip);
        }
    }

    /** The editor's multi-select tags: the suggested labels, plus anything already saved. */
    private void renderTagPicker() {
        tagStrip.removeAllViews();

        Set<String> labels = new LinkedHashSet<>();
        for (String label : Script.LABELS) labels.add(label);
        for (String picked : pickedTags) {
            // A tag the agent wrote as "ssh" must not appear beside the "SSH" label;
            // two chips for one tag would both look selected and both toggle together.
            if (!containsIgnoreCase(labels, picked)) labels.add(picked);
        }

        for (final String label : labels) {
            TextView chip = newChip(tagStrip, label, containsIgnoreCase(pickedTags, label));
            chip.setOnClickListener(v -> {
                if (containsIgnoreCase(pickedTags, label)) {
                    removeIgnoreCase(pickedTags, label);
                } else {
                    pickedTags.add(label);
                }
                chip.setSelected(containsIgnoreCase(pickedTags, label));
            });
            tagStrip.addView(chip);
        }
    }

    private void renderList(List<Script> scripts, String query, String tag) {
        listContainer.removeAllViews();

        if (scripts.isEmpty()) {
            boolean narrowed = !query.trim().isEmpty() || tag != null;
            emptyView.setText(narrowed ? R.string.dash_scripts_none_match : R.string.dash_scripts_empty);
            emptyView.setVisibility(View.VISIBLE);
            return;
        }
        emptyView.setVisibility(View.GONE);

        LayoutInflater inflater = LayoutInflater.from(requireContext());
        for (final Script script : scripts) {
            View row = inflater.inflate(R.layout.row_script, listContainer, false);

            TextView name = row.findViewById(R.id.row_script_name);
            name.setText(script.toString());

            TextView meta = row.findViewById(R.id.row_script_meta);
            String language = script.language == null || script.language.trim().isEmpty()
                ? "sh" : script.language.trim();
            meta.setText(getString(R.string.dash_scripts_meta, language, when(script.createdAt)));

            TextView tags = row.findViewById(R.id.row_script_tags);
            if (script.tags == null || script.tags.isEmpty()) {
                tags.setVisibility(View.GONE);
            } else {
                tags.setVisibility(View.VISIBLE);
                tags.setText(getString(R.string.dash_scripts_tag_line,
                    TextUtils.join(", ", script.tags)));
            }

            TextView preview = row.findViewById(R.id.row_script_preview);
            preview.setText(script.body == null ? "" : script.body.trim());

            Button run = row.findViewById(R.id.row_script_run);
            run.setOnClickListener(v -> run(script));

            Button edit = row.findViewById(R.id.row_script_edit);
            edit.setOnClickListener(v -> bindEditor(script));

            Button delete = row.findViewById(R.id.row_script_delete);
            delete.setOnClickListener(v -> confirmDelete(script));

            listContainer.addView(row);
        }
    }

    // ------------------------------------------------------------------- actions

    private void run(Script script) {
        // effectiveCommand(), not the raw body: a Python script's body is not something
        // a shell can execute, which is exactly why Script carries a run command.
        String command = script.effectiveCommand();
        if (getActivity() instanceof DashboardActivity) {
            ((DashboardActivity) getActivity()).finishWithScript(command);
        }
    }

    private void bindEditor(Script script) {
        editingId = script.id;
        editingCreatedAt = script.createdAt;

        nameField.setText(script.name);
        languageField.setText(script.language);
        bodyField.setText(script.body);
        runField.setText(script.runCommand);

        pickedTags.clear();
        if (script.tags != null) pickedTags.addAll(script.tags);
        renderTagPicker();

        editorTitle.setText(getString(R.string.dash_scripts_editing, script.toString()));
        root.smoothScrollTo(0, 0);
        nameField.requestFocus();
    }

    private void clearEditor() {
        editingId = 0L;
        editingCreatedAt = 0L;

        nameField.setText("");
        languageField.setText("");
        bodyField.setText("");
        runField.setText("");

        pickedTags.clear();
        renderTagPicker();

        editorTitle.setText(R.string.dash_scripts_editor);
    }

    private void saveEditor() {
        String name = nameField.getText().toString().trim();
        if (name.isEmpty()) {
            toast(R.string.dash_scripts_need_name);
            nameField.requestFocus();
            return;
        }

        String body = bodyField.getText().toString();
        if (body.trim().isEmpty()) {
            toast(R.string.dash_scripts_need_body);
            bodyField.requestFocus();
            return;
        }

        String language = languageField.getText().toString().trim();
        if (language.isEmpty()) language = "bash";

        final Script script = new Script(editingId, name, body, language,
            new ArrayList<>(pickedTags), runField.getText().toString().trim(), editingCreatedAt);

        DashboardActivity.io(() -> {
            db.saveScript(script);
            DashboardActivity.main(() -> {
                if (!isAdded()) return;
                toast(R.string.dash_scripts_saved);
                clearEditor();
                reload();
            });
        });
    }

    private void confirmDelete(final Script script) {
        // Theme.AppCompat is dark, which is the only way to be sure this dialog is
        // readable without knowing what the host application theme looks like.
        new AlertDialog.Builder(requireContext(),
            androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
            .setTitle(R.string.dash_delete_confirm_title)
            .setMessage(getString(R.string.dash_delete_confirm_named, script.toString()))
            .setNegativeButton(R.string.dash_cancel, null)
            .setPositiveButton(R.string.dash_delete, (dialog, which) -> delete(script))
            .show();
    }

    private void delete(final Script script) {
        final long id = script.id;
        DashboardActivity.io(() -> {
            db.deleteScript(id);
            DashboardActivity.main(() -> {
                if (!isAdded()) return;
                // The editor was holding the row that just went away.
                if (editingId == id) clearEditor();
                toast(R.string.dash_scripts_deleted);
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

    /**
     * Stops the pager from stealing a sideways drag on a chip strip. Without this the
     * tab swipe wins and the strip can only be scrolled by luck.
     */
    @SuppressLint("ClickableViewAccessibility")
    private static void keepHorizontalDrags(HorizontalScrollView strip) {
        strip.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                v.getParent().requestDisallowInterceptTouchEvent(true);
            }
            return false;
        });
    }

    private static boolean containsIgnoreCase(Set<String> set, String value) {
        for (String s : set) {
            if (s.equalsIgnoreCase(value)) return true;
        }
        return false;
    }

    private static void removeIgnoreCase(Set<String> set, String value) {
        for (String s : new ArrayList<>(set)) {
            if (s.equalsIgnoreCase(value)) set.remove(s);
        }
    }

    private String when(long epochMillis) {
        if (epochMillis <= 0) return "—";
        return stamp.format(new Date(epochMillis));
    }

    private void toast(int messageRes) {
        Toast.makeText(requireContext(), messageRes, Toast.LENGTH_SHORT).show();
    }
}
