package com.gama.nativeapp;

import android.app.Application;
import android.util.Log;

public class GamaApplication extends Application {
    private static final String TAG = "GamaApplication";

    @Override
    public void onCreate() {
        super.onCreate();
        System.setProperty("use_global_preference_store", "false");
        System.setProperty("java.util.prefs.PreferencesFactory", "com.gama.nativeapp.NoOpPreferencesFactory");
        Log.i(TAG, "Application started, prefs disabled");
    }
}
