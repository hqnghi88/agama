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
    private volatile boolean isHeadlessMode = false;
    private Runnable diagnosticRunnable;

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
        if (diagnosticRunnable != null) {
            handler.removeCallbacks(diagnosticRunnable);
        }
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

                // Deep introspection of experiment state
                try {
                    Object agent = expClass.getMethod("getAgent").invoke(expPlan);
                    log("[2b] Experiment agent: " + (agent != null ? agent.getClass().getName() : "NULL"));

                    if (agent != null) {
                        log("[2b]   Agent dead=" + agent.getClass().getMethod("dead").invoke(agent));
                        log("[2b]   Agent scope=" + agent.getClass().getMethod("getScope").invoke(agent));

                        // Check SimulationPopulation
                        try {
                            java.lang.reflect.Method getSimPop = agent.getClass().getMethod("getSimulationPopulation");
                            Object simPop = getSimPop.invoke(agent);
                            log("[2b]   SimulationPopulation=" + (simPop != null ? simPop.getClass().getName() : "NULL"));
                            if (simPop != null) {
                                log("[2b]   SimPop size=" + simPop.getClass().getMethod("size").invoke(simPop));

                                // Get the current simulation from SimPop
                                java.lang.reflect.Method getCurrentSim = simPop.getClass().getMethod("getCurrentSimulation");
                                Object curSim = getCurrentSim.invoke(simPop);
                                log("[2b]   Current sim=" + (curSim != null ? curSim.getClass().getName() : "NULL"));
                                if (curSim != null) {
                                    log("[2b]   Sim dead=" + curSim.getClass().getMethod("dead").invoke(curSim));
                                    Object simScope = curSim.getClass().getMethod("getScope").invoke(curSim);
                                    log("[2b]   Sim scope=" + simScope);

                                    // Check sub-populations on the SimulationAgent's species
                                    Object simSpecies = curSim.getClass().getMethod("getSpecies").invoke(curSim);
                                    log("[2b]   Sim species=" + simSpecies.getClass().getMethod("getName").invoke(simSpecies));

                                    java.lang.reflect.Method getSubPops = simSpecies.getClass().getMethod("getSubPopulations");
                                    Object subPops = getSubPops.invoke(simSpecies);
                                    log("[2b]   Sim species subPops=" + subPops);
                                    if (subPops != null) {
                                        for (Object subPop : (Iterable<?>) subPops) {
                                            java.lang.reflect.Method getName = subPop.getClass().getMethod("getName");
                                            java.lang.reflect.Method getSize = subPop.getClass().getMethod("size");
                                            java.lang.reflect.Method getSpecies2 = subPop.getClass().getMethod("getSpecies");
                                            Object speciesName = getSpecies2.invoke(subPop);
                                            java.lang.reflect.Method getSName = speciesName.getClass().getMethod("getName");
                                            log("[2b]     SubPop: " + getName.invoke(subPop) + " species=" + getSName.invoke(speciesName) + " size=" + getSize.invoke(subPop));
                                        }
                                    }

                                    // Check the SimulationAgent's own population for agents
                                    java.lang.reflect.Method getSimAgentPop = curSim.getClass().getMethod("getPopulation");
                                    Object simAgentPop = getSimAgentPop.invoke(curSim);
                                    if (simAgentPop != null) {
                                        log("[2b]   SimAgent pop size=" + simAgentPop.getClass().getMethod("size").invoke(simAgentPop));
                                    }
                                }
                            }
                        } catch (Exception simPopEx) {
                            log("[2b]   Error: " + simPopEx.getClass().getSimpleName() + ": " + simPopEx.getMessage());
                            simPopEx.printStackTrace(System.err);
                        }
                    }
                } catch (Exception inspectEx) {
                    log("[2b]   Introspection error: " + inspectEx.getClass().getSimpleName() + ": " + inspectEx.getMessage());
                    inspectEx.printStackTrace(System.err);
                }

                // [2c] Try to manually re-trigger model init on the SimulationAgent
                try {
                    Object agent2c = expClass.getMethod("getAgent").invoke(expPlan);
                    if (agent2c != null) {
                        Object simPop2c = agent2c.getClass().getMethod("getSimulationPopulation").invoke(agent2c);
                        if (simPop2c != null) {
                            Object curSim2c = simPop2c.getClass().getMethod("getCurrentSimulation").invoke(simPop2c);
                            if (curSim2c != null) {
                                // Check scope.interrupted()
                                Object simScope = curSim2c.getClass().getMethod("getScope").invoke(curSim2c);
                                if (simScope != null) {
                                    java.lang.reflect.Method isInterrupted = simScope.getClass().getMethod("interrupted");
                                    boolean interrupted = (boolean) isInterrupted.invoke(simScope);
                                    log("[2c] simScope.interrupted()=" + interrupted);

                                    // Check scope agent
                                    try {
                                        java.lang.reflect.Method getAgentMethod = simScope.getClass().getMethod("getAgent");
                                        Object scopeAgent = getAgentMethod.invoke(simScope);
                                        log("[2c] scope.getAgent()=" + (scopeAgent != null ? scopeAgent.getClass().getSimpleName() + " dead=" + scopeAgent.getClass().getMethod("dead").invoke(scopeAgent) : "NULL"));
                                    } catch (Exception e) { log("[2c] scope.getAgent err: " + e.getMessage()); }

                                    // Check isInitOverriden on population
                                    Object pop2c = curSim2c.getClass().getMethod("getPopulation").invoke(curSim2c);
                                    if (pop2c != null) {
                                        boolean initOverridden = (boolean) pop2c.getClass().getMethod("isInitOverriden").invoke(pop2c);
                                        log("[2c] isInitOverriden=" + initOverridden);
                                    }

                                    // Get architecture and check _inits field
                                    Object species2c = curSim2c.getClass().getMethod("getSpecies").invoke(curSim2c);
                                    if (species2c != null) {
                                        Object arch = species2c.getClass().getMethod("getArchitecture").invoke(species2c);
                                        log("[2c] architecture=" + (arch != null ? arch.getClass().getName() : "NULL"));
                                        if (arch != null) {
                                            // Check _inits field
                                            try {
                                                java.lang.reflect.Field initsField = arch.getClass().getDeclaredField("_inits");
                                                initsField.setAccessible(true);
                                                Object inits = initsField.get(arch);
                                                log("[2c] _inits=" + (inits != null ? "size=" + ((java.util.List) inits).size() : "NULL"));
                                                if (inits != null) {
                                                    for (Object stmt : (java.util.List<?>) inits) {
                                                        log("[2c]   init stmt: " + stmt.getClass().getName());
                                                    }
                                                }
                                            } catch (Exception e) { log("[2c] _inits field err: " + e.getMessage()); }

                                            // Now try to manually call the create statement on test_agent
                                            // First, get the GamlAgent helper to get population for test_agent
                                            try {
                                                java.lang.reflect.Method getModel = simScope.getClass().getMethod("getModel");
                                                Object model = getModel.invoke(simScope);
                                                if (model != null) {
                                                    // Evaluate nb_agents in the scope
                                                    try {
                                                        java.lang.reflect.Method getVar = simScope.getClass().getMethod("getVar", String.class);
                                                        Object nbAgentsVal = getVar.invoke(simScope, "nb_agents");
                                                        log("[2c] nb_agents in scope=" + nbAgentsVal);
                                                    } catch (Exception e) {
                                                        log("[2c] getVar nb_agents err: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                                                    }

                                                    java.lang.reflect.Method getSpeciesByName = model.getClass().getMethod("getSpecies", String.class);
                                                    Object testAgentSpecies = getSpeciesByName.invoke(model, "test_agent");
                                                    log("[2c] test_agent species=" + (testAgentSpecies != null ? testAgentSpecies.getClass().getName() : "NULL"));
                                                    if (testAgentSpecies != null) {
                                                        // Get population for test_agent from the sim agent
                                                        java.lang.reflect.Method getPopFor = curSim2c.getClass().getMethod("getPopulationFor", Class.forName("gama.core.metamodel.ISpecies"));
                                                        Object testAgentPop = getPopFor.invoke(curSim2c, testAgentSpecies);
                                                        log("[2c] test_agent pop=" + (testAgentPop != null ? testAgentPop.getClass().getName() : "NULL"));
                                                        if (testAgentPop != null) {
                                                            log("[2c] test_agent pop.size=" + testAgentPop.getClass().getMethod("size").invoke(testAgentPop));
                                                        }
                                                    }
                                                }
                                            } catch (Exception e) {
                                                log("[2c] test_agent pop check err: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                                                if (e.getCause() != null) log("[2c]   cause: " + e.getCause().getMessage());
                                            }

                                            // Try calling arch.init(scope) again with scope's agent set to sim
                                            log("[2c] Calling architecture.init(scope) with sim as agent...");
                                            try {
                                                // Push sim agent onto scope first
                                                java.lang.reflect.Method pushMethod = simScope.getClass().getMethod("push", Class.forName("gama.core.metamodel.agent.IAgent"));
                                                pushMethod.invoke(simScope, curSim2c);
                                                log("[2c] Pushed sim agent onto scope");

                                                java.lang.reflect.Method archInit = arch.getClass().getMethod("init", Class.forName("gama.core.runtime.IScope"));
                                                Object result = archInit.invoke(arch, simScope);
                                                log("[2c] architecture.init() returned: " + result);

                                                // Check test_agent pop again
                                                try {
                                                    java.lang.reflect.Method getModel2 = simScope.getClass().getMethod("getModel");
                                                    Object model2 = getModel2.invoke(simScope);
                                                    java.lang.reflect.Method getSp2 = model2.getClass().getMethod("getSpecies", String.class);
                                                    Object sp2 = getSp2.invoke(model2, "test_agent");
                                                    java.lang.reflect.Method getPopFor2 = curSim2c.getClass().getMethod("getPopulationFor", Class.forName("gama.core.metamodel.ISpecies"));
                                                    Object pop2 = getPopFor2.invoke(curSim2c, sp2);
                                                    if (pop2 != null) {
                                                        log("[2c] test_agent pop.size AFTER init=" + pop2.getClass().getMethod("size").invoke(pop2));
                                                    }
                                                } catch (Exception e) { log("[2c] post-init check err: " + e.getMessage()); }
                                            } catch (Exception archEx) {
                                                log("[2c] architecture.init() EXCEPTION: " + archEx.getClass().getSimpleName() + ": " + archEx.getMessage());
                                                if (archEx.getCause() != null) {
                                                    log("[2c]   CAUSE: " + archEx.getCause().getClass().getSimpleName() + ": " + archEx.getCause().getMessage());
                                                    if (archEx.getCause().getCause() != null) {
                                                        log("[2c]   CAUSE2: " + archEx.getCause().getCause().getClass().getSimpleName() + ": " + archEx.getCause().getCause().getMessage());
                                                    }
                                                }
                                                Log.w(TAG, "arch.init failed", archEx);
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } catch (Exception ex2c) {
                    log("[2c] Error: " + ex2c.getClass().getSimpleName() + ": " + ex2c.getMessage());
                    Log.w(TAG, "[2c] introspection failed", ex2c);
                }

                log("[3] Getting controller...");
                Object controller = expClass.getMethod("getController").invoke(expPlan);
                currentController = controller;

                java.lang.reflect.Field controllersField = gamaClass.getDeclaredField("controllers");
                controllersField.setAccessible(true);
                java.util.List controllers = (java.util.List) controllersField.get(null);
                controllers.add(controller);
                log("[3] Controller registered (size=" + controllers.size() + ")");

                log("[4] Starting experiment controller...");
                Class<?> controllerClass = Class.forName("gama.core.kernel.experiment.DefaultExperimentController");
                controllerClass.getMethod("processStart", boolean.class).invoke(controller, false);
                log("[4] processStart returned");

                // Check state after processStart
                try {
                    Thread.sleep(3000);
                    Class<?> expPlanClass2 = Class.forName("gama.core.kernel.experiment.ExperimentPlan");
                    java.lang.reflect.Method getCurrentSim2 = expPlanClass2.getMethod("getCurrentSimulation");
                    Object curSim2 = getCurrentSim2.invoke(expPlan);
                    log("[4b] getCurrentSimulation() after start=" + (curSim2 != null ? curSim2.getClass().getName() + " dead=" + curSim2.getClass().getMethod("dead").invoke(curSim2) : "NULL"));

                    if (curSim2 != null) {
                        java.lang.reflect.Method simPopMethod = curSim2.getClass().getMethod("getPopulation");
                        Object simPop = simPopMethod.invoke(curSim2);
                        log("[4b]   Sim population=" + (simPop != null ? simPop.getClass().getName() : "NULL"));
                        if (simPop != null) {
                            java.lang.reflect.Method sizeMethod = simPop.getClass().getMethod("size");
                            log("[4b]   Sim pop size=" + sizeMethod.invoke(simPop));
                        }
                    }

                    // Also check ExperimentAgent.getSimulationPopulation()
                    Object agent2 = expClass.getMethod("getAgent").invoke(expPlan);
                    if (agent2 != null) {
                        java.lang.reflect.Method getSimPop2 = agent2.getClass().getMethod("getSimulationPopulation");
                        Object simPop2 = getSimPop2.invoke(agent2);
                        log("[4b] getSimulationPopulation() after start=" + (simPop2 != null ? simPop2.getClass().getName() : "NULL"));
                        if (simPop2 != null) {
                            java.lang.reflect.Method sizeMethod2 = simPop2.getClass().getMethod("size");
                            log("[4b]   SimulationPopulation size=" + sizeMethod2.invoke(simPop2));
                        }
                    }
                } catch (Exception postEx) {
                    log("[4b] Post-start check error: " + postEx.getClass().getSimpleName() + ": " + postEx.getMessage());
                    postEx.printStackTrace(System.err);
                }

                log("Experiment started. Starting diagnostics...");
                handler.post(() -> statusText.setText("Running"));

                startDiagnostics(controller);

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

    private void startDiagnostics(Object controller) {
        final Class<?> controllerClass;
        try {
            controllerClass = Class.forName("gama.core.kernel.experiment.DefaultExperimentController");
        } catch (ClassNotFoundException e) {
            log("DIAG: Cannot find controller class");
            return;
        }

        final int[] pollCount = {0};
        diagnosticRunnable = () -> {
            if (!isRunning) return;
            pollCount[0]++;
            StringBuilder sb = new StringBuilder();
            sb.append("[DIAG #").append(pollCount[0]).append("] ");

            try {
                // Check execution thread
                java.lang.reflect.Field execThreadField = controllerClass.getDeclaredField("executionThread");
                execThreadField.setAccessible(true);
                Thread execThread = (Thread) execThreadField.get(controller);
                sb.append("execThread=").append(execThread.getState().name());
                if (!execThread.isAlive()) {
                    sb.append(" DEAD!");
                }
                sb.append(" ");

                // Check command thread
                java.lang.reflect.Field cmdThreadField = controllerClass.getSuperclass().getDeclaredField("commandThread");
                cmdThreadField.setAccessible(true);
                Thread cmdThread = (Thread) cmdThreadField.get(controller);
                sb.append("cmdThread=").append(cmdThread.getState().name());
                if (!cmdThread.isAlive()) {
                    sb.append(" DEAD!");
                }
                sb.append(" ");

                // Check paused state
                java.lang.reflect.Field pausedField = controllerClass.getSuperclass().getDeclaredField("paused");
                pausedField.setAccessible(true);
                boolean paused = pausedField.getBoolean(controller);
                sb.append("paused=").append(paused);
                sb.append(" ");

                // Check experiment alive
                java.lang.reflect.Field aliveField = controllerClass.getSuperclass().getDeclaredField("experimentAlive");
                aliveField.setAccessible(true);
                boolean alive = aliveField.getBoolean(controller);
                sb.append("alive=").append(alive);
                sb.append(" ");

                // Check agent state
                java.lang.reflect.Field agentField = controllerClass.getDeclaredField("agent");
                agentField.setAccessible(true);
                Object agent = agentField.get(controller);
                if (agent == null) {
                    sb.append("agent=NULL!");
                } else {
                    sb.append("agent=");
                    try {
                        boolean dead = (boolean) agent.getClass().getMethod("dead").invoke(agent);
                        sb.append(dead ? "DEAD" : "alive");
                    } catch (Exception e) {
                        sb.append("?");
                    }
                }
                sb.append(" ");

                // Check scope state
                java.lang.reflect.Field scopeField = controllerClass.getSuperclass().getDeclaredField("scope");
                scopeField.setAccessible(true);
                Object scope = scopeField.get(controller);
                if (scope == null) {
                    sb.append("scope=NULL!");
                } else {
                    sb.append("scopeOK");
                }

            } catch (Exception e) {
                sb.append("reflect_err=").append(e.getMessage());
            }

            Log.i("GAMA-DIAG", sb.toString());
            log(sb.toString());

            // After 3 polls (6s), try a manual synchronous step if experiment seems stuck
            if (pollCount[0] == 3) {
                log("[DIAG] Attempting manual synchronous step...");
                try {
                    // First try to set paused=false and release lock
                    java.lang.reflect.Field pausedField = controllerClass.getSuperclass().getDeclaredField("paused");
                    pausedField.setAccessible(true);
                    boolean currentPaused = pausedField.getBoolean(controller);
                    log("[DIAG] Current paused=" + currentPaused);

                    if (currentPaused) {
                        // Try synchronousStart first
                        java.lang.reflect.Method syncStart = controllerClass.getSuperclass().getDeclaredMethod("synchronousStart");
                        syncStart.setAccessible(true);
                        Object startResult = syncStart.invoke(controller);
                        log("[DIAG] synchronousStart result: " + startResult);
                    }

                    // Then try synchronousStep
                    java.lang.reflect.Method syncStep = controllerClass.getSuperclass().getDeclaredMethod("synchronousStep");
                    syncStep.setAccessible(true);
                    Object stepResult = syncStep.invoke(controller);
                    log("[DIAG] synchronousStep result: " + stepResult);

                    // Check paused state again
                    boolean afterPaused = pausedField.getBoolean(controller);
                    log("[DIAG] After step: paused=" + afterPaused);

                } catch (Exception e) {
                    log("[DIAG] Manual step error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                    if (e.getCause() != null) {
                        log("[DIAG] Cause: " + e.getCause().getClass().getSimpleName() + ": " + e.getCause().getMessage());
                    }
                    Log.w(TAG, "Manual step failed", e);
                }
            }

            // Keep polling
            if (isRunning && pollCount[0] < 20) {
                handler.postDelayed(diagnosticRunnable, 2000);
            }
        };
        handler.postDelayed(diagnosticRunnable, 2000);
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
        if (diagnosticRunnable != null) {
            handler.removeCallbacks(diagnosticRunnable);
        }
        setGuiActivity(null);
        if (currentController != null) stopSimulation();
    }
}
