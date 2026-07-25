package com.intellishell.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.intellishell.R;

import java.util.function.Consumer;

/**
 * The row of tappable prompts that sits above the composer until the user speaks.
 * <p>
 * An empty chat box is the hardest part of an assistant to answer. These five chips
 * are the jobs people actually open a shell for, written the way you would say them
 * out loud, so the first message is one tap away and the user learns by example what
 * kind of thing to ask for.
 * <p>
 * Tapping a chip fills the input rather than sending it — the whole point of the app
 * is that the user stays in control, and the prompt is a starting point they can edit.
 */
public final class QuickStart {

    /**
     * Chip label paired with the sentence it drops into the composer. The label is
     * short enough to scan in a scrolling row; the prompt is a full request, because
     * "Install Python" alone tells the model less than it needs.
     */
    private static final int[][] SUGGESTIONS = {
        {R.string.quick_python_label, R.string.quick_python_prompt},
        {R.string.quick_ssh_label, R.string.quick_ssh_prompt},
        {R.string.quick_update_label, R.string.quick_update_prompt},
        {R.string.quick_storage_label, R.string.quick_storage_prompt},
        {R.string.quick_backup_label, R.string.quick_backup_prompt},
    };

    private QuickStart() {
    }

    /**
     * Fills {@code container} with the suggestion chips and makes it visible.
     *
     * @param container either the {@link HorizontalScrollView} wrapper or the row
     *                  inside it — both are accepted so callers can pass the same
     *                  view they later hand to {@link #hide(View)}.
     * @param onPick    receives the full prompt text of the chip that was tapped.
     */
    public static void populate(ViewGroup container, Consumer<String> onPick) {
        if (container == null || onPick == null) return;

        ViewGroup row = rowOf(container);
        if (row == null) return;

        Context context = row.getContext();
        row.removeAllViews();
        for (int[] suggestion : SUGGESTIONS) {
            TextView chip = chip(context, context.getString(suggestion[0]),
                context.getString(suggestion[1]), onPick);
            row.addView(chip, spacingFor(row, context));
        }
        container.setVisibility(View.VISIBLE);
    }

    /** Takes the row away for good — call it once the user has sent a message. */
    public static void hide(View container) {
        if (container == null) return;
        container.setVisibility(View.GONE);
    }

    /**
     * The scroll view holds exactly one child, the row itself. Unwrapping here means
     * the caller can treat the whole thing as one view.
     */
    private static ViewGroup rowOf(ViewGroup container) {
        if (container instanceof HorizontalScrollView) {
            if (container.getChildCount() == 0) return null;
            View first = container.getChildAt(0);
            return first instanceof ViewGroup ? (ViewGroup) first : null;
        }
        return container;
    }

    private static TextView chip(Context context, String label, String prompt,
                                 Consumer<String> onPick) {
        TextView chip = new TextView(context);
        chip.setText(label);
        chip.setTextColor(ContextCompat.getColor(context, R.color.accent));
        chip.setTextSize(13f);
        chip.setSingleLine(true);
        chip.setGravity(Gravity.CENTER);
        chip.setBackgroundResource(R.drawable.bg_chip_quick);
        int padH = dp(context, 14);
        int padV = dp(context, 9);
        chip.setPadding(padH, padV, padH, padV);
        // Floor for the tap target, so a two-word label is no harder to hit than a long one.
        chip.setMinHeight(dp(context, 44));
        chip.setClickable(true);
        chip.setFocusable(true);
        chip.setOnClickListener(v -> onPick.accept(prompt));
        return chip;
    }

    /**
     * Gap between chips. The params type has to match the row's own class or the
     * parent throws while measuring, so a non-LinearLayout row gets plain margins.
     */
    private static ViewGroup.MarginLayoutParams spacingFor(ViewGroup row, Context context) {
        ViewGroup.MarginLayoutParams lp = row instanceof LinearLayout
            ? new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            : new ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, dp(context, 8), 0);
        return lp;
    }

    private static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
