import React, {useEffect, useRef, useState} from 'react';
import {View, TouchableOpacity, Text, StyleSheet, ScrollView} from 'react-native';
import {useSimulationStore} from '../store/useSimulationStore';

const SimulationControls: React.FC = () => {
  const connected = useSimulationStore(s => s.connected);
  const running = useSimulationStore(s => s.running);
  const models = useSimulationStore(s => s.models);
  const selectedModel = useSimulationStore(s => s.selectedModel);
  const selectedExperiment = useSimulationStore(s => s.selectedExperiment);
  const loadingModels = useSimulationStore(s => s.loadingModels);
  const startSimulation = useSimulationStore(s => s.startSimulation);
  const stopSimulation = useSimulationStore(s => s.stopSimulation);
  const fetchModels = useSimulationStore(s => s.fetchModels);
  const setSelectedModel = useSimulationStore(s => s.setSelectedModel);
  const setSelectedExperiment = useSimulationStore(s => s.setSelectedExperiment);
  const logs = useSimulationStore(s => s.logs);
  const addLog = useSimulationStore(s => s.addLog);
  const modelsFetched = useSimulationStore(s => s.modelsFetched);
  const backendStatus = useSimulationStore(s => s.backendStatus);

  const [modelOpen, setModelOpen] = useState(false);
  const [expOpen, setExpOpen] = useState(false);
  const wasConnected = useRef(false);
  const wasBackendRunning = useRef(false);

  useEffect(() => {
    fetchModels();
  }, [fetchModels]);

  // Retry fetchModels when backend transitions from disconnected to connected
  useEffect(() => {
    if (connected && !wasConnected.current && !modelsFetched) {
      fetchModels();
    }
    wasConnected.current = connected;
  }, [connected, fetchModels, modelsFetched]);

  // Retry fetchModels when native backend transitions to running
  useEffect(() => {
    if (backendStatus === 'running' && !wasBackendRunning.current && !modelsFetched) {
      fetchModels();
    }
    wasBackendRunning.current = backendStatus === 'running';
  }, [backendStatus, fetchModels, modelsFetched]);

  const handleStart = async () => {
    if (!selectedModel) {
      addLog('warn', 'Select a model first');
      return;
    }
    const jobId = await startSimulation({steps: 100});
    if (jobId) {
      addLog('info', `Job ${jobId} created`);
    }
  };

  const handleStop = async () => {
    await stopSimulation();
  };

  const canStart = connected && !running && selectedModel && selectedExperiment;

  return (
    <View style={styles.container}>
      <Text style={styles.label}>MODEL</Text>
      <TouchableOpacity
        style={[styles.selector, modelOpen && styles.selectorOpen]}
        onPress={() => { setModelOpen(!modelOpen); setExpOpen(false); }}
        disabled={running || loadingModels}>
        <Text style={[styles.selectorText, !selectedModel && styles.placeholder]}>
          {selectedModel ? selectedModel.name : (loadingModels ? 'Loading...' : 'Select a model...')}
        </Text>
        <Text style={styles.chevron}>{modelOpen ? '▲' : '▼'}</Text>
      </TouchableOpacity>

      {modelOpen && models.length > 0 && (
        <View style={styles.dropdown}>
          <ScrollView style={styles.dropdownScroll} nestedScrollEnabled>
            {models.map((model, idx) => (
              <TouchableOpacity
                key={`${model.path}-${idx}`}
                style={[styles.dropdownItem, selectedModel?.path === model.path && styles.dropdownItemSelected]}
                onPress={() => { setSelectedModel(model); setModelOpen(false); }}>
                <Text style={[styles.dropdownItemText, selectedModel?.path === model.path && styles.dropdownItemTextSelected]}>
                  {model.name}
                </Text>
                <Text style={styles.dropdownItemCategory}>{model.category}</Text>
              </TouchableOpacity>
            ))}
          </ScrollView>
        </View>
      )}

      {modelOpen && models.length === 0 && !loadingModels && (
        <View style={styles.dropdown}>
          <Text style={styles.emptyText}>No models found</Text>
          <TouchableOpacity style={styles.retryButton} onPress={() => fetchModels()}>
            <Text style={styles.retryText}>RETRY</Text>
          </TouchableOpacity>
        </View>
      )}

      {selectedModel && selectedModel.experiments && selectedModel.experiments.length > 0 && (
        <>
          <Text style={[styles.label, {marginTop: 8}]}>EXPERIMENT</Text>
          <TouchableOpacity
            style={[styles.selector, expOpen && styles.selectorOpen]}
            onPress={() => { setExpOpen(!expOpen); setModelOpen(false); }}
            disabled={running}>
            <Text style={[styles.selectorText, !selectedExperiment && styles.placeholder]}>
              {selectedExperiment || 'Select an experiment...'}
            </Text>
            <Text style={styles.chevron}>{expOpen ? '▲' : '▼'}</Text>
          </TouchableOpacity>

          {expOpen && (
            <View style={styles.dropdown}>
              <ScrollView style={styles.dropdownScroll} nestedScrollEnabled>
                {selectedModel.experiments.map((exp, idx) => (
                  <TouchableOpacity
                    key={`${exp}-${idx}`}
                    style={[styles.dropdownItem, selectedExperiment === exp && styles.dropdownItemSelected]}
                    onPress={() => { setSelectedExperiment(exp); setExpOpen(false); }}>
                    <Text style={[styles.dropdownItemText, selectedExperiment === exp && styles.dropdownItemTextSelected]}>
                      {exp}
                    </Text>
                  </TouchableOpacity>
                ))}
              </ScrollView>
            </View>
          )}
        </>
      )}

      {selectedModel && (!selectedModel.experiments || selectedModel.experiments.length === 0) && (
        <Text style={styles.noExp}>No experiments found in this model</Text>
      )}

      <View style={styles.row}>
        <TouchableOpacity
          style={[styles.button, styles.startButton, !canStart && styles.buttonDisabled]}
          onPress={handleStart}
          disabled={!canStart}>
          <Text style={styles.buttonText}>{running ? 'RUNNING...' : 'START'}</Text>
        </TouchableOpacity>
        <TouchableOpacity
          style={[styles.button, styles.stopButton, !running && styles.buttonDisabled]}
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
  container: {paddingVertical: 8},
  label: {
    color: '#64748b', fontSize: 10, fontWeight: '700',
    fontFamily: 'monospace', letterSpacing: 1, marginBottom: 4,
  },
  selector: {
    flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between',
    backgroundColor: '#0f172a', borderRadius: 8, paddingHorizontal: 12, paddingVertical: 10,
    marginBottom: 8, borderWidth: 1, borderColor: '#334155',
  },
  selectorOpen: {borderColor: '#3b82f6'},
  selectorText: {color: '#e2e8f0', fontSize: 13, fontFamily: 'monospace', flex: 1},
  placeholder: {color: '#64748b'},
  chevron: {color: '#64748b', fontSize: 10, marginLeft: 8},
  dropdown: {
    backgroundColor: '#0f172a', borderRadius: 8, borderWidth: 1,
    borderColor: '#334155', marginBottom: 8, maxHeight: 200,
  },
  dropdownScroll: {maxHeight: 200},
  dropdownItem: {
    paddingHorizontal: 12, paddingVertical: 10,
    borderBottomWidth: 1, borderBottomColor: '#1e293b',
  },
  dropdownItemSelected: {backgroundColor: '#1e3a5f'},
  dropdownItemText: {color: '#cbd5e1', fontSize: 12, fontFamily: 'monospace'},
  dropdownItemTextSelected: {color: '#60a5fa', fontWeight: '700'},
  dropdownItemCategory: {color: '#475569', fontSize: 10, fontFamily: 'monospace', marginTop: 2},
  emptyText: {color: '#64748b', fontSize: 12, fontFamily: 'monospace', padding: 12, textAlign: 'center'},
  retryButton: {
    paddingVertical: 10, paddingHorizontal: 16,
    alignItems: 'center', justifyContent: 'center',
    borderTopWidth: 1, borderTopColor: '#1e293b',
  },
  retryText: {color: '#3b82f6', fontSize: 12, fontFamily: 'monospace', fontWeight: '700'},
  noExp: {color: '#ef4444', fontSize: 11, fontFamily: 'monospace', marginBottom: 8},
  row: {flexDirection: 'row', gap: 8},
  button: {flex: 1, paddingVertical: 12, borderRadius: 8, alignItems: 'center', justifyContent: 'center'},
  startButton: {backgroundColor: '#15803d'},
  stopButton: {backgroundColor: '#b91c1c'},
  clearButton: {backgroundColor: '#334155', flex: 0.5},
  buttonDisabled: {opacity: 0.4},
  buttonText: {color: '#f8fafc', fontSize: 13, fontWeight: '700', fontFamily: 'monospace', letterSpacing: 1},
});

export default SimulationControls;
