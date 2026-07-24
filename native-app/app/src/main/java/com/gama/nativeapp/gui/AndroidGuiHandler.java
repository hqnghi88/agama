package com.gama.nativeapp.gui;

import android.app.Activity;
import android.content.Intent;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;

import java.util.LinkedHashMap;
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
import gama.core.kernel.experiment.IExperimentPlan;
import gama.core.kernel.experiment.IParameter;
import gama.core.kernel.model.IModel;
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

public class AndroidGuiHandler implements IGui {

    private static final String TAG = "AndroidGuiHandler";
    private static Activity currentActivity;
    private static AndroidGuiHandler instance;
    private ConsoleListener consoleListener;
    private static IExperimentPlan cachedExperimentPlan;

    /** All registered display outputs keyed by display name */
    private final LinkedHashMap<String, LayeredDisplayOutput> displayOutputs = new LinkedHashMap<>();
    /** All created surfaces keyed by display name */
    private final LinkedHashMap<String, AndroidDisplaySurface> displaySurfaces = new LinkedHashMap<>();

    public static void setActivity(Activity activity) {
        currentActivity = activity;
    }

    public static AndroidGuiHandler getInstance() {
        if (instance == null) instance = new AndroidGuiHandler();
        return instance;
    }

    public static Activity getCurrentActivity() { return currentActivity; }

    /** Get all registered display outputs (read-only view) */
    public Map<String, LayeredDisplayOutput> getDisplayOutputs() {
        return java.util.Collections.unmodifiableMap(displayOutputs);
    }

    /** Get all created surfaces (read-only view) */
    public Map<String, AndroidDisplaySurface> getDisplaySurfaces() {
        return java.util.Collections.unmodifiableMap(displaySurfaces);
    }

    /** Clear all display state for a new experiment run */
    public void clearDisplayState(Activity activity) {
        displayOutputs.clear();
        displaySurfaces.clear();
        if (activity instanceof ExperimentActivity) {
            ExperimentActivity exp = (ExperimentActivity) activity;
            FrameLayout container = exp.getDisplayContainer();
            if (container != null) {
                activity.runOnUiThread(() -> container.removeAllViews());
            }
        }
    }

    @Override
    public IDisplaySurface createDisplaySurfaceFor(LayeredDisplayOutput output, Object... args) {
        String displayName = output.getName();
        if (displaySurfaces.containsKey(displayName)) {
            Log.i(TAG, "Surface already exists for display: " + displayName + ", skipping");
            return displaySurfaces.get(displayName);
        }

        Activity activity = currentActivity;
        if (activity == null) {
            Log.w(TAG, "No activity available for display surface creation");
            return null;
        }

        if (output.getData().is3D()) {
            Log.i(TAG, "Overriding 3D display type to 2d for: " + displayName);
            output.getData().setDisplayType("2d");
        }

        final AndroidDisplaySurface[] surfaceHolder = new AndroidDisplaySurface[1];
        try {
            java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            activity.runOnUiThread(() -> {
                try {
                    surfaceHolder[0] = new AndroidDisplaySurface(activity, output);
                    Log.i(TAG, "Created surface for display: " + displayName);

                    displayOutputs.put(displayName, output);
                    displaySurfaces.put(displayName, surfaceHolder[0]);

                    if (activity instanceof ExperimentActivity) {
                        ExperimentActivity expActivity = (ExperimentActivity) activity;
                        FrameLayout container = expActivity.getDisplayContainer();
                        if (container != null) {
                            FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(
                                    FrameLayout.LayoutParams.MATCH_PARENT,
                                    FrameLayout.LayoutParams.MATCH_PARENT);
                            flp.gravity = Gravity.CENTER;
                            container.addView(surfaceHolder[0], flp);
                        }
                        expActivity.onDisplayRegistered(displayName, surfaceHolder[0]);
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

        if (surfaceHolder[0] != null) {
            setSurfaceField(output, surfaceHolder[0]);
        }

        return surfaceHolder[0];
    }

    @Override
    public DisplayDescription getDisplayDescriptionFor(String name) {
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
        return new AndroidGamaView(name);
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
        Log.i(TAG, "[ARRANGE] called");
        cachedExperimentPlan = experimentPlan;
        Activity activity = currentActivity;

        collectDisplayOutputs(experimentPlan, myScope);

        if (activity instanceof ExperimentActivity) {
            ExperimentActivity expActivity = (ExperimentActivity) activity;
            expActivity.runOnUiThread(() -> expActivity.updateCycleInfo(0, 0));
        }
    }

    /** Collect display outputs from an experiment plan's simulation outputs. */
    private void collectDisplayOutputs(IExperimentPlan experimentPlan, IScope scope) {
        try {
            java.lang.reflect.Method getSimOutputs = experimentPlan.getClass()
                .getMethod("getOriginalSimulationOutputs");
            Object simOutputMgr = getSimOutputs.invoke(experimentPlan);
            if (simOutputMgr == null) return;

            java.lang.reflect.Field outputsField = simOutputMgr.getClass().getSuperclass()
                .getDeclaredField("outputs");
            outputsField.setAccessible(true);
            Object outputsMap = outputsField.get(simOutputMgr);
            if (!(outputsMap instanceof Map)) return;

            Map map = (Map) outputsMap;
            Log.i(TAG, "[COLLECT] Found " + map.size() + " output(s)");
            for (Object val : map.values()) {
                if (val instanceof LayeredDisplayOutput ldo) {
                    displayOutputs.put(ldo.getName(), ldo);
                    if (ldo.getSurface() != null) {
                        Log.i(TAG, "[COLLECT] Surface exists for: " + ldo.getName());
                    } else {
                        if (scope != null) {
                            boolean ok = ldo.init(scope);
                            Log.i(TAG, "[COLLECT] init(" + ldo.getName() + ")=" + ok);
                        }
                        if (ldo.getSurface() == null) {
                            Log.i(TAG, "[COLLECT] Creating surface for: " + ldo.getName());
                            IDisplaySurface surf = createDisplaySurfaceFor(ldo);
                            if (surf != null) {
                                setSurfaceField(ldo, surf);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "[COLLECT] error: " + e.getMessage());
        }
    }

    public static void probeAndCreateSurface() {
        if (cachedExperimentPlan != null) {
            try {
                getInstance().collectDisplayOutputs(cachedExperimentPlan, null);
                return;
            } catch (Exception e) {
                Log.e(TAG, "[PROBE-FALLBACK] error: " + e.getMessage());
            }
        }

        try {
            Class<?> gamaClass = Class.forName("gama.core.runtime.GAMA");
            java.lang.reflect.Field controllersField = gamaClass.getDeclaredField("controllers");
            controllersField.setAccessible(true);
            java.util.List controllers = (java.util.List) controllersField.get(null);
            if (controllers == null || controllers.isEmpty()) return;
            Object controller = controllers.get(controllers.size() - 1);

            java.lang.reflect.Field scopeField = controller.getClass().getSuperclass()
                .getDeclaredField("scope");
            scopeField.setAccessible(true);
            IScope ctrlScope = (IScope) scopeField.get(controller);
            if (ctrlScope == null) return;

            Object rootAgent = ctrlScope.getRoot();
            if (rootAgent == null) return;

            Object simAgent = rootAgent.getClass().getMethod("getSimulation").invoke(rootAgent);
            if (simAgent == null) return;

            Object simOutMgr = simAgent.getClass().getMethod("getOutputManager").invoke(simAgent);
            if (simOutMgr == null) return;

            java.lang.reflect.Field simOutputsField = simOutMgr.getClass().getSuperclass()
                .getDeclaredField("outputs");
            simOutputsField.setAccessible(true);
            Object simOutputsMap = simOutputsField.get(simOutMgr);
            if (!(simOutputsMap instanceof Map)) return;

            Map map = (Map) simOutputsMap;
            for (Object val : map.values()) {
                if (val instanceof LayeredDisplayOutput ldo) {
                    if (ldo.getSurface() == null) {
                        getInstance().createDisplaySurfaceFor(ldo);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "[PROBE] error: " + e.getMessage());
        }
    }

    private static void setSurfaceField(LayeredDisplayOutput output, IDisplaySurface surf) {
        try {
            Class<?> cls = output.getClass();
            java.lang.reflect.Field surfaceField = null;
            while (cls != null && surfaceField == null) {
                try {
                    surfaceField = cls.getDeclaredField("surface");
                } catch (NoSuchFieldException e) {
                    cls = cls.getSuperclass();
                }
            }
            if (surfaceField != null) {
                surfaceField.setAccessible(true);
                if (surfaceField.get(output) == null) {
                    surfaceField.set(output, surf);
                    Log.i(TAG, "Set surface field on output: " + output.getName());
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not set surface field: " + e.getMessage());
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
