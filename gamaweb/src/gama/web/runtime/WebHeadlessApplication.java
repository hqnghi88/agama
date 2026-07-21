/*******************************************************************************************************
 *
 * WebHeadlessApplication.java, in gama.web, is part of the source code of the GAMA modeling and simulation platform
 * (v.2025-03).
 *
 * (c) 2007-2026 UMI 209 UMMISCO IRD/SU & Partners (IRIT, MIAT, ESPACE-DEV, CTU)
 *
 * Visit https://github.com/gama-platform/gama for license information and contacts.
 *
 ********************************************************************************************************/
package gama.web.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.emf.common.util.URI;

import gama.api.GAMA;
import gama.api.compilation.GamlCompilationError;
import gama.api.kernel.species.IExperimentSpecies;
import gama.api.kernel.species.IModelSpecies;
import gama.api.runtime.GamaExecutorService;
import gama.dev.DEBUG;
import gaml.compiler.validation.GamlModelBuilder;

/**
 * Browser-compatible headless application entry point.
 * This class provides methods that can be called from JavaScript (via CheerpJ)
 * to load models, run experiments, and receive rendering callbacks.
 *
 * Usage from JavaScript:
 * <pre>
 *   const app = WebHeadlessApplication.getInstance();
 *   app.loadModel("path/to/model.gaml", "main");
 *   app.runExperiment(sessionId, 0);
 *   app.setFrameCallback((sessionId, frameData, step) => { ... });
 * </pre>
 *
 * @author GAMA Team
 */
public class WebHeadlessApplication {

    /** The singleton instance. */
    private static WebHeadlessApplication instance;

    /** Active experiments keyed by session ID. */
    private final Map<String, ExperimentSession> sessions = new ConcurrentHashMap<>();

    /** Frame callback listener. */
    private FrameCallback frameCallback;

    /** Executor for running experiments. */
    private final ExecutorService executor = Executors.newCachedThreadPool();

    /** Global step counter. */
    private final AtomicLong globalStep = new AtomicLong(0);

    /** Whether the application is initialized. */
    private final AtomicBoolean initialized = new AtomicBoolean(false);

    /**
     * Functional interface for receiving frame data from the simulation.
     */
    public interface FrameCallback {
        /**
         * Called when a frame is rendered.
         * @param sessionId the experiment session ID
         * @param frameData JSON string with all drawing commands
         * @param step the current simulation step
         */
        void onFrame(String sessionId, String frameData, long step);
    }

    /**
     * Represents an active experiment session.
     */
    public static class ExperimentSession {
        public final String id;
        public final String modelPath;
        public final String experimentName;
        public IModelSpecies modelSpecies;
        public IExperimentSpecies experimentSpecies;
        public volatile boolean running = false;
        public volatile boolean paused = false;
        public volatile long currentStep = 0;

        public ExperimentSession(String id, String modelPath, String experimentName) {
            this.id = id;
            this.modelPath = modelPath;
            this.experimentName = experimentName;
        }
    }

    private WebHeadlessApplication() {}

    /**
     * Gets the singleton instance.
     * @return the application instance
     */
    public static synchronized WebHeadlessApplication getInstance() {
        if (instance == null) {
            instance = new WebHeadlessApplication();
        }
        return instance;
    }

    /**
     * Initializes the GAMA platform for web use.
     * Must be called before any other method.
     */
    public void initialize() {
        if (initialized.get()) return;
        DEBUG.LOG("Initializing GAMA Web runtime...");
        System.setProperty("java.awt.headless", "true");
        System.setProperty("gama.web.mode", "true");
        GAMA.setHeadLessMode(true);
        GAMA.setHeadlessGui(new gama.headless.server.GamaServerGUIHandler());

        try {
            gama.workspace.WorkspaceActivator.load();
            gaml.compiler.GamlStandaloneSetup.doSetup();
            gama.core.CoreActivator.load();
            initialized.set(true);
            DEBUG.LOG("GAMA Web runtime initialized.");
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize GAMA Web runtime", e);
        }
    }

    /**
     * Sets the frame callback for receiving rendered frames.
     * @param callback the callback
     */
    public void setFrameCallback(FrameCallback callback) {
        this.frameCallback = callback;
    }

    /**
     * Loads a GAML model and returns a session ID.
     * @param modelPath path to the .gaml file
     * @param experimentName the experiment to run
     * @return session ID, or null on failure
     */
    public String loadModel(String modelPath, String experimentName) {
        initialize();

        String sessionId = "session_" + System.currentTimeMillis();
        ExperimentSession session = new ExperimentSession(sessionId, modelPath, experimentName);

        try {
            // Compile the model using GamlModelBuilder (same as HeadlessApplication)
            final GamlModelBuilder builder = new GamlModelBuilder(
                gaml.compiler.GamlStandaloneSetup.doSetup()
            );

            final List<GamlCompilationError> errors = new ArrayList<>();
            URI uri;
            try {
                uri = URI.createFileURI(modelPath);
            } catch (Exception e) {
                uri = URI.createURI(modelPath);
            }

            final IModelSpecies mdl = builder.compile(uri, errors);

            if (mdl == null) {
                DEBUG.ERR("Failed to compile model: " + modelPath);
                for (GamlCompilationError err : errors) {
                    DEBUG.ERR("  " + err.getMessage());
                }
                return null;
            }

            // Get the experiment
            final IExperimentSpecies expPlan = mdl.getExperiment(experimentName);
            if (expPlan == null) {
                DEBUG.ERR("Experiment '" + experimentName + "' not found in " + modelPath);
                return null;
            }

            session.modelSpecies = mdl;
            session.experimentSpecies = expPlan;

            sessions.put(sessionId, session);
            DEBUG.LOG("Model loaded: " + modelPath + " [session=" + sessionId + "]");
            return sessionId;

        } catch (Exception e) {
            DEBUG.ERR("Failed to load model: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Runs an experiment step by step.
     * @param sessionId the session ID
     * @param steps number of steps to run (0 for infinite until stop)
     */
    public void runExperiment(String sessionId, int steps) {
        ExperimentSession session = sessions.get(sessionId);
        if (session == null) {
            DEBUG.ERR("Session not found: " + sessionId);
            return;
        }

        session.running = true;
        session.paused = false;

        executor.submit(() -> {
            try {
                GamaExecutorService.CONCURRENCY_SIMULATIONS.set(true);

                session.experimentSpecies.setHeadless(true);
                session.experimentSpecies.open();
                GAMA.getControllers().add(session.experimentSpecies.getController());

                if (steps > 0) {
                    for (int i = 0; i < steps && session.running && !session.paused; i++) {
                        session.experimentSpecies.getController().processStep(true);
                        session.currentStep = globalStep.incrementAndGet();
                    }
                } else {
                    // Run until stopped
                    while (session.running && !session.paused) {
                        session.experimentSpecies.getController().processStep(true);
                        session.currentStep = globalStep.incrementAndGet();
                    }
                }
            } catch (Exception e) {
                DEBUG.ERR("Experiment error: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    /**
     * Pauses a running experiment.
     * @param sessionId the session ID
     */
    public void pauseExperiment(String sessionId) {
        ExperimentSession session = sessions.get(sessionId);
        if (session != null) {
            session.paused = true;
            if (session.experimentSpecies != null) {
                session.experimentSpecies.getController().processPause(true);
            }
        }
    }

    /**
     * Stops an experiment.
     * @param sessionId the session ID
     */
    public void stopExperiment(String sessionId) {
        ExperimentSession session = sessions.get(sessionId);
        if (session != null) {
            session.running = false;
            session.paused = false;
            if (session.experimentSpecies != null) {
                session.experimentSpecies.getController().processPause(true);
                session.experimentSpecies.getController().close();
            }
            sessions.remove(sessionId);
        }
    }

    /**
     * Gets the current step of an experiment.
     * @param sessionId the session ID
     * @return the current step
     */
    public long getCurrentStep(String sessionId) {
        ExperimentSession session = sessions.get(sessionId);
        return session != null ? session.currentStep : -1;
    }

    /**
     * Gets all active session IDs.
     * @return list of session IDs
     */
    public List<String> getActiveSessions() {
        return new ArrayList<>(sessions.keySet());
    }

    /**
     * Disposes all sessions and shuts down.
     */
    public void dispose() {
        for (String sessionId : new ArrayList<>(sessions.keySet())) {
            stopExperiment(sessionId);
        }
        executor.shutdown();
    }

    // --- Static accessors for JavaScript ---

    public static void main(String[] args) {
        WebHeadlessApplication app = getInstance();
        app.initialize();
        DEBUG.LOG("GAMA Web runtime ready.");
    }

}
