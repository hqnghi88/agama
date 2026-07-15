import React, {useEffect, useRef, useState} from 'react';
import {View, TouchableOpacity, Text, StyleSheet, ScrollView} from 'react-native';
import {useSimulationStore} from '../store/useSimulationStore';
import {useResponsive} from '../hooks/useResponsive';

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
  const {s} = useResponsive();

  const [modelOpen, setModelOpen] = useState(false);
  const [expOpen, setExpOpen] = useState(false);
  const wasConnected = useRef(false);
  const wasBackendRunning = useRef(false);

  useEffect(() => {
    fetchModels();
  }, [fetchModels]);

  useEffect(() => {
    if (connected && !wasConnected.current && !modelsFetched) {
      fetchModels();
    }
    wasConnected.current = connected;
  }, [connected, fetchModels, modelsFetched]);

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
    <View style={{paddingVertical: s(8)}}>
      <Text style={{color: '#64748b', fontSize: s(10), fontWeight: '700', fontFamily: 'monospace', letterSpacing: 1, marginBottom: s(4)}}>MODEL</Text>
      <TouchableOpacity
        style={{
          flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between',
          backgroundColor: '#0f172a', borderRadius: s(8), paddingHorizontal: s(12), paddingVertical: s(10),
          marginBottom: s(8), borderWidth: 1, borderColor: modelOpen ? '#3b82f6' : '#334155',
        }}
        onPress={() => { setModelOpen(!modelOpen); setExpOpen(false); }}
        disabled={running || loadingModels}>
        <Text style={{color: selectedModel ? '#e2e8f0' : '#64748b', fontSize: s(13), fontFamily: 'monospace', flex: 1}}>
          {selectedModel ? selectedModel.name : (loadingModels ? 'Loading...' : 'Select a model...')}
        </Text>
        <Text style={{color: '#64748b', fontSize: s(10), marginLeft: s(8)}}>{modelOpen ? '▲' : '▼'}</Text>
      </TouchableOpacity>

      {modelOpen && models.length > 0 && (
        <View style={{backgroundColor: '#0f172a', borderRadius: s(8), borderWidth: 1, borderColor: '#334155', marginBottom: s(8), maxHeight: s(200)}}>
          <ScrollView style={{maxHeight: s(200)}} nestedScrollEnabled>
            {models.map((model, idx) => (
              <TouchableOpacity
                key={`${model.path}-${idx}`}
                style={{
                  paddingHorizontal: s(12), paddingVertical: s(10),
                  borderBottomWidth: 1, borderBottomColor: '#1e293b',
                  backgroundColor: selectedModel?.path === model.path ? '#1e3a5f' : undefined,
                }}
                onPress={() => { setSelectedModel(model); setModelOpen(false); }}>
                <Text style={{color: selectedModel?.path === model.path ? '#60a5fa' : '#cbd5e1', fontSize: s(12), fontFamily: 'monospace', fontWeight: selectedModel?.path === model.path ? '700' : '400'}}>
                  {model.name}
                </Text>
                <Text style={{color: '#475569', fontSize: s(10), fontFamily: 'monospace', marginTop: s(2)}}>{model.category}</Text>
              </TouchableOpacity>
            ))}
          </ScrollView>
        </View>
      )}

      {modelOpen && models.length === 0 && !loadingModels && (
        <View style={{backgroundColor: '#0f172a', borderRadius: s(8), borderWidth: 1, borderColor: '#334155', marginBottom: s(8)}}>
          <Text style={{color: '#64748b', fontSize: s(12), fontFamily: 'monospace', padding: s(12), textAlign: 'center'}}>No models found</Text>
          <TouchableOpacity style={{paddingVertical: s(10), paddingHorizontal: s(16), alignItems: 'center', justifyContent: 'center', borderTopWidth: 1, borderTopColor: '#1e293b'}} onPress={() => fetchModels()}>
            <Text style={{color: '#3b82f6', fontSize: s(12), fontFamily: 'monospace', fontWeight: '700'}}>RETRY</Text>
          </TouchableOpacity>
        </View>
      )}

      {selectedModel && selectedModel.experiments && selectedModel.experiments.length > 0 && (
        <>
          <Text style={{color: '#64748b', fontSize: s(10), fontWeight: '700', fontFamily: 'monospace', letterSpacing: 1, marginBottom: s(4), marginTop: s(8)}}>EXPERIMENT</Text>
          <TouchableOpacity
            style={{
              flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between',
              backgroundColor: '#0f172a', borderRadius: s(8), paddingHorizontal: s(12), paddingVertical: s(10),
              marginBottom: s(8), borderWidth: 1, borderColor: expOpen ? '#3b82f6' : '#334155',
            }}
            onPress={() => { setExpOpen(!expOpen); setModelOpen(false); }}
            disabled={running}>
            <Text style={{color: selectedExperiment ? '#e2e8f0' : '#64748b', fontSize: s(13), fontFamily: 'monospace', flex: 1}}>
              {selectedExperiment || 'Select an experiment...'}
            </Text>
            <Text style={{color: '#64748b', fontSize: s(10), marginLeft: s(8)}}>{expOpen ? '▲' : '▼'}</Text>
          </TouchableOpacity>

          {expOpen && (
            <View style={{backgroundColor: '#0f172a', borderRadius: s(8), borderWidth: 1, borderColor: '#334155', marginBottom: s(8), maxHeight: s(200)}}>
              <ScrollView style={{maxHeight: s(200)}} nestedScrollEnabled>
                {selectedModel.experiments.map((exp, idx) => (
                  <TouchableOpacity
                    key={`${exp}-${idx}`}
                    style={{
                      paddingHorizontal: s(12), paddingVertical: s(10),
                      borderBottomWidth: 1, borderBottomColor: '#1e293b',
                      backgroundColor: selectedExperiment === exp ? '#1e3a5f' : undefined,
                    }}
                    onPress={() => { setSelectedExperiment(exp); setExpOpen(false); }}>
                    <Text style={{color: selectedExperiment === exp ? '#60a5fa' : '#cbd5e1', fontSize: s(12), fontFamily: 'monospace', fontWeight: selectedExperiment === exp ? '700' : '400'}}>
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
        <Text style={{color: '#ef4444', fontSize: s(11), fontFamily: 'monospace', marginBottom: s(8)}}>No experiments found in this model</Text>
      )}

      <View style={{flexDirection: 'row', gap: s(8)}}>
        <TouchableOpacity
          style={{
            flex: 1, paddingVertical: s(12), borderRadius: s(8), alignItems: 'center', justifyContent: 'center',
            backgroundColor: '#15803d', opacity: canStart ? 1 : 0.4,
          }}
          onPress={handleStart}
          disabled={!canStart}>
          <Text style={{color: '#f8fafc', fontSize: s(13), fontWeight: '700', fontFamily: 'monospace', letterSpacing: 1}}>{running ? 'RUNNING...' : 'START'}</Text>
        </TouchableOpacity>
        <TouchableOpacity
          style={{
            flex: 1, paddingVertical: s(12), borderRadius: s(8), alignItems: 'center', justifyContent: 'center',
            backgroundColor: '#b91c1c', opacity: running ? 1 : 0.4,
          }}
          onPress={handleStop}
          disabled={!running}>
          <Text style={{color: '#f8fafc', fontSize: s(13), fontWeight: '700', fontFamily: 'monospace', letterSpacing: 1}}>STOP</Text>
        </TouchableOpacity>
        <TouchableOpacity
          style={{
            flex: 0.5, paddingVertical: s(12), borderRadius: s(8), alignItems: 'center', justifyContent: 'center',
            backgroundColor: '#334155',
          }}
          onPress={() => {
            const count = logs.length;
            useSimulationStore.getState().clearLogs();
            addLog('info', `Cleared ${count} log entries`);
          }}>
          <Text style={{color: '#f8fafc', fontSize: s(13), fontWeight: '700', fontFamily: 'monospace', letterSpacing: 1}}>CLEAR</Text>
        </TouchableOpacity>
      </View>
    </View>
  );
};

export default SimulationControls;
