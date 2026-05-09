import {useEffect, useRef, useCallback} from 'react';
import {useSimulationStore} from '../store/useSimulationStore';

const HEALTH_CHECK_INTERVAL = 5000;
const INITIAL_RETRY_INTERVAL = 1000;
const MAX_INITIAL_RETRIES = 30;

export function useBackendHealth() {
  const checkHealth = useSimulationStore(s => s.checkHealth);
  const connected = useSimulationStore(s => s.connected);
  const intervalRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const retryCountRef = useRef(0);

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

    const initialPoll = async () => {
      while (retryCountRef.current < MAX_INITIAL_RETRIES && mounted) {
        try {
          await checkHealth();
          if (mounted && useSimulationStore.getState().connected) {
            startPolling();
            return;
          }
        } catch {
          // Backend not ready yet
        }
        retryCountRef.current++;
        await new Promise(r => setTimeout(r, INITIAL_RETRY_INTERVAL));
      }
    };

    initialPoll();

    return () => {
      mounted = false;
      stopPolling();
    };
  }, [checkHealth, startPolling, stopPolling]);

  useEffect(() => {
    if (!connected) {
      retryCountRef.current = 0;
    }
  }, [connected]);

  return {connected, startPolling, stopPolling};
}
