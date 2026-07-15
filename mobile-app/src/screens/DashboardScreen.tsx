import React, {useCallback, useEffect, useRef, useState} from 'react';
import {
  View,
  Text,
  ScrollView,
  TouchableOpacity,
  Image,
  StyleSheet,
  NativeModules,
} from 'react-native';
import {useBackendHealth} from '../hooks/useBackendHealth';
import {useSimulationStore} from '../store/useSimulationStore';
import {api} from '../services/api';
import {useResponsive} from '../hooks/useResponsive';
import StatusIndicator from '../components/StatusIndicator';
import SimulationControls from '../components/SimulationControls';
import LogViewer from '../components/LogViewer';

const {SimulationModule} = NativeModules;

interface DashboardScreenProps {
  onOpenVnc?: () => void;
}

const DashboardScreen: React.FC<DashboardScreenProps> = ({onOpenVnc}) => {
  useBackendHealth();
  const {s, width} = useResponsive();

  const connected = useSimulationStore(s => s.connected);
  const backendStatus = useSimulationStore(s => s.backendStatus);
  const backendProgress = useSimulationStore(s => s.backendProgress);
  const backendUptime = useSimulationStore(s => s.backendUptime);
  const error = useSimulationStore(s => s.error);
  const running = useSimulationStore(s => s.running);
  const jobs = useSimulationStore(s => s.jobs);
  const logs = useSimulationStore(s => s.logs);
  const checkHealth = useSimulationStore(s => s.checkHealth);
  const checkStatus = useSimulationStore(s => s.checkStatus);
  const addLog = useSimulationStore(s => s.addLog);

  const [frameTs, setFrameTs] = useState(0);

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
    const sec = Math.floor(ms / 1000);
    const m = Math.floor(sec / 60);
    const h = Math.floor(m / 60);
    if (h > 0) return `${h}h ${m % 60}m`;
    if (m > 0) return `${m}m ${sec % 60}s`;
    return `${sec}s`;
  }, []);

  const [restarting, setRestarting] = useState(false);

  const handleRestartBackend = useCallback(async () => {
    if (restarting) return;
    setRestarting(true);
    addLog('info', 'Restarting backend...');
    try {
      await SimulationModule?.stopBackend();
      await new Promise(r => setTimeout(r, 2000));
      await SimulationModule?.startBackend();
      await new Promise(r => setTimeout(r, 3000));
      await checkHealth();
    } catch (e) {
      addLog('error', `Restart failed: ${(e as Error).message}`);
    }
    setRestarting(false);
  }, [restarting, addLog, checkHealth]);

  const backendColor = connected
    ? 'ok'
    : backendStatus === 'starting'
      ? 'warning'
      : backendStatus === 'error'
        ? 'error'
        : 'inactive';

  const pad = s(16);
  const logHeight = Math.max(s(160), Math.round(width * 0.22));
  const simFrameH = Math.max(s(160), Math.round(width * 0.25));

  return (
    <ScrollView style={styles.container} contentContainerStyle={{padding: pad, paddingBottom: s(40)}}>
      <Text style={{color: '#f8fafc', fontSize: s(28), fontWeight: '800', fontFamily: 'monospace'}}>GAMA Mobile</Text>
      <Text style={{color: '#64748b', fontSize: s(14), fontFamily: 'monospace', marginBottom: s(20)}}>Simulation Backend</Text>

      <View style={styles.card}>
        <Text style={{color: '#e2e8f0', fontSize: s(14), fontWeight: '700', fontFamily: 'monospace', marginBottom: s(8)}}>System Status</Text>
        <StatusIndicator
          label="Backend"
          status={backendColor}
          value={
            connected
              ? `Up ${formatUptime(backendUptime)}`
              : backendStatus === 'starting'
                ? (backendProgress || 'Starting...')
                : backendStatus === 'error'
                  ? (error && error.length < 40 ? error : 'Error')
                  : 'Offline'
          }
        />
        {!connected && SimulationModule && (
          <TouchableOpacity
            style={{backgroundColor: '#334155', borderRadius: s(8), padding: s(10), alignItems: 'center', marginTop: s(8)}}
            onPress={handleRestartBackend}
            disabled={restarting}>
            <Text style={{color: '#f8fafc', fontSize: s(11), fontWeight: '700', fontFamily: 'monospace', letterSpacing: 1}}>
              {restarting ? 'RESTARTING...' : 'RESTART BACKEND'}
            </Text>
          </TouchableOpacity>
        )}
        {connected && onOpenVnc && (
          <TouchableOpacity style={{backgroundColor: '#7c3aed', borderRadius: s(8), padding: s(12), alignItems: 'center', marginTop: s(8)}} onPress={onOpenVnc}>
            <Text style={{color: '#f8fafc', fontSize: s(12), fontWeight: '800', fontFamily: 'monospace', letterSpacing: 1}}>OPEN VNC VIEWER</Text>
          </TouchableOpacity>
        )}
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
        <Text style={{color: '#e2e8f0', fontSize: s(14), fontWeight: '700', fontFamily: 'monospace', marginBottom: s(8)}}>Controls</Text>
        <SimulationControls />
      </View>

      {jobs.length > 0 && (
        <View style={styles.card}>
          <Text style={{color: '#e2e8f0', fontSize: s(14), fontWeight: '700', fontFamily: 'monospace', marginBottom: s(8)}}>
            {jobs.some(j => j.state === 'running' || j.state === 'starting')
              ? 'Running Simulations'
              : 'Simulation Results'
            }
          </Text>
          {jobs.map(job => {
            const frameUrl = api.getFrameUrl(job.id, frameTs);
            return (
              <View key={job.id} style={{marginBottom: s(8)}}>
                <View style={{flexDirection: 'row', alignItems: 'center', paddingVertical: s(4)}}>
                  <Text style={{color: '#94a3b8', fontSize: s(12), fontFamily: 'monospace', flex: 1}}>{job.id}</Text>
                  <Text style={[
                    {color: '#fbbf24', fontSize: s(12), fontFamily: 'monospace', width: s(80)},
                    job.state === 'completed' && {color: '#22c55e'},
                    job.state === 'stopped' && {color: '#ef4444'},
                  ]}>
                    {job.state === 'completed' ? '✓ Complete' : job.state}
                  </Text>
                  <Text style={[
                    {color: '#e2e8f0', fontSize: s(12), fontFamily: 'monospace', width: s(40), textAlign: 'right' as const},
                    job.state === 'completed' && {color: '#22c55e'},
                  ]}>{job.progress}%</Text>
                </View>
                {job.model || job.experiment ? (
                  <Text style={{color: '#60a5fa', fontSize: s(10), fontFamily: 'monospace', marginTop: s(2)}}>
                    {[String(job.model ?? ''), String(job.experiment ?? '')].filter(Boolean).join(' # ')}
                  </Text>
                ) : null}
                <View style={{height: s(6), backgroundColor: '#334155', borderRadius: s(3), overflow: 'hidden', marginTop: s(2)}}>
                  <View style={[
                    {height: s(6), backgroundColor: '#3b82f6', borderRadius: s(3), width: `${job.progress}%` as unknown as number},
                    job.state === 'completed' && {backgroundColor: '#22c55e'},
                    job.state === 'stopped' && {backgroundColor: '#ef4444'},
                  ]} />
                </View>
                {job.has_frame && (
                  <View style={{marginTop: s(8), backgroundColor: '#0f172a', borderRadius: s(8), overflow: 'hidden', alignItems: 'center', justifyContent: 'center', minHeight: s(120)}}>
                    <Image
                      source={{uri: frameUrl}}
                      style={{width: '100%', height: simFrameH}}
                      resizeMode="contain"
                    />
                  </View>
                )}
                {job.current_step != null && job.state !== 'completed' && (
                  <Text style={{color: '#64748b', fontSize: s(10), fontFamily: 'monospace', marginTop: s(2)}}>Step {job.current_step}/{job.steps || 100}</Text>
                )}
                {job.state === 'completed' && (
                  <Text style={{color: '#64748b', fontSize: s(11), fontFamily: 'monospace', marginTop: s(4), fontStyle: 'italic'}}>Simulation finished successfully</Text>
                )}
                {job.state === 'stopped' && (
                  <Text style={{color: '#64748b', fontSize: s(11), fontFamily: 'monospace', marginTop: s(4), fontStyle: 'italic'}}>Simulation stopped by user</Text>
                )}
                {!job.has_frame && job.state === 'running' && (
                  <Text style={{color: '#64748b', fontSize: s(10), fontFamily: 'monospace', marginTop: s(2)}}>Waiting for display frame...</Text>
                )}
                {job.has_frame && (
                  <Text style={{color: '#64748b', fontSize: s(10), fontFamily: 'monospace', marginTop: s(2)}}>Live display — refreshes every 1.5s</Text>
                )}
              </View>
            );
          })}
          <Text style={{color: '#475569', fontSize: s(11), fontFamily: 'monospace', marginTop: s(8), textAlign: 'center'}}>
            {jobs.filter(j => j.state === 'completed').length} completed,{' '}
            {jobs.filter(j => j.state === 'running' || j.state === 'starting').length} active
          </Text>
        </View>
      )}

      <View style={styles.card}>
        <View style={styles.logHeader}>
          <Text style={{color: '#e2e8f0', fontSize: s(14), fontWeight: '700', fontFamily: 'monospace', marginBottom: s(8)}}>Logs</Text>
          <TouchableOpacity
            onPress={() => {
              addLog('info', 'Refreshed connection');
              checkHealth();
            }}
            style={{paddingHorizontal: s(8), paddingVertical: s(4)}}>
            <Text style={{color: '#3b82f6', fontSize: s(11), fontFamily: 'monospace', fontWeight: '700'}}>REFRESH</Text>
          </TouchableOpacity>
        </View>
        <View style={{height: logHeight}}>
          <LogViewer />
        </View>
      </View>

      <View style={{alignItems: 'center', paddingTop: s(8)}}>
        <Text style={{color: '#475569', fontSize: s(11), fontFamily: 'monospace'}}>v0.1.0 | ARM64 | PRoot</Text>
      </View>
    </ScrollView>
  );
};

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#0f172a',
  },
  card: {
    backgroundColor: '#1e293b',
    borderRadius: 12,
    padding: 16,
    marginBottom: 12,
  },
  logHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
  },
});

export default DashboardScreen;
