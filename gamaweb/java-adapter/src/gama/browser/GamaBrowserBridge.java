package gama.browser;

import java.io.*;
import java.nio.file.*;
import java.util.*;

import gama.api.GAMA;
import gaml.compiler.validation.GamlModelBuilder;
import gama.api.compilation.GamlCompilationError;
import gama.api.kernel.species.IModelSpecies;
import gama.api.kernel.species.IExperimentSpecies;
import gama.api.kernel.species.ISpecies;
import gama.api.kernel.agent.IAgent;
import gama.api.kernel.agent.IPopulation;
import gama.api.runtime.scope.IScope;
import gama.headless.core.Experiment;
import gama.headless.core.RichExperiment;
import gama.core.CoreActivator;
import gama.workspace.WorkspaceActivator;
import gaml.compiler.GamlStandaloneSetup;
import gama.api.utils.prefs.GamaPreferences;

public class GamaBrowserBridge {

    static boolean initialized = false;

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("{\"error\":\"Usage: GamaBrowserBridge <init|compile|run>\"}");
            return;
        }
        String cmd = args[0];
        switch (cmd) {
            case "init": doInit(); break;
            case "compile": {
                // args[1] = GAML source string, args[2] = optional output path
                String gaml = args[1];
                String outPath = args.length > 2 ? args[2] : null;
                doCompileGaml(gaml, outPath);
                break;
            }
            case "run": {
                // args[1] = GAML source, args[2] = steps, args[3] = optional output path
                String gaml = args[1];
                int steps = Integer.parseInt(args[2]);
                String outPath = args.length > 3 ? args[3] : null;
                doRunGaml(gaml, steps, outPath);
                break;
            }
            default: System.out.println("{\"error\":\"Unknown command: " + esc(cmd) + "\"}");
        }
    }

    static void doCompileGaml(String gaml, String outputPath) throws Exception {
        doInit();
        File tmp = File.createTempFile("gama_", ".gaml");
        tmp.deleteOnExit();
        Files.writeString(tmp.toPath(), gaml);
        String outPath = outputPath != null ? outputPath : "/files/gama_output.json";
        doCompile(tmp.getAbsolutePath(), outPath);
    }

    static void doRunGaml(String gaml, int numSteps, String outputPath) throws Exception {
        doInit();
        File tmp = File.createTempFile("gama_", ".gaml");
        tmp.deleteOnExit();
        Files.writeString(tmp.toPath(), gaml);
        String outPath = outputPath != null ? outputPath : "/files/gama_output.json";
        doRun(tmp.getAbsolutePath(), numSteps, outPath);
    }

    static void doInit() throws Exception {
        if (initialized) { out("{\"ok\":true,\"msg\":\"already initialized\"}", null); return; }
        System.setProperty("java.awt.headless", "true");
        GAMA.setHeadLessMode(false);
        GAMA.setHeadlessGui(new gama.api.ui.NullGuiHandler());
        WorkspaceActivator.load();
        GamlStandaloneSetup.doSetup();
        CoreActivator.load();
        GamlStandaloneSetup.initializeAfterPlatformReady(null);
        GamaPreferences.External.CORE_SEED_DEFINED.set(true);
        GamaPreferences.External.CORE_SEED.set(1.0);
        initialized = true;
        out("{\"ok\":true,\"msg\":\"GAMA initialized\"}", null);
    }

    static void doCompile(String inputPath, String outputPath) throws Exception {
        doInit();
        File f = new File(inputPath);
        List<GamlCompilationError> errors = new ArrayList<>();
        IModelSpecies model = GamlModelBuilder.getInstance().compile(f, errors, null);
        StringBuilder sb = new StringBuilder();
        sb.append("{\"success\":").append(model != null);
        if (model == null) {
            sb.append(",\"errors\":[");
            boolean first = true;
            for (GamlCompilationError e : errors) {
                if (!first) sb.append(","); first = false;
                sb.append("\"").append(esc(e.toString())).append("\"");
            }
            sb.append("]");
        } else {
            sb.append(",\"name\":\"").append(esc(model.getName())).append("\"");
            sb.append(",\"experiments\":[");
            boolean first = true;
            for (IExperimentSpecies exp : model.getExperiments()) {
                if (!first) sb.append(","); first = false;
                sb.append("\"").append(esc(exp.getName())).append("\"");
            }
            sb.append("]");
            sb.append(",\"species\":[");
            first = true;
            for (String sn : model.getAllSpecies().keySet()) {
                if (!first) sb.append(","); first = false;
                sb.append("\"").append(esc(sn)).append("\"");
            }
            sb.append("]");
        }
        sb.append("}");
        out(sb.toString(), outputPath);
    }

    static void doRun(String inputPath, int numSteps, String outputPath) throws Exception {
        doInit();
        File f = new File(inputPath);
        List<GamlCompilationError> errors = new ArrayList<>();
        IModelSpecies model = GamlModelBuilder.getInstance().compile(f, errors, null);
        if (model == null) {
            out("{\"success\":false,\"error\":\"Compilation failed\"}", outputPath);
            return;
        }
        String expName = null;
        for (IExperimentSpecies e : model.getExperiments()) { expName = e.getName(); break; }
        if (expName == null) {
            out("{\"success\":false,\"error\":\"No experiments\"}", outputPath);
            return;
        }

        Experiment exp = new RichExperiment(model);
        exp.setup(expName, 0.0);

        StringBuilder sb = new StringBuilder();
        sb.append("{\"success\":true");
        sb.append(",\"model\":\"").append(esc(model.getName())).append("\"");
        sb.append(",\"experiment\":\"").append(esc(expName)).append("\"");
        sb.append(",\"steps\":[");

        IScope simScope = exp.getSimulation().getScope();

        for (int step = 0; step < numSteps; step++) {
            exp.step();
            if (step > 0) sb.append(",");
            sb.append("{\"n\":").append(step + 1).append(",\"s\":[");

            boolean firstSp = true;
            for (String spName : model.getAllSpecies().keySet()) {
                try {
                    ISpecies sp = model.getSpecies(spName);
                    if (sp == null) continue;
                    IPopulation<? extends IAgent> pop = sp.getPopulation(simScope);
                    if (pop == null || pop.length(simScope) == 0) continue;
                    int count = pop.length(simScope);
                    if (!firstSp) sb.append(","); firstSp = false;
                    sb.append("{\"name\":\"").append(esc(spName)).append("\",\"a\":[");
                    for (int i = 0; i < count; i++) {
                        IAgent agent = pop.get(simScope, i);
                        if (i > 0) sb.append(",");
                        sb.append("{");
                        try {
                            var loc = agent.getLocation(simScope);
                            if (loc != null) {
                                sb.append("\"x\":").append(fmt(loc.getX()));
                                sb.append(",\"y\":").append(fmt(loc.getY()));
                            }
                        } catch (Exception ignored) {}
                        try {
                            Object h = agent.getDirectVarValue(simScope, "heading");
                            if (h != null) sb.append(",\"h\":").append(fmt(((Number) h).doubleValue()));
                        } catch (Exception ignored) {}
                        try {
                            Object c = agent.getDirectVarValue(simScope, "color");
                            if (c != null) sb.append(",\"c\":\"").append(esc(c.toString())).append("\"");
                        } catch (Exception ignored) {}
                        sb.append("}");
                    }
                    sb.append("]}");
                } catch (Exception ignored) {}
            }
            sb.append("]}");
        }
        sb.append("]}");
        exp.dispose();
        out(sb.toString(), outputPath);
    }

    static void out(String data, String path) throws Exception {
        if (path != null) Files.writeString(Path.of(path), data);
        else System.out.println(data);
    }

    static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "").replace("\t", "\\t");
    }
    static String fmt(double v) {
        if (v == (long) v) return String.valueOf((long) v);
        return Double.toString(v);
    }
}
