package com.gama.nativeapp;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public class ExperimentActivity extends Activity {

    private static final String TAG = "ExperimentActivity";
    private TextView logView;
    private LinearLayout experimentPanel;
    private FrameLayout displayContainer;
    private TextView cycleText;
    private TextView statusText;
    private Button playPauseBtn;
    private Button stepBtn;
    private Button stopBtn;
    private LinearLayout displayToolbar;
    private LinearLayout consolePanel;
    private LinearLayout root;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private Object compiledModel;
    private String modelName;
    private volatile boolean isRunning = false;
    private volatile boolean isPaused = false;
    private Object currentExpPlan;
    private Object currentController;
    private Runnable statePollRunnable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setGuiActivity(this);
        modelName = getIntent().getStringExtra("model_name");

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF1E1E1E);

        buildTopBar();
        buildExperimentPanel();
        buildDisplayArea();
        buildConsolePanel();

        setContentView(root);

        String assetPath = getIntent().getStringExtra("asset_path");
        String jarPath = getIntent().getStringExtra("jar_path");
        String filePath = getIntent().getStringExtra("file_path");
        boolean fromLibrary = getIntent().getBooleanExtra("from_library", false);

        if (filePath != null) {
            compileModelFromFilePath(filePath);
        } else if (assetPath != null) {
            compileModelFromAsset(assetPath);
        } else if (fromLibrary && jarPath != null) {
            compileModelFromLibrary(jarPath);
        } else if (jarPath != null) {
            compileModelFromLibrary(jarPath);
        }
    }

    private void buildTopBar() {
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setPadding(dp(12), dp(6), dp(12), dp(6));
        topBar.setBackgroundColor(0xFF2D2D2D);
        topBar.setGravity(Gravity.CENTER_VERTICAL);

        Button backBtn = new Button(this);
        backBtn.setText("\u25C0");
        backBtn.setTextColor(Color.WHITE);
        backBtn.setTextSize(14);
        backBtn.setBackgroundColor(0xFF444444);
        backBtn.setPadding(dp(12), dp(2), dp(12), dp(2));
        backBtn.setOnClickListener(v -> finish());
        topBar.addView(backBtn);

        TextView title = new TextView(this);
        title.setText(modelName != null ? modelName : "GAMA");
        title.setTextColor(0xFFCCCCCC);
        title.setTextSize(14);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(dp(8), 0, 0, 0);
        title.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        topBar.addView(title);

        statusText = new TextView(this);
        statusText.setText("Idle");
        statusText.setTextColor(0xFF888888);
        statusText.setTextSize(12);
        statusText.setPadding(dp(8), dp(4), dp(8), dp(4));
        statusText.setBackgroundColor(0xFF3C3C3C);
        topBar.addView(statusText);

        root.addView(topBar, lpFull());
    }

    private void buildExperimentPanel() {
        experimentPanel = new LinearLayout(this);
        experimentPanel.setOrientation(LinearLayout.VERTICAL);
        experimentPanel.setPadding(dp(16), dp(12), dp(16), dp(12));
        experimentPanel.setBackgroundColor(0xFF252526);
        experimentPanel.setVisibility(View.GONE);
        root.addView(experimentPanel, lpFull());
    }

    private void buildDisplayArea() {
        LinearLayout displayWrapper = new LinearLayout(this);
        displayWrapper.setOrientation(LinearLayout.VERTICAL);
        displayWrapper.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        buildSimControlBar(displayWrapper);
        buildDisplayToolbar(displayWrapper);

        displayContainer = new FrameLayout(this);
        displayContainer.setBackgroundColor(0xFF808080);
        displayContainer.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        displayWrapper.addView(displayContainer, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        root.addView(displayWrapper, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
    }

    private void buildSimControlBar(LinearLayout parent) {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(dp(8), dp(4), dp(8), dp(4));
        bar.setBackgroundColor(0xFF333333);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        cycleText = new TextView(this);
        cycleText.setText("Simulation 0: 0 cycle [00:00:00]");
        cycleText.setTextColor(0xFFCCCCCC);
        cycleText.setTextSize(10);
        cycleText.setTypeface(Typeface.MONOSPACE);
        cycleText.setSingleLine(true);
        cycleText.setHorizontallyScrolling(true);
        HorizontalScrollView cycleScroll = new HorizontalScrollView(this);
        cycleScroll.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        cycleScroll.setHorizontalScrollBarEnabled(false);
        cycleScroll.addView(cycleText);
        bar.addView(cycleScroll);

        playPauseBtn = makeControlBtn("\u25B6", 0xFF4CAF50);
        playPauseBtn.setOnClickListener(v -> togglePlayPause());
        bar.addView(playPauseBtn);

        stepBtn = makeControlBtn("\u23E9", 0xFF888888);
        stepBtn.setOnClickListener(v -> stepSimulation());
        bar.addView(stepBtn);

        stopBtn = makeControlBtn("\u23F9", 0xFF888888);
        stopBtn.setOnClickListener(v -> stopSimulation());
        bar.addView(stopBtn);

        TextView speedLabel = new TextView(this);
        speedLabel.setText(" Speed ");
        speedLabel.setTextColor(0xFF888888);
        speedLabel.setTextSize(9);
        bar.addView(speedLabel);

        SeekBar speedSlider = new SeekBar(this);
        speedSlider.setLayoutParams(new LinearLayout.LayoutParams(dp(60), LinearLayout.LayoutParams.WRAP_CONTENT));
        speedSlider.setMax(100);
        speedSlider.setProgress(50);
        bar.addView(speedSlider);

        parent.addView(bar, lpFull());
    }

    private void buildDisplayToolbar(LinearLayout parent) {
        displayToolbar = new LinearLayout(this);
        displayToolbar.setOrientation(LinearLayout.HORIZONTAL);
        displayToolbar.setPadding(dp(4), dp(2), dp(4), dp(2));
        displayToolbar.setBackgroundColor(0xFF2D2D2D);
        displayToolbar.setGravity(Gravity.CENTER);
        displayToolbar.setVisibility(View.GONE);

        String[] icons = {"\u23F8", "+", "\u2195", "\u2212", "\u2318", "\u25CE", "\u26F6", "\u2637"};
        for (String icon : icons) {
            TextView btn = new TextView(this);
            btn.setText(icon);
            btn.setTextColor(0xFFAAAAAA);
            btn.setTextSize(16);
            btn.setPadding(dp(12), dp(4), dp(12), dp(4));
            btn.setGravity(Gravity.CENTER);
            btn.setOnClickListener(v -> handleDisplayAction(icon));
            displayToolbar.addView(btn);
        }

        parent.addView(displayToolbar, lpFull());
    }

    private void buildConsolePanel() {
        consolePanel = new LinearLayout(this);
        consolePanel.setOrientation(LinearLayout.VERTICAL);
        consolePanel.setBackgroundColor(0xFF1E1E1E);

        LinearLayout consoleHeader = new LinearLayout(this);
        consoleHeader.setOrientation(LinearLayout.HORIZONTAL);
        consoleHeader.setPadding(dp(12), dp(4), dp(12), dp(4));
        consoleHeader.setBackgroundColor(0xFF252526);

        TextView consoleTab = new TextView(this);
        consoleTab.setText("  Console  ");
        consoleTab.setTextColor(0xFFCCCCCC);
        consoleTab.setTextSize(11);
        consoleTab.setBackgroundColor(0xFF1E1E1E);
        consoleTab.setPadding(dp(8), dp(4), dp(8), dp(4));
        consoleHeader.addView(consoleTab);

        TextView interactiveTab = new TextView(this);
        interactiveTab.setText("  Interactive console  ");
        interactiveTab.setTextColor(0xFF666666);
        interactiveTab.setTextSize(11);
        interactiveTab.setPadding(dp(8), dp(4), dp(8), dp(4));
        consoleHeader.addView(interactiveTab);

        consolePanel.addView(consoleHeader, lpFull());

        ScrollView logScroll = new ScrollView(this);
        logScroll.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(120)));

        logView = new TextView(this);
        logView.setTextSize(10);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextColor(0xFF00FF00);
        logView.setPadding(dp(8), dp(4), dp(8), dp(4));
        logView.setBackgroundColor(0xFF0D0D0D);
        logView.setMovementMethod(new ScrollingMovementMethod());
        logScroll.addView(logView);

        consolePanel.addView(logScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(120)));

        root.addView(consolePanel, lpFull());
    }

    private Button makeControlBtn(String text, int color) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setTextColor(color);
        btn.setTextSize(14);
        btn.setBackgroundColor(Color.TRANSPARENT);
        btn.setPadding(dp(8), 0, dp(8), 0);
        btn.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return btn;
    }

    private void togglePlayPause() {
        if (!isRunning || currentController == null) return;
        isPaused = !isPaused;
        handler.post(() -> {
            playPauseBtn.setText(isPaused ? "\u25B6" : "\u23F8");
            statusText.setText(isPaused ? "Paused" : "Running");
        });
        try {
            Class<?> absControllerClass = Class.forName("gama.core.kernel.experiment.DefaultExperimentController").getSuperclass();
            java.lang.reflect.Field pausedField = absControllerClass.getDeclaredField("paused");
            pausedField.setAccessible(true);
            pausedField.setBoolean(currentController, isPaused);

            java.lang.reflect.Field lockField = absControllerClass.getDeclaredField("lock");
            lockField.setAccessible(true);
            Object lock = lockField.get(currentController);
            if (!isPaused) {
                lock.getClass().getMethod("release").invoke(lock);
            }
            log("Play/pause toggled: paused=" + isPaused);
        } catch (Exception e) {
            Log.w(TAG, "Could not toggle pause", e);
            log("Pause toggle error: " + e.getMessage());
        }
    }

    private void stepSimulation() {
        if (!isRunning || !isPaused || currentController == null) return;
        try {
            Class<?> absControllerClass = Class.forName("gama.core.kernel.experiment.DefaultExperimentController").getSuperclass();
            java.lang.reflect.Field pausedField = absControllerClass.getDeclaredField("paused");
            pausedField.setAccessible(true);

            java.lang.reflect.Field lockField = absControllerClass.getDeclaredField("lock");
            lockField.setAccessible(true);
            Object lock = lockField.get(currentController);

            pausedField.setBoolean(currentController, false);
            lock.getClass().getMethod("release").invoke(lock);
            log("Manual step executed");
        } catch (Exception e) {
            Log.w(TAG, "Could not step", e);
            log("Step error: " + e.getMessage());
        }
    }

    private void stopSimulation() {
        if (!isRunning) return;
        isRunning = false;
        isPaused = false;
        if (statePollRunnable != null) {
            handler.removeCallbacks(statePollRunnable);
        }
        try {
            if (currentController != null) {
                Class<?> ctrlInterface = Class.forName("gama.core.kernel.experiment.IExperimentController");
                ctrlInterface.getMethod("close").invoke(currentController);
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not stop", e);
        }
        handler.post(() -> {
            statusText.setText("Stopped");
            playPauseBtn.setText("\u25B6");
            cycleText.setText("Simulation stopped");
        });
    }

    private void handleDisplayAction(String icon) {
        if (displayContainer.getChildCount() == 0) return;
        View child = displayContainer.getChildAt(0);
        try {
            switch (icon) {
                case "+":
                    child.getClass().getMethod("zoomIn").invoke(child);
                    break;
                case "\u2212":
                    child.getClass().getMethod("zoomOut").invoke(child);
                    break;
                case "\u2195":
                    child.getClass().getMethod("zoomFit").invoke(child);
                    break;
                case "\u2318":
                    child.getClass().getMethod("toggleLock").invoke(child);
                    break;
            }
        } catch (Exception e) {
            Log.w(TAG, "Display action failed", e);
        }
    }

    private void compileModelFromAsset(String assetPath) {
        log("Compiling model: " + assetPath);
        new Thread(() -> {
            try {
                File cacheDir = getCacheDir();
                String safeName = assetPath.replaceAll("[^a-zA-Z0-9]", "_");
                File modelFile = new File(cacheDir, safeName + ".gaml");

                try (InputStream is = getAssets().open(assetPath);
                     FileOutputStream fos = new FileOutputStream(modelFile)) {
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = is.read(buf)) > 0) fos.write(buf, 0, n);
                }
                log("Model file copied to: " + modelFile.getAbsolutePath());

                Class<?> builderClass = Class.forName("gaml.compiler.gaml.validation.GamlModelBuilder");
                Object builder = builderClass.getMethod("getDefaultInstance").invoke(null);

                Class<?> uriClass = Class.forName("org.eclipse.emf.common.util.URI");
                Object uri = uriClass.getMethod("createFileURI", String.class)
                        .invoke(null, modelFile.getAbsolutePath());

                List<Object> errors = new ArrayList<>();
                Class<?> errorClass = Class.forName("gama.gaml.compilation.GamlCompilationError");
                @SuppressWarnings("unchecked")
                List<Object> errorList = (List<Object>) errors;

                Class<?> modelClass = Class.forName("gama.core.kernel.model.IModel");
                Object model = builderClass.getMethod("compile", uriClass, List.class)
                        .invoke(builder, uri, errorList);

                if (model == null) {
                    log("Compilation failed with " + errorList.size() + " errors");
                    for (Object err : errorList) {
                        log("  ERROR: " + err);
                    }
                    return;
                }

                compiledModel = model;
                String name = (String) modelClass.getMethod("getName").invoke(model);
                log("Model compiled: " + name);

                java.lang.reflect.Method getExps = modelClass.getMethod("getExperiments");
                Iterable<?> experiments = (Iterable<?>) getExps.invoke(model);

                List<Object> expList = new ArrayList<>();
                for (Object exp : experiments) {
                    expList.add(exp);
                }

                log("Found " + expList.size() + " experiment(s)");
                for (Object exp : expList) {
                    String expName = (String) exp.getClass().getMethod("getName").invoke(exp);
                    log("  - " + expName);
                }

                handler.post(() -> showExperiments(expList));

            } catch (Exception e) {
                Log.e(TAG, "Compilation error", e);
                log("ERROR: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                Throwable cause = e.getCause();
                while (cause != null) {
                    log("  CAUSE: " + cause.getMessage());
                    cause = cause.getCause();
                }
            }
        }).start();
    }

    private void compileModelFromFilePath(String filePath) {
        log("Compiling model from file: " + filePath);
        new Thread(() -> {
            try {
                File modelFile = new File(filePath);
                if (!modelFile.exists()) {
                    log("ERROR: File not found: " + filePath);
                    return;
                }

                Class<?> builderClass = Class.forName("gaml.compiler.gaml.validation.GamlModelBuilder");
                Object builder = builderClass.getMethod("getDefaultInstance").invoke(null);

                Class<?> uriClass = Class.forName("org.eclipse.emf.common.util.URI");
                Object uri = uriClass.getMethod("createFileURI", String.class)
                        .invoke(null, modelFile.getAbsolutePath());

                List<Object> errors = new ArrayList<>();
                @SuppressWarnings("unchecked")
                List<Object> errorList = (List<Object>) errors;

                Class<?> modelClass = Class.forName("gama.core.kernel.model.IModel");
                Object model = builderClass.getMethod("compile", uriClass, List.class)
                        .invoke(builder, uri, errorList);

                if (model == null) {
                    log("Compilation failed with " + errorList.size() + " errors");
                    for (Object err : errorList) {
                        log("  ERROR: " + err);
                    }
                    return;
                }

                compiledModel = model;
                String name = (String) modelClass.getMethod("getName").invoke(model);
                log("Model compiled: " + name);

                java.lang.reflect.Method getExps = modelClass.getMethod("getExperiments");
                Iterable<?> experiments = (Iterable<?>) getExps.invoke(model);

                List<Object> expList = new ArrayList<>();
                for (Object exp : experiments) {
                    expList.add(exp);
                }

                log("Found " + expList.size() + " experiment(s)");
                for (Object exp : expList) {
                    String expName = (String) exp.getClass().getMethod("getName").invoke(exp);
                    log("  - " + expName);
                }

                handler.post(() -> showExperiments(expList));

            } catch (Exception e) {
                Log.e(TAG, "File compilation error", e);
                log("ERROR: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                Throwable cause = e.getCause();
                while (cause != null) {
                    log("  CAUSE: " + cause.getMessage());
                    cause = cause.getCause();
                }
            }
        }).start();
    }

    private void compileModelFromLibrary(String jarEntryPath) {
        log("Compiling model from library: " + jarEntryPath);
        new Thread(() -> {
            try {
                File cacheDir = getCacheDir();

                java.io.File cacheJar = new java.io.File(cacheDir, "gama.library.jar");
                if (!cacheJar.exists()) {
                    try (InputStream is = getAssets().open("gama.library.jar");
                         FileOutputStream fos = new FileOutputStream(cacheJar)) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = is.read(buf)) > 0) fos.write(buf, 0, n);
                    }
                }

                java.util.jar.JarFile jarFile = new java.util.jar.JarFile(cacheJar);
                JarEntry entry = jarFile.getJarEntry(jarEntryPath);
                if (entry == null) {
                    log("ERROR: Entry not found in JAR: " + jarEntryPath);
                    jarFile.close();
                    return;
                }

                // Extract model with directory structure so relative paths (e.g. ../includes/) work
                File modelFile = new File(cacheDir, jarEntryPath);
                modelFile.getParentFile().mkdirs();
                try (InputStream is = jarFile.getInputStream(entry);
                     FileOutputStream fos = new FileOutputStream(modelFile)) {
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = is.read(buf)) > 0) fos.write(buf, 0, n);
                }

                // Also extract 'includes' directories for shapefiles etc.
                // The model may reference ../includes/ so look in parent dirs too
                String modelParentPath = jarEntryPath.substring(0, jarEntryPath.lastIndexOf('/') + 1);
                // Also compute grandparent path for models nested in a subdirectory (e.g. models/Toy Models/Traffic/models/)
                String grandParentPath = modelParentPath.contains("/")
                    ? modelParentPath.substring(0, modelParentPath.lastIndexOf('/', modelParentPath.length() - 2) + 1)
                    : "";
                int extractedIncludes = 0;
                java.util.Enumeration<? extends JarEntry> entries = jarFile.entries();
                java.util.Set<String> extractedPaths = new java.util.HashSet<>();
                while (entries.hasMoreElements()) {
                    JarEntry e = entries.nextElement();
                    String eName = e.getName();
                    if (e.isDirectory() || extractedPaths.contains(eName)) continue;
                    // Check if this file is in an 'includes' directory that is a sibling or parent-sibling of the model
                    if (eName.endsWith("/")) continue;
                    int includesIdx = eName.lastIndexOf("/includes/");
                    if (includesIdx < 0) continue;
                    String includesDirPrefix = eName.substring(0, includesIdx + "/includes/".length());
                    // Check if this includes dir is related to our model
                    if (!includesDirPrefix.startsWith(modelParentPath) && !includesDirPrefix.startsWith(grandParentPath)) continue;
                    if (!includesDirPrefix.endsWith("includes/")) continue;

                    // Compute output path: place includes relative to the model file
                    // The model at cacheDir/<jarEntryPath> references ../includes/
                    // So we need includes at cacheDir/<modelParentPath>/../includes/ = cacheDir/<grandParentPath>/includes/
                    String relativePath = eName.substring(includesDirPrefix.length());
                    File outFile = new File(modelFile.getParentFile(), "../includes/" + relativePath);
                    // Normalize to remove .. 
                    outFile = outFile.getCanonicalFile();
                    outFile.getParentFile().mkdirs();
                    if (!extractedPaths.contains(outFile.getAbsolutePath())) {
                        try (InputStream is = jarFile.getInputStream(e);
                             FileOutputStream fos = new FileOutputStream(outFile)) {
                            byte[] buf = new byte[4096];
                            int n;
                            while ((n = is.read(buf)) > 0) fos.write(buf, 0, n);
                        }
                        extractedPaths.add(outFile.getAbsolutePath());
                        extractedIncludes++;
                    }
                }
                if (extractedIncludes > 0) {
                    log("Extracted " + extractedIncludes + " includes files from JAR");
                } else {
                    log("WARNING: No includes files found for model");
                }
                jarFile.close();
                log("Model extracted to: " + modelFile.getAbsolutePath());

                Class<?> builderClass = Class.forName("gaml.compiler.gaml.validation.GamlModelBuilder");
                Object builder = builderClass.getMethod("getDefaultInstance").invoke(null);

                Class<?> uriClass = Class.forName("org.eclipse.emf.common.util.URI");
                Object uri = uriClass.getMethod("createFileURI", String.class)
                        .invoke(null, modelFile.getAbsolutePath());

                List<Object> errors = new ArrayList<>();
                Class<?> errorClass = Class.forName("gama.gaml.compilation.GamlCompilationError");
                @SuppressWarnings("unchecked")
                List<Object> errorList = (List<Object>) errors;

                Class<?> modelClass = Class.forName("gama.core.kernel.model.IModel");
                Object model = builderClass.getMethod("compile", uriClass, List.class)
                        .invoke(builder, uri, errorList);

                if (model == null) {
                    log("Compilation failed with " + errorList.size() + " errors");
                    for (Object err : errorList) {
                        log("  ERROR: " + err);
                    }
                    return;
                }

                compiledModel = model;
                String name = (String) modelClass.getMethod("getName").invoke(model);
                log("Model compiled: " + name);

                java.lang.reflect.Method getExps = modelClass.getMethod("getExperiments");
                Iterable<?> experiments = (Iterable<?>) getExps.invoke(model);

                List<Object> expList = new ArrayList<>();
                for (Object exp : experiments) {
                    expList.add(exp);
                }

                log("Found " + expList.size() + " experiment(s)");
                for (Object exp : expList) {
                    String expName = (String) exp.getClass().getMethod("getName").invoke(exp);
                    log("  - " + expName);
                }

                handler.post(() -> showExperiments(expList));

            } catch (Exception e) {
                Log.e(TAG, "Library compilation error", e);
                log("ERROR: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                Throwable cause = e.getCause();
                while (cause != null) {
                    log("  CAUSE: " + cause.getMessage());
                    cause = cause.getCause();
                }
            }
        }).start();
    }

    private void showExperiments(List<Object> experiments) {
        experimentPanel.removeAllViews();
        experimentPanel.setVisibility(View.VISIBLE);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setPadding(0, dp(4), 0, dp(8));

        TextView label = new TextView(this);
        label.setText("Select Experiment:");
        label.setTextColor(0xFFCCCCCC);
        label.setTextSize(13);
        header.addView(label);
        experimentPanel.addView(header);

        LinearLayout buttonRow = new LinearLayout(this);
        buttonRow.setOrientation(LinearLayout.HORIZONTAL);
        buttonRow.setGravity(Gravity.CENTER_VERTICAL);

        for (Object exp : experiments) {
            try {
                String expName = (String) exp.getClass().getMethod("getName").invoke(exp);

                Button btn = new Button(this);
                btn.setText("\u25B6 " + expName.toLowerCase());
                btn.setTextColor(Color.WHITE);
                btn.setTextSize(13);
                btn.setTypeface(Typeface.DEFAULT_BOLD);
                btn.setBackgroundColor(0xFF4CAF50);
                btn.setPadding(dp(16), dp(8), dp(16), dp(8));
                LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                btnParams.setMargins(dp(4), 0, dp(4), 0);
                btn.setLayoutParams(btnParams);
                btn.setOnClickListener(v -> {
                    experimentPanel.setVisibility(View.GONE);
                    runExperiment(exp, expName);
                });
                buttonRow.addView(btn);
            } catch (Exception e) {
                log("Error getting experiment name: " + e.getMessage());
            }
        }

        experimentPanel.addView(buttonRow);
    }

    private void runExperiment(Object expPlan, String expName) {
        log("Starting experiment: " + expName);
        currentExpPlan = expPlan;
        isRunning = true;
        isPaused = false;

        handler.post(() -> {
            statusText.setText("Running");
            playPauseBtn.setText("\u23F8");
            displayToolbar.setVisibility(View.VISIBLE);
            cycleText.setText("Simulation 0: 0 cycle elapsed [00:00:00]");
        });

        new Thread(() -> {
            try {
                log("[1] Setting up GUI handlers...");
                Class<?> guiHandlerClass = Class.forName("com.gama.nativeapp.gui.AndroidGuiHandler");
                Object guiHandler = guiHandlerClass.getMethod("getInstance").invoke(null);

                Class<?> gamaClass = Class.forName("gama.core.runtime.GAMA");
                gamaClass.getMethod("setHeadlessGui", Class.forName("gama.core.common.interfaces.IGui")).invoke(null, guiHandler);
                gamaClass.getMethod("setRegularGui", Class.forName("gama.core.common.interfaces.IGui")).invoke(null, guiHandler);
                log("[1] GUI handlers set");

                log("[2] Opening experiment plan...");
                Class<?> expClass = Class.forName("gama.core.kernel.experiment.IExperimentPlan");
                expClass.getMethod("setHeadless", boolean.class).invoke(expPlan, false);
                expClass.getMethod("open").invoke(expPlan);
                log("[2] Experiment plan opened");

                log("[3] Getting controller...");
                Object controller = expClass.getMethod("getController").invoke(expPlan);
                currentController = controller;

                java.lang.reflect.Field controllersField = gamaClass.getDeclaredField("controllers");
                controllersField.setAccessible(true);
                java.util.List controllers = (java.util.List) controllersField.get(null);
                controllers.add(controller);
                log("[3] Controller registered (size=" + controllers.size() + ")");

                log("[4] Starting experiment controller via processStart...");
                Class<?> ctrlInterface = Class.forName("gama.core.kernel.experiment.IExperimentController");
                ctrlInterface.getMethod("processStart", boolean.class).invoke(controller, true);
                log("[4] processStart(true) completed");

                // Manually unpause the execution thread since acceptingCommands starts false
                Class<?> absControllerClass = Class.forName("gama.core.kernel.experiment.DefaultExperimentController").getSuperclass();
                java.lang.reflect.Field pausedField = absControllerClass.getDeclaredField("paused");
                pausedField.setAccessible(true);
                pausedField.setBoolean(controller, false);

                java.lang.reflect.Field lockField = absControllerClass.getDeclaredField("lock");
                lockField.setAccessible(true);
                Object lock = lockField.get(controller);
                lock.getClass().getMethod("release").invoke(lock);
                log("[4] paused=false, lock released - execution thread running");

                log("Experiment started successfully");
                handler.post(() -> statusText.setText("Running"));

                // Diagnostic: check display container state after a delay
                handler.postDelayed(() -> {
                    try {
                        int childCount = displayContainer.getChildCount();
                        log("[DIAG] displayContainer childCount=" + childCount);

                        // Check GAMA GUI references
                        Class<?> gamaClassDiag = Class.forName("gama.core.runtime.GAMA");
                        Object regularGui = gamaClassDiag.getMethod("getRegularGui").invoke(null);
                        Object headlessGui = gamaClassDiag.getMethod("getHeadlessGui").invoke(null);
                        Object staticGui = gamaClassDiag.getMethod("getGui").invoke(null);
                        log("[DIAG] regularGui=" + (regularGui == null ? "null" : regularGui.getClass().getSimpleName()));
                        log("[DIAG] headlessGui=" + (headlessGui == null ? "null" : headlessGui.getClass().getSimpleName()));
                        log("[DIAG] getGui()=" + (staticGui == null ? "null" : staticGui.getClass().getSimpleName()));

                        // Check isInHeadlessMode
                        java.lang.reflect.Field headlessModeField = gamaClassDiag.getDeclaredField("isInHeadlessMode");
                        headlessModeField.setAccessible(true);
                        boolean inHeadless = headlessModeField.getBoolean(null);
                        log("[DIAG] isInHeadlessMode=" + inHeadless);

                        // Check experiment headless state
                        java.lang.reflect.Method isHeadlessMethod = expPlan.getClass().getMethod("isHeadless");
                        boolean expHeadless = (boolean) isHeadlessMethod.invoke(expPlan);
                        log("[DIAG] expPlan.isHeadless()=" + expHeadless);

                        // Try to find simulation agent and its outputs
                        java.lang.reflect.Method getAgentMethod = expPlan.getClass().getMethod("getAgent");
                        Object expAgent = getAgentMethod.invoke(expPlan);
                        if (expAgent != null) {
                            log("[DIAG] expAgent=" + expAgent.getClass().getSimpleName());
                            java.lang.reflect.Method getOutputMgr = expAgent.getClass().getMethod("getOutputManager");
                            Object outMgr = getOutputMgr.invoke(expAgent);
                            log("[DIAG] expOutputManager=" + (outMgr == null ? "null" : outMgr.getClass().getSimpleName()));

                            // Check if there are simulations
                            try {
                                java.lang.reflect.Method getPopMethod = expAgent.getClass().getMethod("getPopulation", int.class);
                                Object pop = getPopMethod.invoke(expAgent, 0);
                                if (pop != null) {
                                    java.lang.reflect.Method getAgentCount = pop.getClass().getMethod("getAgentCount");
                                    int agentCount = (int) getAgentCount.invoke(pop);
                                    log("[DIAG] simulationPopulation agentCount=" + agentCount);

                                    if (agentCount > 0) {
                                        java.lang.reflect.Method getAgentAt = pop.getClass().getMethod("getAgent", int.class);
                                        Object simAgent = getAgentAt.invoke(pop, 0);
                                        if (simAgent != null) {
                                            log("[DIAG] simAgent=" + simAgent.getClass().getSimpleName());
                                            java.lang.reflect.Method simOutMgr = simAgent.getClass().getMethod("getOutputManager");
                                            Object simOutputMgr = simOutMgr.invoke(simAgent);
                                            log("[DIAG] simOutputManager=" + (simOutputMgr == null ? "null" : simOutputMgr.getClass().getSimpleName()));

                                            // Try to get outputs from the simulation output manager
                                            if (simOutputMgr != null) {
                                                try {
                                                    java.lang.reflect.Field outputsField = simOutputMgr.getClass().getSuperclass().getDeclaredField("outputs");
                                                    outputsField.setAccessible(true);
                                                    java.util.List outputs = (java.util.List) outputsField.get(simOutputMgr);
                                                    log("[DIAG] simOutputs list size=" + (outputs == null ? "null" : outputs.size()));
                                                    if (outputs != null) {
                                                        for (Object out : outputs) {
                                                            log("[DIAG]   output: " + out.getClass().getSimpleName() + " name=" + out.getClass().getMethod("getName").invoke(out));
                                                        }
                                                    }
                                                } catch (Exception oe) {
                                                    log("[DIAG] simOutputs error: " + oe.getMessage());
                                                }
                                            }
                                        }
                                    }
                                }
                            } catch (Exception pe) {
                                log("[DIAG] popError: " + pe.getMessage());
                            }
                        }
                    } catch (Exception diagE) {
                        log("[DIAG] error: " + diagE.getMessage());
                    }
                }, 5000);

                startStatePolling(controller);

            } catch (Exception e) {
                Log.e(TAG, "Experiment run error", e);
                log("ERROR: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                Throwable cause = e.getCause();
                while (cause != null) {
                    log("  CAUSE: " + cause.getClass().getSimpleName() + ": " + cause.getMessage());
                    cause = cause.getCause();
                }
                handler.post(() -> {
                    isRunning = false;
                    statusText.setText("Error");
                });
            }
        }).start();
    }

    private void startStatePolling(Object controller) {
        final Class<?> controllerClass;
        final Class<?> absControllerClass;
        try {
            controllerClass = Class.forName("gama.core.kernel.experiment.DefaultExperimentController");
            absControllerClass = controllerClass.getSuperclass();
        } catch (ClassNotFoundException e) {
            log("Cannot find controller class");
            return;
        }

        final long startTime = System.currentTimeMillis();
        final int[] pollCount = {0};
        statePollRunnable = () -> {
            if (!isRunning) return;
            pollCount[0]++;

            try {
                long elapsed = System.currentTimeMillis() - startTime;
                long seconds = elapsed / 1000;
                long min = seconds / 60;
                long sec = seconds % 60;

                java.lang.reflect.Field pausedField = absControllerClass.getDeclaredField("paused");
                pausedField.setAccessible(true);
                boolean paused = pausedField.getBoolean(controller);

                java.lang.reflect.Field aliveField = absControllerClass.getDeclaredField("experimentAlive");
                aliveField.setAccessible(true);
                boolean alive = aliveField.getBoolean(controller);

                java.lang.reflect.Field execField = controllerClass.getDeclaredField("executionThread");
                execField.setAccessible(true);
                Thread execThread = (Thread) execField.get(controller);
                String execState = execThread.getState().name();
                boolean execAlive = execThread.isAlive();

                // Get cycle count via controller.scope.getClock().getCycle()
                final int[] cycleCount = {-1};
                try {
                    java.lang.reflect.Field scopeField = absControllerClass.getDeclaredField("scope");
                    scopeField.setAccessible(true);
                    Object scope = scopeField.get(controller);
                    if (scope != null) {
                        Object clock = scope.getClass().getMethod("getClock").invoke(scope);
                        if (clock != null) {
                            cycleCount[0] = (int) clock.getClass().getMethod("getCycle").invoke(clock);
                        }
                    }
                } catch (Exception e) {
                    // clock not available yet
                }

                StringBuilder sb = new StringBuilder();
                sb.append("[Poll #").append(pollCount[0]).append("] ");
                sb.append("paused=").append(paused).append(" ");
                sb.append("alive=").append(alive).append(" ");
                sb.append("exec=").append(execState);
                sb.append(" cycle=").append(cycleCount[0]);
                if (!execAlive) sb.append(" DEAD!");

                Log.i(TAG, sb.toString());
                if (pollCount[0] <= 5 || pollCount[0] % 10 == 0) {
                    log(sb.toString());
                }

                if (pollCount[0] <= 3 || pollCount[0] == 10) {
                    StackTraceElement[] stack = execThread.getStackTrace();
                    StringBuilder stackStr = new StringBuilder();
                    stackStr.append("EXEC THREAD STACK (").append(execState).append("):\n");
                    for (StackTraceElement frame : stack) {
                        stackStr.append("  at ").append(frame).append("\n");
                    }
                    Log.w(TAG, stackStr.toString());
                    if (pollCount[0] <= 3) log(stackStr.toString());

                    ThreadGroup tg = execThread.getThreadGroup();
                    if (tg != null) {
                        Thread[] threads = new Thread[tg.activeCount() + 10];
                        int count = tg.enumerate(threads, true);
                        for (int i = 0; i < count; i++) {
                            Thread t = threads[i];
                            if (t != execThread && t != Thread.currentThread() && t.isAlive()) {
                                String name = t.getName();
                                if (name.contains("Simulation") || name.contains("Front end") || name.contains("ForkJoin")) {
                                    StackTraceElement[] tstack = t.getStackTrace();
                                    StringBuilder tsb = new StringBuilder();
                                    tsb.append("THREAD '").append(name).append("' (").append(t.getState()).append("):\n");
                                    for (StackTraceElement frame : tstack) {
                                        tsb.append("  at ").append(frame).append("\n");
                                    }
                                    Log.w(TAG, tsb.toString());
                                    if (pollCount[0] <= 3) log(tsb.toString());
                                }
                            }
                        }
                    }
                }

                handler.post(() -> {
                    String cycleStr = cycleCount[0] >= 0 ? String.valueOf(cycleCount[0]) : "?";
                    cycleText.setText(
                        "Simulation 0: " + cycleStr + " cycles [" +
                        String.format("%02d:%02d:%02d", 0, min, sec) + "]" +
                        (paused ? " [PAUSED]" : ""));

                    // Step and update the display output with the simulation scope
                    if (displayContainer.getChildCount() > 0) {
                        try {
                            Class<?> guiHandlerClass = Class.forName("com.gama.nativeapp.gui.AndroidGuiHandler");
                            java.lang.reflect.Field ldoField = guiHandlerClass.getDeclaredField("cachedDisplayOutput");
                            ldoField.setAccessible(true);
                            Object ldoObj = ldoField.get(null);
                            if (ldoObj != null) {
                                Class<?> iscopeClass = Class.forName("gama.core.runtime.IScope");
                                Class<?> gamaClass = Class.forName("gama.core.runtime.GAMA");
                                java.lang.reflect.Field controllersField = gamaClass.getDeclaredField("controllers");
                                controllersField.setAccessible(true);
                                java.util.List controllers = (java.util.List) controllersField.get(null);
                                if (controllers != null && !controllers.isEmpty()) {
                                    Object ctrl = controllers.get(controllers.size() - 1);
                                    java.lang.reflect.Field scopeField = ctrl.getClass().getSuperclass().getDeclaredField("scope");
                                    scopeField.setAccessible(true);
                                    Object ctrlScope = scopeField.get(ctrl);
                                    if (ctrlScope != null) {
                                        java.lang.reflect.Method copyForGraphics = ctrlScope.getClass().getMethod("copyForGraphics", String.class);
                                        Object gfxScope = copyForGraphics.invoke(ctrlScope, "display map");

                                        java.lang.reflect.Method setScope = ldoObj.getClass().getMethod("setScope", iscopeClass);
                                        setScope.invoke(ldoObj, gfxScope);

                                        java.lang.reflect.Method step = ldoObj.getClass().getMethod("step", iscopeClass);
                                        step.invoke(ldoObj, gfxScope);

                                        java.lang.reflect.Method update = ldoObj.getClass().getMethod("update");
                                        update.invoke(ldoObj);

                                        // Also get the surface and invalidate it directly
                                        java.lang.reflect.Method getSurface = ldoObj.getClass().getMethod("getSurface");
                                        Object surfObj = getSurface.invoke(ldoObj);
                                        if (surfObj != null) {
                                            android.view.View surfView = (android.view.View) surfObj;
                                            surfView.post(() -> {
                                                surfView.invalidate();
                                            });
                                        }

                                        if (pollCount[0] <= 5 || pollCount[0] % 50 == 0) {
                                            Log.i(TAG, "[DISPLAY] stepped LDO, cycle=" + cycleCount[0] + " surface=" + (surfObj == null ? "null" : "ok"));
                                        }
                                    }
                                }
                            }
                        } catch (Exception e) {
                            if (pollCount[0] <= 3) Log.e(TAG, "[DISPLAY] step error: " + e.getMessage());
                        }
                    }
                });

            } catch (Exception e) {
                Log.w(TAG, "Poll error", e);
            }

            if (isRunning && pollCount[0] < 300) {
                handler.postDelayed(statePollRunnable, 1000);
            }
        };
        handler.postDelayed(statePollRunnable, 1000);
    }

    private static void setGuiActivity(Activity activity) {
        try {
            Class<?> handlerClass = Class.forName("com.gama.nativeapp.gui.AndroidGuiHandler");
            handlerClass.getMethod("setActivity", android.app.Activity.class).invoke(null, activity);
        } catch (Throwable e) {
            Log.w(TAG, "Could not set GUI activity", e);
        }
    }

    private void log(String message) {
        Log.i(TAG, message);
        handler.post(() -> logView.append(message + "\n"));
    }

    private int dp(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }

    private LinearLayout.LayoutParams lpFull() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    public FrameLayout getDisplayContainer() {
        return displayContainer;
    }

    public void updateCycleInfo(long cycle, long elapsedMs) {
        long seconds = elapsedMs / 1000;
        long min = seconds / 60;
        long sec = seconds % 60;
        handler.post(() -> cycleText.setText(
                "Simulation 0: " + cycle + " cycle elapsed [" + String.format("%02d:%02d:%02d", 0, min, sec) + "]"));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        isRunning = false;
        if (statePollRunnable != null) {
            handler.removeCallbacks(statePollRunnable);
        }
        setGuiActivity(null);
        if (currentController != null) stopSimulation();
    }
}
