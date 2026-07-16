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
        if (assetPath != null) {
            compileModel(assetPath);
        }
    }

    private void buildTopBar() {
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setPadding(dp(12), dp(6), dp(12), dp(6));
        topBar.setBackgroundColor(0xFF2D2D2D);
        topBar.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText(modelName != null ? modelName : "GAMA");
        title.setTextColor(0xFFCCCCCC);
        title.setTextSize(14);
        title.setTypeface(Typeface.DEFAULT_BOLD);
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
        if (!isRunning) return;
        isPaused = !isPaused;
        handler.post(() -> {
            playPauseBtn.setText(isPaused ? "\u25B6" : "\u23F8");
            statusText.setText(isPaused ? "Paused" : "Running");
        });
        try {
            Class<?> controllerClass = Class.forName("gama.core.kernel.experiment.DefaultExperimentController");
            controllerClass.getMethod("setPaused", boolean.class).invoke(currentController, isPaused);
        } catch (Exception e) {
            Log.w(TAG, "Could not toggle pause", e);
        }
    }

    private void stepSimulation() {
        if (!isRunning || !isPaused) return;
        try {
            Class<?> controllerClass = Class.forName("gama.core.kernel.experiment.DefaultExperimentController");
            controllerClass.getMethod("step").invoke(currentController);
        } catch (Exception e) {
            Log.w(TAG, "Could not step", e);
        }
    }

    private void stopSimulation() {
        if (!isRunning) return;
        isRunning = false;
        isPaused = false;
        try {
            if (currentController != null) {
                Class<?> controllerClass = Class.forName("gama.core.kernel.experiment.DefaultExperimentController");
                controllerClass.getMethod("forceStop").invoke(currentController);
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

    private void compileModel(String assetPath) {
        log("Compiling model: " + assetPath);
        new Thread(() -> {
            try {
                File cacheDir = getCacheDir();
                File modelFile = new File(cacheDir, "model.gaml");

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
                Class<?> guiHandlerClass = Class.forName("com.gama.nativeapp.gui.AndroidGuiHandler");
                Object guiHandler = guiHandlerClass.getMethod("getInstance").invoke(null);

                Class<?> gamaClass = Class.forName("gama.core.runtime.GAMA");
                gamaClass.getMethod("setHeadlessGui", Class.forName("gama.core.common.interfaces.IGui")).invoke(null, guiHandler);
                gamaClass.getMethod("setRegularGui", Class.forName("gama.core.common.interfaces.IGui")).invoke(null, guiHandler);

                Class<?> expClass = Class.forName("gama.core.kernel.experiment.IExperimentPlan");
                expClass.getMethod("setHeadless", boolean.class).invoke(expPlan, false);
                expClass.getMethod("open").invoke(expPlan);

                Object controller = expClass.getMethod("getController").invoke(expPlan);
                currentController = controller;
                Class<?> controllerClass = Class.forName("gama.core.kernel.experiment.DefaultExperimentController");
                controllerClass.getMethod("processStart", boolean.class).invoke(controller, false);

                log("Experiment started successfully");
                handler.post(() -> statusText.setText("Running"));

            } catch (Exception e) {
                Log.e(TAG, "Experiment run error", e);
                log("ERROR running experiment: " + e.getMessage());
                Throwable cause = e.getCause();
                while (cause != null) {
                    log("  CAUSE: " + cause.getMessage());
                    cause = cause.getCause();
                }
                handler.post(() -> {
                    isRunning = false;
                    statusText.setText("Error");
                });
            }
        }).start();
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
        setGuiActivity(null);
        if (isRunning) stopSimulation();
    }
}
