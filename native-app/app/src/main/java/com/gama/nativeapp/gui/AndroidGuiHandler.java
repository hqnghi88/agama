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

        final AndroidDisplaySurface[] surfaceHolder = new AndroidDisplaySurface[1];
        try {
            java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            activity.runOnUiThread(() -> {
                try {
                    surfaceHolder[0] = new AndroidDisplaySurface(activity, output);
                    Log.i(TAG, "Created AndroidDisplaySurface for: " + output.getName());

                    if (activity instanceof ExperimentActivity) {
                        ExperimentActivity expActivity = (ExperimentActivity) activity;
                        android.widget.FrameLayout container = expActivity.getDisplayContainer();
                        if (container != null) {
                            container.removeAllViews();
                            container.addView(surfaceHolder[0], new android.widget.FrameLayout.LayoutParams(
                                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
                        }
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
        Log.i(TAG, "Arranging experiment views for: " + (experimentPlan != null ? experimentPlan.getName() : "null"));
        Activity activity = currentActivity;
        if (activity instanceof ExperimentActivity) {
            ExperimentActivity expActivity = (ExperimentActivity) activity;
            expActivity.runOnUiThread(() -> {
                expActivity.updateCycleInfo(0, 0);
            });
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
