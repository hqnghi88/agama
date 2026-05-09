import React from 'react';
import {View, TouchableOpacity, Text, StyleSheet} from 'react-native';
import {useSimulationStore} from '../store/useSimulationStore';

const SimulationControls: React.FC = () => {
  const connected = useSimulationStore(s => s.connected);
  const running = useSimulationStore(s => s.running);
  const startSimulation = useSimulationStore(s => s.startSimulation);
  const stopSimulation = useSimulationStore(s => s.stopSimulation);
  const logs = useSimulationStore(s => s.logs);
  const addLog = useSimulationStore(s => s.addLog);

  const handleStart = async () => {
    const jobId = await startSimulation({steps: 100});
    if (jobId) {
      addLog('info', `Job ${jobId} created`);
    }
  };

  const handleStop = async () => {
    await stopSimulation();
  };

  const handleStopAll = async () => {
    await stopSimulation();
  };

  return (
    <View style={styles.container}>
      <View style={styles.row}>
        <TouchableOpacity
          style={[
            styles.button,
            styles.startButton,
            (!connected || running) && styles.buttonDisabled,
          ]}
          onPress={handleStart}
          disabled={!connected || running}>
          <Text style={styles.buttonText}>
            {running ? 'RUNNING...' : 'START'}
          </Text>
        </TouchableOpacity>

        <TouchableOpacity
          style={[
            styles.button,
            styles.stopButton,
            !running && styles.buttonDisabled,
          ]}
          onPress={handleStop}
          disabled={!running}>
          <Text style={styles.buttonText}>STOP</Text>
        </TouchableOpacity>

        <TouchableOpacity
          style={[styles.button, styles.clearButton]}
          onPress={() => {
            const count = logs.length;
            useSimulationStore.getState().clearLogs();
            addLog('info', `Cleared ${count} log entries`);
          }}>
          <Text style={styles.buttonText}>CLEAR</Text>
        </TouchableOpacity>
      </View>
    </View>
  );
};

const styles = StyleSheet.create({
  container: {
    paddingVertical: 8,
  },
  row: {
    flexDirection: 'row',
    gap: 8,
  },
  button: {
    flex: 1,
    paddingVertical: 12,
    borderRadius: 8,
    alignItems: 'center',
    justifyContent: 'center',
  },
  startButton: {
    backgroundColor: '#15803d',
  },
  stopButton: {
    backgroundColor: '#b91c1c',
  },
  clearButton: {
    backgroundColor: '#334155',
    flex: 0.5,
  },
  buttonDisabled: {
    opacity: 0.4,
  },
  buttonText: {
    color: '#f8fafc',
    fontSize: 13,
    fontWeight: '700',
    fontFamily: 'monospace',
    letterSpacing: 1,
  },
});

export default SimulationControls;
