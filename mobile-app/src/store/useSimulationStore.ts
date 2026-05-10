import {create} from 'zustand';
import {api, type SimulationStatus, type GamlModel} from '../services/api';

interface JobInfo {
  id: string;
  state: string;
  progress: number;
  name?: string;
  model?: string;
  has_frame?: boolean;
  current_step?: number;
  steps?: number;
  experiment?: string;
  [key: string]: unknown;
}

interface LogEntry {
  timestamp: string;
  message: string;
  level: 'info' | 'warn' | 'error' | 'debug';
}

interface SimulationState {
  backendStatus: 'stopped' | 'starting' | 'running' | 'error';
  backendPid: number | null;
  backendProgress: string;
  backendUptime: number;
  connected: boolean;
  running: boolean;
  jobs: JobInfo[];
  logs: LogEntry[];
  error: string | null;
  models: GamlModel[];
  selectedModel: GamlModel | null;
  selectedExperiment: string | null;
  loadingModels: boolean;
  modelsFetched: boolean;

  checkHealth: () => Promise<void>;
  checkNativeStatus: (nativeStatus: string, nativeProgress?: string) => void;
  checkStatus: () => Promise<void>;
  fetchModels: () => Promise<void>;
  clearModels: () => void;
  setSelectedModel: (model: GamlModel | null) => void;
  setSelectedExperiment: (experiment: string | null) => void;
  startSimulation: (config?: Record<string, unknown>) => Promise<string | null>;
  stopSimulation: (jobId?: string) => Promise<void>;
  setBackendStatus: (status: SimulationState['backendStatus']) => void;
  addLog: (level: LogEntry['level'], message: string) => void;
  clearLogs: () => void;
  reset: () => void;
}

const initialLog: LogEntry = {
  timestamp: new Date().toISOString(),
  message: 'GAMA Mobile initialized',
  level: 'info',
};

export const useSimulationStore = create<SimulationState>((set, get) => ({
  backendStatus: 'stopped',
  backendPid: null,
  backendProgress: '',
  backendUptime: 0,
  connected: false,
  running: false,
  jobs: [],
  logs: [initialLog],
  error: null,
  models: [],
  selectedModel: null,
  selectedExperiment: null,
  loadingModels: false,
  modelsFetched: false,

  checkHealth: async () => {
    try {
      const response = await api.health();
      const wasConnected = get().connected;
      set({
        connected: true,
        backendStatus: 'running',
        backendUptime: (response.uptime as number) || 0,
        error: null,
      });
      if (!wasConnected) {
        get().addLog('info', 'Backend connected');
      }
    } catch (err) {
      const wasConnected = get().connected;
      set({
        connected: false,
        backendStatus: 'stopped',
        backendUptime: 0,
      });
      if (wasConnected) {
        get().addLog('warn', 'Backend disconnected');
      }
    }
  },

  checkNativeStatus: (nativeStatus: string, nativeProgress?: string) => {
    const state = get();
    if (nativeProgress !== undefined) {
      set({backendProgress: nativeProgress});
    }
    if (!state.connected && nativeStatus === 'running') {
      set({backendStatus: 'running', error: null, backendProgress: ''});
    } else if (nativeStatus.startsWith('error')) {
      const msg = nativeStatus.replace(/^error:\s*/, '');
      set({backendStatus: 'error', error: msg, backendProgress: ''});
    } else if (nativeStatus === 'initializing' || nativeStatus === 'starting') {
      set({backendStatus: 'starting', error: null});
    }
  },

  checkStatus: async () => {
    try {
      const status: SimulationStatus = await api.getStatus();
      const prevJobs = get().jobs;
      const newJobs = (status.jobs || []) as JobInfo[];

      // Detect newly completed jobs
      for (const job of newJobs) {
        const prev = prevJobs.find(j => j.id === job.id);
        if (prev && prev.state !== 'completed' && job.state === 'completed') {
          get().addLog('info', `Simulation ${job.id} completed (${job.progress}%)`);
        } else if (prev && prev.state !== 'running' && job.state === 'running') {
          get().addLog('info', `Simulation ${job.id} running: step ${job.current_step || '?'}`);
        }
      }

      set({
        connected: true,
        running: status.running,
        backendUptime: status.uptime,
        jobs: newJobs,
        error: null,
      });
    } catch {
      set({connected: false});
    }
  },

  fetchModels: async () => {
    set({loadingModels: true});
    try {
      const response = await api.getModels();
      const list = response.models || [];
      set({models: list, loadingModels: false, modelsFetched: list.length > 0});
      get().addLog('info', `Loaded ${response.total} model(s)`);
    } catch (err) {
      set({loadingModels: false});
      get().addLog('warn', `Failed to load models: ${(err as Error).message}`);
    }
  },

  setSelectedModel: (model) => {
    const experiment = model?.experiments?.[0] ?? null;
    set({selectedModel: model, selectedExperiment: experiment});
    if (model) {
      get().addLog('info', `Selected model: ${model.name}`);
    }
  },

  setSelectedExperiment: (experiment) => {
    set({selectedExperiment: experiment});
  },

  startSimulation: async (config) => {
    try {
      const selected = get().selectedModel;
      const experiment = get().selectedExperiment;
      if (!selected) {
        get().addLog('error', 'No model selected');
        return null;
      }
      if (!experiment) {
        get().addLog('error', 'No experiment selected');
        return null;
      }
      get().addLog('info', `Starting simulation: ${selected.name}#${experiment}`);
      const response = await api.startSimulation({
        model: selected.path,
        experiment,
        ...config,
      });
      const jobId = response.job_id as string;
      get().addLog('info', `Simulation started: ${jobId}`);
      await get().checkStatus();
      return jobId;
    } catch (err) {
      const msg = `Failed to start: ${(err as Error).message}`;
      get().addLog('error', msg);
      set({error: msg});
      return null;
    }
  },

  stopSimulation: async (jobId) => {
    try {
      get().addLog('info', `Stopping simulation ${jobId || '(all)'}...`);
      await api.stopSimulation(jobId);
      get().addLog('info', 'Simulation stopped');
      await get().checkStatus();
    } catch (err) {
      const msg = `Failed to stop: ${(err as Error).message}`;
      get().addLog('error', msg);
      set({error: msg});
    }
  },

  setBackendStatus: (status) => {
    set({backendStatus: status});
    get().addLog('info', `Backend status: ${status}`);
  },

  addLog: (level, message) => {
    const entry: LogEntry = {
      timestamp: new Date().toISOString(),
      message,
      level,
    };
    set(state => ({
      logs: [entry, ...state.logs].slice(0, 200),
    }));
  },

  clearModels: () => {
    set({models: [], selectedModel: null, selectedExperiment: null, modelsFetched: false});
  },

  clearLogs: () => {
    set({logs: []});
  },

  reset: () => {
    set({
      backendStatus: 'stopped',
      backendPid: null,
      backendProgress: '',
      backendUptime: 0,
      connected: false,
      running: false,
      jobs: [],
      error: null,
      models: [],
      selectedModel: null,
      selectedExperiment: null,
      loadingModels: false,
      modelsFetched: false,
    });
  },
}));

export type {JobInfo, LogEntry};
