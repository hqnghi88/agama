import React, {useCallback, useEffect, useRef, useState} from 'react';
import {
  View,
  Text,
  ScrollView,
  TouchableOpacity,
  Image,
  StyleSheet,
} from 'react-native';
import {useBackendHealth} from '../hooks/useBackendHealth';
import {useSimulationStore} from '../store/useSimulationStore';
import {api} from '../services/api';
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
  const checkStatus = useSimulationStore(s => s.checkStatus);
  const addLog = useSimulationStore(s => s.addLog);

  // Frame refresh counter (increment to force Image re-render)
  const [frameTs, setFrameTs] = useState(0);

  // Poll simulation status every 2s while running
  const statusInterval = useRef<ReturnType<typeof setInterval> | null>(null);
  useEffect(() => {
    if (running && !statusInterval.current) {
      statusInterval.current = setInterval(() => { checkStatus(); }, 2000);
    } else if (!running && statusInterval.current) {
      clearInterval(statusInterval.current);
      statusInterval.current = null;
    }
    return () => {
      if (statusInterval.current) {
        clearInterval(statusInterval.current);
        statusInterval.current = null;
      }
    };
  }, [running, checkStatus]);

  // Poll simulation frame every 1.5s while running
  const frameInterval = useRef<ReturnType<typeof setInterval> | null>(null);
  useEffect(() => {
    if (running && !frameInterval.current) {
      frameInterval.current = setInterval(() => {
        setFrameTs(Date.now());
      }, 1500);
    } else if (!running && frameInterval.current) {
      clearInterval(frameInterval.current);
      frameInterval.current = null;
    }
    return () => {
      if (frameInterval.current) {
        clearInterval(frameInterval.current);
        frameInterval.current = null;
      }
    };
  }, [running]);

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
          <Text style={styles.cardTitle}>
            {jobs.some(j => j.state === 'running' || j.state === 'starting')
              ? 'Running Simulations'
              : 'Simulation Results'
            }
          </Text>
          {jobs.map(job => {
            const frameUrl = api.getFrameUrl(job.id, frameTs);
            return (
            <View key={job.id} style={styles.jobBlock}>
              <View style={styles.jobRow}>
                <Text style={styles.jobId}>{job.id}</Text>
                <Text style={[
                  styles.jobState,
                  job.state === 'completed' && styles.completed,
                  job.state === 'stopped' && styles.stopped,
                ]}>
                  {job.state === 'completed' ? '✓ Complete' : job.state}
                </Text>
                <Text style={[
                  styles.jobProgress,
                  job.state === 'completed' && styles.completedText,
                ]}>{job.progress}%</Text>
              </View>
              <View style={styles.progressBarBg}>
                <View style={[
                  styles.progressBarFill,
                  {width: `${job.progress}%`},
                  job.state === 'completed' && styles.progressComplete,
                  job.state === 'stopped' && styles.progressStopped,
                ]} />
              </View>
              {job.has_frame && (
                <View style={styles.frameContainer}>
                  <Image
                    source={{uri: frameUrl}}
                    style={styles.simFrame}
                    resizeMode="contain"
                  />
                </View>
              )}
              {job.current_step != null && job.state !== 'completed' && (
                <Text style={styles.jobStep}>Step {job.current_step}/{job.steps || 100}</Text>
              )}
              {job.state === 'completed' && (
                <Text style={styles.jobResult}>Simulation finished successfully</Text>
              )}
              {job.state === 'stopped' && (
                <Text style={styles.jobResult}>Simulation stopped by user</Text>
              )}
              {!job.has_frame && job.state === 'running' && (
                <Text style={styles.jobStep}>Waiting for display frame...</Text>
              )}
              {job.has_frame && (
                <Text style={styles.jobStep}>Live display — refreshes every 1.5s</Text>
              )}
            </View>
            );
          })}
          <Text style={styles.jobsSummary}>
            {jobs.filter(j => j.state === 'completed').length} completed,{' '}
            {jobs.filter(j => j.state === 'running' || j.state === 'starting').length} active
          </Text>
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
  jobBlock: {
    marginBottom: 8,
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
    color: '#fbbf24',
    fontSize: 12,
    fontFamily: 'monospace',
    width: 80,
  },
  completed: {
    color: '#22c55e',
  },
  jobProgress: {
    color: '#e2e8f0',
    fontSize: 12,
    fontFamily: 'monospace',
    width: 40,
    textAlign: 'right',
  },
  progressBarBg: {
    height: 6,
    backgroundColor: '#334155',
    borderRadius: 3,
    overflow: 'hidden',
    marginTop: 2,
  },
  progressBarFill: {
    height: 6,
    backgroundColor: '#3b82f6',
    borderRadius: 3,
  },
  jobStep: {
    color: '#64748b',
    fontSize: 10,
    fontFamily: 'monospace',
    marginTop: 2,
  },
  stopped: {
    color: '#ef4444',
  },
  completedText: {
    color: '#22c55e',
  },
  progressComplete: {
    backgroundColor: '#22c55e',
  },
  progressStopped: {
    backgroundColor: '#ef4444',
  },
  frameContainer: {
    marginTop: 8,
    backgroundColor: '#0f172a',
    borderRadius: 8,
    overflow: 'hidden',
    alignItems: 'center',
    justifyContent: 'center',
    minHeight: 120,
  },
  simFrame: {
    width: '100%',
    height: 200,
  },
  jobResult: {
    color: '#64748b',
    fontSize: 11,
    fontFamily: 'monospace',
    marginTop: 4,
    fontStyle: 'italic',
  },
  jobsSummary: {
    color: '#475569',
    fontSize: 11,
    fontFamily: 'monospace',
    marginTop: 8,
    textAlign: 'center',
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
