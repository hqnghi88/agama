import {create} from 'zustand';
import {api, type SimulationStatus} from '../services/api';

interface JobInfo {
  id: string;
  state: string;
  progress: number;
  name?: string;
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
  backendUptime: number;
  connected: boolean;
  running: boolean;
  jobs: JobInfo[];
  logs: LogEntry[];
  error: string | null;

  checkHealth: () => Promise<void>;
  checkStatus: () => Promise<void>;
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
  backendUptime: 0,
  connected: false,
  running: false,
  jobs: [],
  logs: [initialLog],
  error: null,

  checkHealth: async () => {
    try {
      const response = await api.health();
      set({
        connected: true,
        backendStatus: 'running',
        backendUptime: (response.uptime as number) || 0,
        error: null,
      });
      get().addLog('info', 'Backend health check OK');
    } catch (err) {
      set({
        connected: false,
        backendStatus: 'stopped',
        backendUptime: 0,
      });
    }
  },

  checkStatus: async () => {
    try {
      const status: SimulationStatus = await api.getStatus();
      set({
        connected: true,
        running: status.running,
        backendUptime: status.uptime,
        jobs: (status.jobs || []) as JobInfo[],
        error: null,
      });
    } catch {
      set({connected: false});
    }
  },

  startSimulation: async (config) => {
    try {
      get().addLog('info', 'Starting simulation...');
      const response = await api.startSimulation(config);
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

  clearLogs: () => {
    set({logs: []});
  },

  reset: () => {
    set({
      backendStatus: 'stopped',
      backendPid: null,
      backendUptime: 0,
      connected: false,
      running: false,
      jobs: [],
      error: null,
    });
  },
}));

export type {JobInfo, LogEntry};
