package com.intellishell.ui;

import android.annotation.SuppressLint;
import android.os.Bundle;
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
import com.intellishell.data.MemoryEntry;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The Memory tab: everything the assistant carries between conversations.
 * <p>
 * This store writes itself — the agent calls {@link Db#rememberFact} as it works — so
 * the screen leads with an explanation. A user who does not know the list is
 * self-populating will never think to correct it, and an uncorrected wrong fact is
 * repeated into every future prompt.
 * <p>
 * Entries arrive from {@link Db#listMemory()} already ordered by canonical category,
 * so grouping is a single pass that emits a header whenever the category changes.
 */
public class MemoryFragment extends Fragment {

    /** Db's own fallback bucket for a blank category; mirrored here for the picker. */
    private static final String DEFAULT_CATEGORY = "important-facts";

    private Db db;

    private ScrollView root;
    private EditText contentField;
    private TextView editorTitle;
    private TextView emptyView;
    private LinearLayout listContainer;
    private LinearLayout categoryStrip;

    private String selectedCategory = MemoryEntry.CATEGORIES[0];

    /** Row being edited; {@code 0} means the editor is composing a new fact. */
    private long editingId;

    /** Row timestamps. UI thread only — SimpleDateFormat is not thread-safe. */
    private final SimpleDateFormat stamp =
        new SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault());

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        db = Db.get(requireContext());

        root = (ScrollView) inflater.inflate(R.layout.fragment_memory, container, false);

        contentField = root.findViewById(R.id.memory_content);
        editorTitle = root.findViewById(R.id.memory_editor_title);
        emptyView = root.findViewById(R.id.memory_empty);
        listContainer = root.findViewById(R.id.memory_list);
        categoryStrip = root.findViewById(R.id.memory_category_strip);

        keepHorizontalDrags(root.findViewById(R.id.memory_category_scroll));

        Button save = root.findViewById(R.id.memory_save);
        save.setOnClickListener(v -> saveEditor());

        Button clear = root.findViewById(R.id.memory_clear);
        clear.setOnClickListener(v -> clearEditor());

        renderCategoryPicker();
        return root;
    }

    @Override
    public void onResume() {
        super.onResume();
        // The agent may have written here while the user was in the chat tab.
        reload();
    }

    // ------------------------------------------------------------------ loading

    private void reload() {
        DashboardActivity.io(() -> {
            final List<MemoryEntry> entries = db.listMemory();
            DashboardActivity.main(() -> {
                if (!isAdded()) return;
                renderList(entries);
                renderCategoryPicker();
            });
        });
    }

    // ----------------------------------------------------------------- rendering

    /**
     * The known buckets, plus any category already present on the entry being edited.
     * Keeping an unknown category selectable means correcting such an entry does not
     * silently re-file it.
     */
    private void renderCategoryPicker() {
        categoryStrip.removeAllViews();

        Set<String> categories = new LinkedHashSet<>();
        for (String c : MemoryEntry.CATEGORIES) categories.add(c);
        categories.add(selectedCategory);

        for (final String category : categories) {
            TextView chip = newChip(categoryStrip, category, category.equals(selectedCategory));
            chip.setOnClickListener(v -> {
                selectedCategory = category;
                renderCategoryPicker();
            });
            categoryStrip.addView(chip);
        }
    }

    private void renderList(List<MemoryEntry> entries) {
        listContainer.removeAllViews();

        if (entries.isEmpty()) {
            emptyView.setVisibility(View.VISIBLE);
            return;
        }
        emptyView.setVisibility(View.GONE);

        LayoutInflater inflater = LayoutInflater.from(requireContext());
        String currentCategory = null;

        for (final MemoryEntry entry : entries) {
            String category = bucketOf(entry.category);
            if (!category.equals(currentCategory)) {
                TextView header = (TextView) inflater.inflate(
                    R.layout.row_memory_header, listContainer, false);
                header.setText(category);
                listContainer.addView(header);
                currentCategory = category;
            }

            View row = inflater.inflate(R.layout.row_memory, listContainer, false);

            TextView content = row.findViewById(R.id.row_memory_content);
            content.setText(entry.content == null ? "" : entry.content.trim());

            TextView meta = row.findViewById(R.id.row_memory_meta);
            meta.setText(getString(R.string.dash_memory_meta, when(entry.updatedAt)));

            Button edit = row.findViewById(R.id.row_memory_edit);
            edit.setOnClickListener(v -> bindEditor(entry));

            Button delete = row.findViewById(R.id.row_memory_delete);
            delete.setOnClickListener(v -> confirmDelete(entry));

            listContainer.addView(row);
        }
    }

    // ------------------------------------------------------------------- actions

    private void bindEditor(MemoryEntry entry) {
        editingId = entry.id;
        selectedCategory = bucketOf(entry.category);
        contentField.setText(entry.content);
        renderCategoryPicker();
        editorTitle.setText(R.string.dash_memory_editing);
        root.smoothScrollTo(0, 0);
        contentField.requestFocus();
    }

    private void clearEditor() {
        editingId = 0L;
        selectedCategory = MemoryEntry.CATEGORIES[0];
        contentField.setText("");
        renderCategoryPicker();
        editorTitle.setText(R.string.dash_memory_editor);
    }

    private void saveEditor() {
        String content = contentField.getText().toString().trim();
        if (content.isEmpty()) {
            toast(R.string.dash_memory_need_content);
            contentField.requestFocus();
            return;
        }

        final MemoryEntry entry = new MemoryEntry(editingId, selectedCategory, content, 0L);
        DashboardActivity.io(() -> {
            db.saveMemory(entry);
            DashboardActivity.main(() -> {
                if (!isAdded()) return;
                toast(R.string.dash_memory_saved);
                clearEditor();
                reload();
            });
        });
    }

    private void confirmDelete(final MemoryEntry entry) {
        String label = entry.content == null ? "" : entry.content.trim();
        new AlertDialog.Builder(requireContext(),
            androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
            .setTitle(R.string.dash_delete_confirm_title)
            .setMessage(getString(R.string.dash_delete_confirm_named, label))
            .setNegativeButton(R.string.dash_cancel, null)
            .setPositiveButton(R.string.dash_delete, (dialog, which) -> delete(entry))
            .show();
    }

    private void delete(final MemoryEntry entry) {
        final long id = entry.id;
        DashboardActivity.io(() -> {
            db.deleteMemory(id);
            DashboardActivity.main(() -> {
                if (!isAdded()) return;
                if (editingId == id) clearEditor();
                toast(R.string.dash_memory_deleted);
                reload();
            });
        });
    }

    // ------------------------------------------------------------------- helpers

    /** Mirrors Db's normalisation so headers match the order rows actually arrive in. */
    private static String bucketOf(String category) {
        if (category == null) return DEFAULT_CATEGORY;
        String c = category.trim();
        return c.isEmpty() ? DEFAULT_CATEGORY : c;
    }

    private TextView newChip(ViewGroup parent, CharSequence label, boolean selected) {
        TextView chip = (TextView) LayoutInflater.from(requireContext())
            .inflate(R.layout.view_dash_chip, parent, false);
        chip.setText(label);
        chip.setSelected(selected);
        return chip;
    }

    /** Keeps a sideways drag on the category strip away from the pager. */
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
