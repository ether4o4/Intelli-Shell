package com.intellishell;

import android.app.Application;

/** Application entry point; installs crash reporting before anything else runs. */
public final class App extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        CrashReporter.install(this);
    }
}
