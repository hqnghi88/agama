import {useEffect, useRef, useCallback} from 'react';
import {useSimulationStore} from '../store/useSimulationStore';

const HEALTH_CHECK_INTERVAL = 5000;
const INITIAL_RETRY_INTERVAL = 2000;

export function useBackendHealth() {
  const checkHealth = useSimulationStore(s => s.checkHealth);
  const connected = useSimulationStore(s => s.connected);
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
        // Backend not ready yet
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
  }, [checkHealth, startPolling, stopPolling]);

  useEffect(() => {
    if (!connected && !intervalRef.current) {
      // Will be picked up by the existing effect
    }
  }, [connected]);

  return {connected, startPolling, stopPolling};
}
