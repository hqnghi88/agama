import React from 'react';
import {View, Text, TouchableOpacity, StyleSheet} from 'react-native';
import {NativeModules} from 'react-native';
import {useSimulationStore} from '../store/useSimulationStore';
import {useResponsive} from '../hooks/useResponsive';

const {SimulationModule} = NativeModules;

const SettingsScreen: React.FC = () => {
  const connected = useSimulationStore(s => s.connected);
  const backendPid = useSimulationStore(s => s.backendPid);
  const clearLogs = useSimulationStore(s => s.clearLogs);
  const checkHealth = useSimulationStore(s => s.checkHealth);
  const {s} = useResponsive();

  const handleRestart = async () => {
    try {
      await SimulationModule?.stopBackend();
      await new Promise(r => setTimeout(r, 2000));
      await SimulationModule?.startBackend();
      await new Promise(r => setTimeout(r, 3000));
      await checkHealth();
    } catch {
      // Handle restart failure
    }
  };

  const cardStyle = {backgroundColor: '#1e293b', borderRadius: s(12), padding: s(16), marginBottom: s(12)};

  return (
    <View style={{flex: 1, backgroundColor: '#0f172a', padding: s(16)}}>
      <Text style={{color: '#f8fafc', fontSize: s(24), fontWeight: '800', fontFamily: 'monospace', marginBottom: s(20)}}>Settings</Text>

      <View style={cardStyle}>
        <Text style={{color: '#e2e8f0', fontSize: s(14), fontWeight: '700', fontFamily: 'monospace', marginBottom: s(8)}}>Backend</Text>
        <Text style={{color: '#94a3b8', fontSize: s(12), fontFamily: 'monospace', marginVertical: s(2)}}>
          Status: {connected ? 'Connected' : 'Disconnected'}
        </Text>
        <Text style={{color: '#94a3b8', fontSize: s(12), fontFamily: 'monospace', marginVertical: s(2)}}>PID: {backendPid ?? 'N/A'}</Text>
        <Text style={{color: '#94a3b8', fontSize: s(12), fontFamily: 'monospace', marginVertical: s(2)}}>Port: 8080</Text>
      </View>

      <View style={cardStyle}>
        <Text style={{color: '#e2e8f0', fontSize: s(14), fontWeight: '700', fontFamily: 'monospace', marginBottom: s(8)}}>Actions</Text>
        <TouchableOpacity style={{backgroundColor: '#334155', borderRadius: s(8), padding: s(12), alignItems: 'center', marginVertical: s(4)}} onPress={handleRestart}>
          <Text style={{color: '#f8fafc', fontSize: s(12), fontWeight: '700', fontFamily: 'monospace', letterSpacing: 1}}>RESTART BACKEND</Text>
        </TouchableOpacity>
        <TouchableOpacity style={{backgroundColor: '#334155', borderRadius: s(8), padding: s(12), alignItems: 'center', marginVertical: s(4)}} onPress={clearLogs}>
          <Text style={{color: '#f8fafc', fontSize: s(12), fontWeight: '700', fontFamily: 'monospace', letterSpacing: 1}}>CLEAR LOGS</Text>
        </TouchableOpacity>
        <TouchableOpacity style={{backgroundColor: '#334155', borderRadius: s(8), padding: s(12), alignItems: 'center', marginVertical: s(4)}} onPress={checkHealth}>
          <Text style={{color: '#f8fafc', fontSize: s(12), fontWeight: '700', fontFamily: 'monospace', letterSpacing: 1}}>CHECK HEALTH</Text>
        </TouchableOpacity>
      </View>

      <View style={cardStyle}>
        <Text style={{color: '#e2e8f0', fontSize: s(14), fontWeight: '700', fontFamily: 'monospace', marginBottom: s(8)}}>About</Text>
        <Text style={{color: '#94a3b8', fontSize: s(12), fontFamily: 'monospace', marginVertical: s(2)}}>GAMA Mobile v0.1.0</Text>
        <Text style={{color: '#94a3b8', fontSize: s(12), fontFamily: 'monospace', marginVertical: s(2)}}>Architecture: ARM64</Text>
        <Text style={{color: '#94a3b8', fontSize: s(12), fontFamily: 'monospace', marginVertical: s(2)}}>Runtime: PRoot + OpenJDK 17</Text>
        <Text style={{color: '#94a3b8', fontSize: s(12), fontFamily: 'monospace', marginVertical: s(2)}}>Communication: localhost:8080</Text>
      </View>
    </View>
  );
};

export default SettingsScreen;
