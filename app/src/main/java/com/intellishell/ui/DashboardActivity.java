package com.intellishell.ui;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;
import com.intellishell.R;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * The dashboard: everything the assistant knows and everything it is configured with.
 * <p>
 * Four tabs, one page each — saved Scripts, Notes, the assistant's Memory, and AI
 * Config. They are siblings rather than separate activities so the user can move
 * between them without losing an in-progress edit, and so the chat screen can deep-link
 * straight to one with {@link #EXTRA_TAB}.
 * <p>
 * The activity itself is deliberately thin: it owns the pager, the tab strip, and the
 * background executor the tabs share. All state lives in
 * {@link com.intellishell.data.Db} or {@link android.content.SharedPreferences}, so a
 * process death costs nothing but the text in an open editor.
 */
public class DashboardActivity extends AppCompatActivity {

    /** Optional {@code int} extra: which tab to open on. Out-of-range values are clamped. */
    public static final String EXTRA_TAB = "tab";

    /**
     * Result extra carrying a command the user asked to run. The caller (the chat and
     * terminal screen) is what actually has a shell, so the dashboard hands the text
     * back rather than executing anything itself.
     */
    public static final String EXTRA_RUN_SCRIPT = "run_script";

    public static final int TAB_SCRIPTS = 0;
    public static final int TAB_NOTES = 1;
    public static final int TAB_MEMORY = 2;
    public static final int TAB_CONFIG = 3;

    private static final int[] TAB_TITLES = {
        R.string.dash_tab_scripts,
        R.string.dash_tab_notes,
        R.string.dash_tab_memory,
        R.string.dash_tab_config
    };

    /**
     * Shared worker for the tabs' database and socket calls.
     * <p>
     * Single-threaded on purpose: every query here is cheap, and serialising them keeps
     * a save and the reload it triggers in the order the user performed them. Static
     * because fragments outlive individual activity instances across a rotation, and a
     * daemon thread so it can never hold the process open.
     */
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override
        public Thread newThread(@NonNull Runnable r) {
            Thread t = new Thread(r, "dashboard-io");
            t.setDaemon(true);
            return t;
        }
    });

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private ViewPager2 pager;

    /** Runs {@code task} off the UI thread. {@link com.intellishell.data.Db} blocks. */
    static void io(Runnable task) {
        IO.execute(task);
    }

    /**
     * Posts {@code task} back to the UI thread. Callers must still re-check
     * {@code isAdded()} inside it — the fragment may be gone by the time it runs.
     */
    static void main(Runnable task) {
        MAIN.post(task);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dashboard);

        TabLayout tabs = findViewById(R.id.dash_tabs);
        pager = findViewById(R.id.dash_pager);

        pager.setAdapter(new Pages(this));
        // All four pages are cheap and users flick between them constantly; keeping
        // them alive avoids re-querying and re-losing scroll position on every swipe.
        pager.setOffscreenPageLimit(TAB_TITLES.length - 1);

        new TabLayoutMediator(tabs, pager, (tab, position) ->
            tab.setText(TAB_TITLES[position])).attach();

        // Only honour the requested tab on a fresh start; after a rotation the pager
        // has already restored whichever tab the user was actually on.
        if (savedInstanceState == null) {
            pager.setCurrentItem(requestedTab(getIntent()), false);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (pager != null) pager.setCurrentItem(requestedTab(intent), false);
    }

    private static int requestedTab(Intent intent) {
        if (intent == null) return TAB_SCRIPTS;
        int tab = intent.getIntExtra(EXTRA_TAB, TAB_SCRIPTS);
        if (tab < 0) return TAB_SCRIPTS;
        if (tab >= TAB_TITLES.length) return TAB_TITLES.length - 1;
        return tab;
    }

    /**
     * Finishes with a command for the caller to run.
     * <p>
     * Lives here rather than in {@link ScriptsFragment} because the result belongs to
     * the activity, and a fragment calling {@code getActivity().setResult(...)} on a
     * detached host would be a crash waiting to happen.
     */
    void finishWithScript(String command) {
        Intent result = new Intent();
        result.putExtra(EXTRA_RUN_SCRIPT, command != null ? command : "");
        setResult(RESULT_OK, result);
        finish();
    }

    private static final class Pages extends FragmentStateAdapter {

        Pages(AppCompatActivity host) {
            super(host);
        }

        @NonNull
        @Override
        public Fragment createFragment(int position) {
            switch (position) {
                case TAB_NOTES:
                    return new NotesFragment();
                case TAB_MEMORY:
                    return new MemoryFragment();
                case TAB_CONFIG:
                    return new ConfigFragment();
                case TAB_SCRIPTS:
                default:
                    return new ScriptsFragment();
            }
        }

        @Override
        public int getItemCount() {
            return TAB_TITLES.length;
        }
    }
}
