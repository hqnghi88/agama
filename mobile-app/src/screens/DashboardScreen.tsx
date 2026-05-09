import React, {useCallback} from 'react';
import {
  View,
  Text,
  ScrollView,
  TouchableOpacity,
  StyleSheet,
} from 'react-native';
import {useBackendHealth} from '../hooks/useBackendHealth';
import {useSimulationStore} from '../store/useSimulationStore';
import StatusIndicator from '../components/StatusIndicator';
import SimulationControls from '../components/SimulationControls';
import LogViewer from '../components/LogViewer';

const DashboardScreen: React.FC = () => {
  useBackendHealth();

  const connected = useSimulationStore(s => s.connected);
  const backendStatus = useSimulationStore(s => s.backendStatus);
  const backendUptime = useSimulationStore(s => s.backendUptime);
  const running = useSimulationStore(s => s.running);
  const jobs = useSimulationStore(s => s.jobs);
  const logs = useSimulationStore(s => s.logs);
  const checkHealth = useSimulationStore(s => s.checkHealth);
  const addLog = useSimulationStore(s => s.addLog);

  const formatUptime = useCallback((ms: number): string => {
    const s = Math.floor(ms / 1000);
    const m = Math.floor(s / 60);
    const h = Math.floor(m / 60);
    if (h > 0) return `${h}h ${m % 60}m`;
    if (m > 0) return `${m}m ${s % 60}s`;
    return `${s}s`;
  }, []);

  const backendColor = connected
    ? 'ok'
    : backendStatus === 'starting'
      ? 'warning'
      : backendStatus === 'error'
        ? 'error'
        : 'inactive';

  return (
    <ScrollView style={styles.container} contentContainerStyle={styles.content}>
      <Text style={styles.title}>GAMA Mobile</Text>
      <Text style={styles.subtitle}>Simulation Backend</Text>

      <View style={styles.card}>
        <Text style={styles.cardTitle}>System Status</Text>
        <StatusIndicator
          label="Backend"
          status={backendColor}
          value={
            connected
              ? `Up ${formatUptime(backendUptime)}`
              : backendStatus === 'starting'
                ? 'Starting...'
                : 'Offline'
          }
        />
        <StatusIndicator
          label="Simulation"
          status={running ? 'ok' : 'inactive'}
          value={running ? 'Active' : 'Idle'}
        />
        <StatusIndicator
          label="Jobs"
          status={jobs.length > 0 ? 'ok' : 'inactive'}
          value={String(jobs.length)}
        />
        <StatusIndicator
          label="Logs"
          status="ok"
          value={String(logs.length)}
        />
      </View>

      <View style={styles.card}>
        <Text style={styles.cardTitle}>Controls</Text>
        <SimulationControls />
      </View>

      {jobs.length > 0 && (
        <View style={styles.card}>
          <Text style={styles.cardTitle}>Active Jobs</Text>
          {jobs.map(job => (
            <View key={job.id} style={styles.jobRow}>
              <Text style={styles.jobId}>{job.id}</Text>
              <Text style={styles.jobState}>{job.state}</Text>
              <Text style={styles.jobProgress}>{job.progress}%</Text>
            </View>
          ))}
        </View>
      )}

      <View style={styles.card}>
        <View style={styles.logHeader}>
          <Text style={styles.cardTitle}>Logs</Text>
          <TouchableOpacity
            onPress={() => {
              addLog('info', 'Refreshed connection');
              checkHealth();
            }}
            style={styles.refreshButton}>
            <Text style={styles.refreshText}>REFRESH</Text>
          </TouchableOpacity>
        </View>
        <View style={styles.logContainer}>
          <LogViewer />
        </View>
      </View>

      <View style={styles.footer}>
        <Text style={styles.footerText}>v0.1.0 | ARM64 | PRoot</Text>
      </View>
    </ScrollView>
  );
};

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#0f172a',
  },
  content: {
    padding: 16,
    paddingBottom: 40,
  },
  title: {
    color: '#f8fafc',
    fontSize: 28,
    fontWeight: '800',
    fontFamily: 'monospace',
  },
  subtitle: {
    color: '#64748b',
    fontSize: 14,
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
  logHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
  },
  refreshButton: {
    paddingHorizontal: 8,
    paddingVertical: 4,
  },
  refreshText: {
    color: '#3b82f6',
    fontSize: 11,
    fontFamily: 'monospace',
    fontWeight: '700',
  },
  logContainer: {
    height: 200,
  },
  jobRow: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingVertical: 4,
  },
  jobId: {
    color: '#94a3b8',
    fontSize: 12,
    fontFamily: 'monospace',
    flex: 1,
  },
  jobState: {
    color: '#22c55e',
    fontSize: 12,
    fontFamily: 'monospace',
    width: 60,
  },
  jobProgress: {
    color: '#e2e8f0',
    fontSize: 12,
    fontFamily: 'monospace',
    width: 40,
    textAlign: 'right',
  },
  footer: {
    alignItems: 'center',
    paddingTop: 8,
  },
  footerText: {
    color: '#475569',
    fontSize: 11,
    fontFamily: 'monospace',
  },
});

export default DashboardScreen;
