import React from 'react';
import {View, Text, TouchableOpacity, StyleSheet} from 'react-native';
import {NativeModules} from 'react-native';
import {useSimulationStore} from '../store/useSimulationStore';

const {SimulationModule} = NativeModules;

const SettingsScreen: React.FC = () => {
  const connected = useSimulationStore(s => s.connected);
  const backendPid = useSimulationStore(s => s.backendPid);
  const clearLogs = useSimulationStore(s => s.clearLogs);
  const checkHealth = useSimulationStore(s => s.checkHealth);

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

  return (
    <View style={styles.container}>
      <Text style={styles.title}>Settings</Text>

      <View style={styles.card}>
        <Text style={styles.cardTitle}>Backend</Text>
        <Text style={styles.infoText}>
          Status: {connected ? 'Connected' : 'Disconnected'}
        </Text>
        <Text style={styles.infoText}>PID: {backendPid ?? 'N/A'}</Text>
        <Text style={styles.infoText}>Port: 8080</Text>
      </View>

      <View style={styles.card}>
        <Text style={styles.cardTitle}>Actions</Text>
        <TouchableOpacity style={styles.button} onPress={handleRestart}>
          <Text style={styles.buttonText}>RESTART BACKEND</Text>
        </TouchableOpacity>
        <TouchableOpacity style={styles.button} onPress={clearLogs}>
          <Text style={styles.buttonText}>CLEAR LOGS</Text>
        </TouchableOpacity>
        <TouchableOpacity style={styles.button} onPress={checkHealth}>
          <Text style={styles.buttonText}>CHECK HEALTH</Text>
        </TouchableOpacity>
      </View>

      <View style={styles.card}>
        <Text style={styles.cardTitle}>About</Text>
        <Text style={styles.infoText}>GAMA Mobile v0.1.0</Text>
        <Text style={styles.infoText}>Architecture: ARM64</Text>
        <Text style={styles.infoText}>Runtime: PRoot + OpenJDK 17</Text>
        <Text style={styles.infoText}>Communication: localhost:8080</Text>
      </View>
    </View>
  );
};

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#0f172a',
    padding: 16,
  },
  title: {
    color: '#f8fafc',
    fontSize: 24,
    fontWeight: '800',
    fontFamily: 'monospace',
    marginBottom: 20,
  },
  card: {
    backgroundColor: '#1e293b',
    borderRadius: 12,
    padding: 16,
    marginBottom: 12,
  },
  cardTitle: {
    color: '#e2e8f0',
    fontSize: 14,
    fontWeight: '700',
    fontFamily: 'monospace',
    marginBottom: 8,
  },
  infoText: {
    color: '#94a3b8',
    fontSize: 12,
    fontFamily: 'monospace',
    marginVertical: 2,
  },
  button: {
    backgroundColor: '#334155',
    borderRadius: 8,
    padding: 12,
    alignItems: 'center',
    marginVertical: 4,
  },
  buttonText: {
    color: '#f8fafc',
    fontSize: 12,
    fontWeight: '700',
    fontFamily: 'monospace',
    letterSpacing: 1,
  },
});

export default SettingsScreen;
