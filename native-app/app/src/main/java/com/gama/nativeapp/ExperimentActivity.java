package com.gama.nativeapp;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public class ExperimentActivity extends Activity {

    private static final String TAG = "ExperimentActivity";
    private TextView logView;
    private LinearLayout displayContainer;
    private LinearLayout experimentPanel;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private Object compiledModel;
    private String modelName;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setGuiActivity(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setPadding(16, 8, 16, 8);
        topBar.setBackgroundColor(0xFF333333);

        TextView titleText = new TextView(this);
        modelName = getIntent().getStringExtra("model_name");
        titleText.setText(modelName != null ? modelName : "Model");
        titleText.setTextColor(Color.WHITE);
        titleText.setTextSize(16);
        titleText.setGravity(Gravity.CENTER_VERTICAL);
        titleText.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));
        topBar.addView(titleText);
        root.addView(topBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        experimentPanel = new LinearLayout(this);
        experimentPanel.setOrientation(LinearLayout.VERTICAL);
        experimentPanel.setPadding(16, 8, 16, 8);
        experimentPanel.setVisibility(View.GONE);
        root.addView(experimentPanel);

        displayContainer = new LinearLayout(this);
        displayContainer.setOrientation(LinearLayout.VERTICAL);
        displayContainer.setBackgroundColor(0xFF1A1A2E);
        displayContainer.setGravity(Gravity.CENTER);
        root.addView(displayContainer, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f));

        logView = new TextView(this);
        logView.setTextSize(11);
        logView.setTypeface(android.graphics.Typeface.MONOSPACE);
        logView.setTextColor(0xFF00FF00);
        logView.setPadding(16, 8, 16, 8);
        logView.setBackgroundColor(0xFF0D0D0D);

        ScrollView logScroll = new ScrollView(this);
        logScroll.addView(logView);
        LinearLayout.LayoutParams logParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 200);
        root.addView(logScroll, logParams);

        setContentView(root);

        String assetPath = getIntent().getStringExtra("asset_path");
        if (assetPath != null) {
            compileModel(assetPath);
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

        TextView label = new TextView(this);
        label.setText("Select Experiment:");
        label.setTextSize(14);
        label.setPadding(0, 8, 0, 8);
        experimentPanel.addView(label);

        for (Object exp : experiments) {
            try {
                String expName = (String) exp.getClass().getMethod("getName").invoke(exp);
                Button btn = new Button(this);
                btn.setText("Run: " + expName);
                btn.setOnClickListener(v -> runExperiment(exp, expName));
                experimentPanel.addView(btn);
            } catch (Exception e) {
                log("Error getting experiment name: " + e.getMessage());
            }
        }
    }

    private void runExperiment(Object expPlan, String expName) {
        log("Starting experiment: " + expName);
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
                Class<?> controllerClass = Class.forName("gama.core.kernel.experiment.DefaultExperimentController");
                controllerClass.getMethod("processStart", boolean.class).invoke(controller, false);

                log("Experiment started successfully");

            } catch (Exception e) {
                Log.e(TAG, "Experiment run error", e);
                log("ERROR running experiment: " + e.getMessage());
                Throwable cause = e.getCause();
                while (cause != null) {
                    log("  CAUSE: " + cause.getMessage());
                    cause = cause.getCause();
                }
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
        handler.post(() -> {
            logView.append(message + "\n");
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        setGuiActivity(null);
    }
}
