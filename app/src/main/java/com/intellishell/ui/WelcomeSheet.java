package com.intellishell.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.intellishell.R;

/**
 * The one-time "here's how this works" sheet, shown after setup finishes.
 * <p>
 * Three cards, no tour, no paging: the app is a chat box over a terminal, and the
 * only thing a new user actually has to do is give it a model. So the sheet says
 * what the two panes are, where the rest of the app lives, and puts the one required
 * action — pick an AI — on a button.
 * <p>
 * "Seen" is recorded the moment the sheet appears rather than when a button is
 * pressed. Someone who swipes it away has read as much of it as they intend to, and
 * showing it again on the next launch would be nagging.
 */
public final class WelcomeSheet {

    /** Lives in the same preference file as everything else the user configures. */
    private static final String KEY_SEEN = "seen_welcome";

    private WelcomeSheet() {
    }

    /** True once the sheet has been shown, whether or not the user acted on it. */
    public static boolean hasBeenSeen(Context context) {
        return prefs(context).getBoolean(KEY_SEEN, false);
    }

    /**
     * Shows the sheet if this is the user's first time here.
     *
     * @return true if it was shown.
     */
    public static boolean showIfFirstTime(Activity activity) {
        if (activity == null || activity.isFinishing()) return false;
        if (hasBeenSeen(activity)) return false;
        show(activity);
        return true;
    }

    /** Shows the sheet unconditionally — for a "how does this work again?" entry point. */
    public static void show(Activity activity) {
        if (activity == null || activity.isFinishing()) return;

        prefs(activity).edit().putBoolean(KEY_SEEN, true).apply();

        View content = LayoutInflater.from(activity).inflate(R.layout.view_welcome, null, false);
        BottomSheetDialog dialog = new BottomSheetDialog(activity);
        dialog.setContentView(content);

        // The window's own background would otherwise draw a light Material surface
        // behind our rounded corners on some devices.
        View sheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
        if (sheet != null) sheet.setBackgroundColor(0x00000000);

        Button config = content.findViewById(R.id.welcomeConfig);
        Button dismiss = content.findViewById(R.id.welcomeDismiss);

        config.setOnClickListener(v -> {
            dialog.dismiss();
            Intent intent = new Intent(activity, DashboardActivity.class);
            intent.putExtra(DashboardActivity.EXTRA_TAB, DashboardActivity.TAB_CONFIG);
            activity.startActivity(intent);
        });
        dismiss.setOnClickListener(v -> dialog.dismiss());

        dialog.show();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(ConfigFragment.PREFS, Context.MODE_PRIVATE);
    }
}
