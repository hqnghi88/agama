package com.gama.nativeapp.gui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import com.gama.nativeapp.ExperimentActivity;
import com.gama.nativeapp.display.AndroidDisplaySurface;

import gama.core.common.interfaces.IDisplayCreator.DisplayDescription;
import gama.core.common.interfaces.IDisplaySurface;
import gama.core.common.interfaces.IConsoleListener;
import gama.core.common.interfaces.IGamaView;
import gama.core.common.interfaces.IGui;
import gama.core.common.interfaces.IDisposable;
import gama.core.kernel.experiment.IExperimentPlan;
import gama.core.kernel.experiment.IParameter;
import gama.core.kernel.model.IModel;
import gama.core.kernel.simulation.SimulationAgent;
import gama.core.outputs.LayeredDisplayOutput;
import gama.core.runtime.GAMA;
import gama.core.runtime.IScope;
import gama.core.runtime.exceptions.GamaRuntimeException;
import gama.core.util.GamaColor;
import gama.core.util.GamaFont;
import gama.core.util.IList;
import gama.core.util.IMap;
import gama.gaml.descriptions.ActionDescription;
import gama.gaml.statements.test.CompoundSummary;
import gama.gaml.statements.test.TestExperimentSummary;

public class AndroidGuiHandler implements IGui {

    private static final String TAG = "AndroidGuiHandler";
    private static Activity currentActivity;
    private static AndroidGuiHandler instance;
    private ConsoleListener consoleListener;
    private static AndroidDisplaySurface pendingSurface;
    private static IExperimentPlan cachedExperimentPlan;
    public static LayeredDisplayOutput cachedDisplayOutput;

    public static void setActivity(Activity activity) {
        currentActivity = activity;
    }

    public static AndroidGuiHandler getInstance() {
        if (instance == null) instance = new AndroidGuiHandler();
        return instance;
    }

    public static Activity getCurrentActivity() { return currentActivity; }

    @Override
    public IDisplaySurface createDisplaySurfaceFor(LayeredDisplayOutput output, Object... args) {
        Activity activity = currentActivity;
        if (activity == null) {
            Log.w(TAG, "No activity available for display surface creation");
            return null;
        }

        // Force display type to 2d on Android — no OpenGL available
        if (output.getData().is3D()) {
            Log.i(TAG, "Overriding 3D display type to 2d for: " + output.getName());
            output.getData().setDisplayType("2d");
        }

        final AndroidDisplaySurface[] surfaceHolder = new AndroidDisplaySurface[1];
        try {
            java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            activity.runOnUiThread(() -> {
                try {
                    surfaceHolder[0] = new AndroidDisplaySurface(activity, output);
                    Log.i(TAG, "Created AndroidDisplaySurface for: " + output.getName() + " on " + activity.getClass().getSimpleName());

                    if (activity instanceof ExperimentActivity) {
                        ExperimentActivity expActivity = (ExperimentActivity) activity;
                        android.widget.FrameLayout container = expActivity.getDisplayContainer();
                        if (container != null) {
                            container.removeAllViews();
                            container.addView(surfaceHolder[0], new android.widget.FrameLayout.LayoutParams(
                                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
                            Log.i(TAG, "Surface added to container directly");
                        }
                    } else {
                        pendingSurface = surfaceHolder[0];
                        Log.i(TAG, "Holding surface as pending (activity is " + activity.getClass().getSimpleName() + ")");
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error creating display surface on UI thread", e);
                } finally {
                    latch.countDown();
                }
            });
            latch.await(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Log.e(TAG, "Interrupted waiting for UI thread", e);
        }

        return surfaceHolder[0];
    }

    @Override
    public DisplayDescription getDisplayDescriptionFor(String name) {
        // Return a basic description - the actual display creation goes through createDisplaySurfaceFor
        return null;
    }

    @Override
    public boolean openSimulationPerspective(IModel model, String experimentId) {
        Activity activity = currentActivity;
        if (activity == null) return false;
        Intent intent = new Intent(activity, ExperimentActivity.class);
        intent.putExtra("model_name", model.getName());
        intent.putExtra("experiment_name", experimentId);
        activity.startActivity(intent);
        return true;
    }

    @Override
    public void openMessageDialog(IScope scope, String error) {
        Log.i(TAG, "Message: " + error);
    }

    @Override
    public void openErrorDialog(IScope scope, String error) {
        Log.e(TAG, "Error: " + error);
    }

    @Override
    public void runtimeError(IScope scope, GamaRuntimeException g) {
        Log.e(TAG, "Runtime error: " + (g != null ? g.getMessage() : "unknown"), g);
    }

    @Override
    public IConsoleListener getConsole() {
        if (consoleListener == null) consoleListener = new ConsoleListener();
        return consoleListener;
    }

    @Override
    public void run(String taskName, Runnable opener, boolean asynchronous) {
        if (asynchronous) {
            new Thread(opener, taskName).start();
        } else {
            opener.run();
        }
    }

    @Override
    public IGamaView showView(IScope scope, String viewId, String name, int code) {
        Log.i(TAG, "showView called: viewId=" + viewId + ", name=" + name);
        AndroidGamaView view = new AndroidGamaView(name);

        Activity activity = currentActivity;
        if (activity instanceof ExperimentActivity) {
            ExperimentActivity expActivity = (ExperimentActivity) activity;
            expActivity.runOnUiThread(() -> {
                android.widget.FrameLayout container = expActivity.getDisplayContainer();
                if (container != null && pendingSurface != null && container.getChildCount() == 0) {
                    container.addView(pendingSurface, new android.widget.FrameLayout.LayoutParams(
                            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                            android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
                    Log.i(TAG, "Added pending surface to container in showView");
                    pendingSurface = null;
                } else if (container != null) {
                    Log.i(TAG, "Container has " + container.getChildCount() + " children, pendingSurface=" + (pendingSurface != null));
                }
            });
        }
        return view;
    }

    @Override
    public void exit() {
        Activity activity = currentActivity;
        if (activity != null) {
            activity.runOnUiThread(() -> activity.finish());
        }
    }

    @Override
    public Map<String, Object> openUserInputDialog(IScope scope, String title,
            List<IParameter> parameters, GamaFont font, GamaColor color, Boolean showTitle) {
        return java.util.Collections.emptyMap();
    }

    @Override
    public IMap<String, IMap<String, Object>> openWizard(IScope scope, String title,
            ActionDescription finish, IList<IMap<String, Object>> pages) {
        return null;
    }

    @Override
    public void displayTestsResults(IScope scope, CompoundSummary<?, ?> summary) {}

    @Override
    public void arrangeExperimentViews(IScope myScope, IExperimentPlan experimentPlan,
            Boolean keepTabs, Boolean keepToolbars, Boolean showConsoles,
            Boolean showParameters, Boolean showNavigator, Boolean showControls,
            Boolean keepTray, Supplier<GamaColor> color, boolean showEditors) {
        Log.i(TAG, "[ARRANGE] called, scope=" + (myScope == null ? "null" : myScope.getClass().getSimpleName()));
        Activity activity = currentActivity;
        cachedExperimentPlan = experimentPlan;

        // Find the LayeredDisplayOutput definition and init it with the scope to create its surface
        try {
            java.lang.reflect.Method getSimOutputs = experimentPlan.getClass().getMethod("getOriginalSimulationOutputs");
            Object simOutputMgr = getSimOutputs.invoke(experimentPlan);
            if (simOutputMgr != null) {
                java.lang.reflect.Field outputsField = simOutputMgr.getClass().getSuperclass().getDeclaredField("outputs");
                outputsField.setAccessible(true);
                Object outputsMap = outputsField.get(simOutputMgr);
                if (outputsMap instanceof java.util.Map) {
                    java.util.Map map = (java.util.Map) outputsMap;
                    Log.i(TAG, "[ARRANGE] Found " + map.size() + " output(s)");
                    for (Object val : map.values()) {
                        if (val instanceof LayeredDisplayOutput) {
                            LayeredDisplayOutput ldo = (LayeredDisplayOutput) val;
                            cachedDisplayOutput = ldo;
                            if (ldo.getSurface() != null) {
                                Log.i(TAG, "[ARRANGE] Surface already exists for: " + ldo.getName());
                            } else if (myScope != null) {
                                boolean ok = ldo.init(myScope);
                                Log.i(TAG, "[ARRANGE] init(" + ldo.getName() + ")=" + ok);
                                IDisplaySurface surf = ldo.getSurface();
                                Log.i(TAG, "[ARRANGE] Surface: " + (surf == null ? "NULL" : surf.getClass().getSimpleName()));
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "[ARRANGE] error: " + e.getMessage(), e);
        }

        if (activity instanceof ExperimentActivity) {
            ExperimentActivity expActivity = (ExperimentActivity) activity;
            expActivity.runOnUiThread(() -> {
                expActivity.updateCycleInfo(0, 0);
            });
        }
    }

    public static void probeAndCreateSurface() {
        // First try the experiment plan's output definitions — call init() to create surface
        if (cachedExperimentPlan != null) {
            try {
                java.lang.reflect.Method getSimOutputs = cachedExperimentPlan.getClass().getMethod("getOriginalSimulationOutputs");
                Object simOutputMgr = getSimOutputs.invoke(cachedExperimentPlan);
                if (simOutputMgr != null) {
                    java.lang.reflect.Field outputsField = simOutputMgr.getClass().getSuperclass().getDeclaredField("outputs");
                    outputsField.setAccessible(true);
                    Object outputsMap = outputsField.get(simOutputMgr);
                    if (outputsMap instanceof java.util.Map) {
                        java.util.Map map = (java.util.Map) outputsMap;
                        for (Object val : map.values()) {
                            if (val instanceof LayeredDisplayOutput) {
                                LayeredDisplayOutput ldo = (LayeredDisplayOutput) val;
                                if (ldo.getSurface() != null) continue;
                                // Get controller scope to init
                                Class<?> gamaClass = Class.forName("gama.core.runtime.GAMA");
                                java.lang.reflect.Field controllersField = gamaClass.getDeclaredField("controllers");
                                controllersField.setAccessible(true);
                                java.util.List controllers = (java.util.List) controllersField.get(null);
                                if (controllers != null && !controllers.isEmpty()) {
                                    Object controller = controllers.get(controllers.size() - 1);
                                    java.lang.reflect.Field scopeField = controller.getClass().getSuperclass().getDeclaredField("scope");
                                    scopeField.setAccessible(true);
                                    IScope ctrlScope = (IScope) scopeField.get(controller);
                                    if (ctrlScope != null) {
                                        Log.i(TAG, "[PROBE-FALLBACK] init output: " + ldo.getName());
                                        boolean ok = ldo.init(ctrlScope);
                                        Log.i(TAG, "[PROBE-FALLBACK] init()=" + ok + " surface=" + (ldo.getSurface() == null ? "NULL" : "OK"));
                                    }
                                }
                            }
                        }
                    }
                }
                return;
            } catch (Exception e) {
                Log.e(TAG, "[PROBE-FALLBACK] error: " + e.getMessage());
            }
        }

        // Original probe path
        try {
            Class<?> gamaClass = Class.forName("gama.core.runtime.GAMA");
            java.lang.reflect.Field controllersField = gamaClass.getDeclaredField("controllers");
            controllersField.setAccessible(true);
            java.util.List controllers = (java.util.List) controllersField.get(null);
            if (controllers == null || controllers.isEmpty()) return;
            Object controller = controllers.get(controllers.size() - 1);

            java.lang.reflect.Field scopeField = controller.getClass().getSuperclass().getDeclaredField("scope");
            scopeField.setAccessible(true);
            IScope ctrlScope = (IScope) scopeField.get(controller);
            if (ctrlScope == null) return;

            Object rootAgent = ctrlScope.getRoot();
            if (rootAgent == null) return;

            Object simAgent = rootAgent.getClass().getMethod("getSimulation").invoke(rootAgent);
            if (simAgent == null) {
                Log.i(TAG, "[PROBE] no simulation yet");
                return;
            }

            Object simOutMgr = simAgent.getClass().getMethod("getOutputManager").invoke(simAgent);
            if (simOutMgr == null) {
                Log.i(TAG, "[PROBE] no sim output manager");
                return;
            }

            java.lang.reflect.Field simOutputsField = simOutMgr.getClass().getSuperclass().getDeclaredField("outputs");
            simOutputsField.setAccessible(true);
            Object simOutputsMap = simOutputsField.get(simOutMgr);
            if (!(simOutputsMap instanceof java.util.Map)) {
                Log.i(TAG, "[PROBE] sim outputs not a map");
                return;
            }

            java.util.Map map = (java.util.Map) simOutputsMap;
            Log.i(TAG, "[PROBE] Sim output manager has " + map.size() + " output(s)");
            for (Object key : map.keySet()) {
                Object val = map.get(key);
                if (val instanceof LayeredDisplayOutput) {
                    LayeredDisplayOutput ldo = (LayeredDisplayOutput) val;
                    IDisplaySurface surf = ldo.getSurface();
                    Log.i(TAG, "[PROBE]   " + key + " surface=" + (surf == null ? "NULL" : "OK"));
                    if (surf == null) {
                        IDisplaySurface newSurf = getInstance().createDisplaySurfaceFor(ldo);
                        Log.i(TAG, "[PROBE]   created: " + (newSurf == null ? "NULL" : "OK"));
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "[PROBE] error: " + e.getMessage());
        }
    }

    @Override
    public void updateParameters(boolean refreshValues) {}

    private static class ConsoleListener implements IConsoleListener {
        @Override
        public void informConsole(String s, gama.core.kernel.experiment.ITopLevelAgent root, GamaColor color) {
            Log.i(TAG, s);
        }
    }
}
