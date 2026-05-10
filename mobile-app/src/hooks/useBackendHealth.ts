import {useEffect, useRef, useCallback} from 'react';
import {NativeModules} from 'react-native';
import {useSimulationStore} from '../store/useSimulationStore';

const {SimulationModule} = NativeModules;

const HEALTH_CHECK_INTERVAL = 5000;
const INITIAL_RETRY_INTERVAL = 2000;

interface NativeStatus {
  status: string;
  progress: string;
  pid: number;
  port: number;
}

async function checkNativeStatus(): Promise<NativeStatus | null> {
  try {
    if (!SimulationModule?.getStatus) return null;
    return await SimulationModule.getStatus();
  } catch {
    return null;
  }
}

export function useBackendHealth() {
  const checkHealth = useSimulationStore(s => s.checkHealth);
  const checkNativeStatusAction = useSimulationStore(s => s.checkNativeStatus);
  const connected = useSimulationStore(s => s.connected);
  const addLog = useSimulationStore(s => s.addLog);
  const intervalRef = useRef<ReturnType<typeof setInterval> | null>(null);

  const startPolling = useCallback(() => {
    if (intervalRef.current) clearInterval(intervalRef.current);
    intervalRef.current = setInterval(checkHealth, HEALTH_CHECK_INTERVAL);
  }, [checkHealth]);

  const stopPolling = useCallback(() => {
    if (intervalRef.current) {
      clearInterval(intervalRef.current);
      intervalRef.current = null;
    }
  }, []);

  useEffect(() => {
    let mounted = true;
    let timeoutId: ReturnType<typeof setTimeout>;

    const poll = async () => {
      if (!mounted) return;
      try {
        await checkHealth();
        if (mounted && useSimulationStore.getState().connected) {
          startPolling();
          return;
        }
      } catch {
        // HTTP health check failed
      }

      const native = await checkNativeStatus();
      if (mounted && native) {
        const {status, progress, pid} = native;
        const store = useSimulationStore.getState();
        checkNativeStatusAction(status, progress);
        if (!store.connected && status === 'running' && pid > 0) {
          addLog('warn', `Java backend says "${status}" (PID ${pid}) but HTTP unreachable from JS`);
        }
      }

      if (mounted) {
        timeoutId = setTimeout(poll, INITIAL_RETRY_INTERVAL);
      }
    };

    poll();

    return () => {
      mounted = false;
      clearTimeout(timeoutId);
      stopPolling();
    };
  }, [checkHealth, startPolling, stopPolling, addLog, checkNativeStatusAction]);

  return {connected, startPolling, stopPolling};
}
