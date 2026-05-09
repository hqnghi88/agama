package com.simulation.api.model;

public class SimulationRequest {
    private String model;
    private String experiment;
    private int steps = 100;
    private String jobId;

    public SimulationRequest() {}

    public SimulationRequest(String model, String experiment, int steps) {
        this.model = model;
        this.experiment = experiment;
        this.steps = steps;
    }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public String getExperiment() { return experiment; }
    public void setExperiment(String experiment) { this.experiment = experiment; }

    public int getSteps() { return steps; }
    public void setSteps(int steps) { this.steps = steps; }

    public String getJobId() { return jobId; }
    public void setJobId(String jobId) { this.jobId = jobId; }
}
