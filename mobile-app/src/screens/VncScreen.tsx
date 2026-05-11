import React, {useState, useCallback, useEffect, useRef} from 'react';
import {View, Text, StyleSheet, TouchableOpacity, DeviceEventEmitter, requireNativeComponent} from 'react-native';
import {useSimulationStore} from '../store/useSimulationStore';

interface VncScreenProps {
  onBack: () => void;
}

type VncState = 'connecting' | 'connected' | 'error';

interface NativeVncViewProps {
  style?: object;
}

const NativeVncView = requireNativeComponent<NativeVncViewProps>('VncView');

const VncScreen: React.FC<VncScreenProps> = ({onBack}) => {
  const [vncState, setVncState] = useState<VncState>('connecting');
  const [errorMessage, setErrorMessage] = useState('');
  const addLog = useSimulationStore(s => s.addLog);
  const timeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const connectedRef = useRef(false);
  const listenerRef = useRef<any>(null);

  useEffect(() => {
    listenerRef.current = DeviceEventEmitter.addListener('VncStateChange', (event: {state: string}) => {
      addLog('info', 'VNC: ' + event.state);
      switch (event.state) {
        case 'connected':
          connectedRef.current = true;
          setVncState('connected');
          break;
        case 'error':
          setVncState('error');
          setErrorMessage('VNC connection failed');
          break;
        case 'disconnected':
          if (connectedRef.current) {
            connectedRef.current = false;
            setVncState('connecting');
          }
          break;
      }
    });

    return () => {
      if (listenerRef.current) listenerRef.current.remove();
    };
  }, [addLog]);

  useEffect(() => {
    return () => {
      if (timeoutRef.current) clearTimeout(timeoutRef.current);
    };
  }, []);

  useEffect(() => {
    if (vncState === 'connecting') {
      timeoutRef.current = setTimeout(() => {
        if (!connectedRef.current) {
          setVncState('error');
          setErrorMessage('Connection timed out after 30s');
          addLog('error', 'VNC connection timed out');
        }
      }, 30000);
    }
    return () => {
      if (timeoutRef.current) clearTimeout(timeoutRef.current);
    };
  }, [vncState, addLog]);

  const handleDisconnect = useCallback(() => {
    addLog('info', 'VNC viewer closed');
    onBack();
  }, [addLog, onBack]);

  if (vncState === 'error') {
    return (
      <View style={styles.container}>
        <View style={styles.toolbar}>
          <Text style={styles.title}>GAMA VNC</Text>
          <TouchableOpacity onPress={handleDisconnect} style={styles.backBtn}>
            <Text style={styles.backBtnText}>CLOSE</Text>
          </TouchableOpacity>
        </View>
        <View style={styles.errorContainer}>
          <Text style={styles.errorIcon}>⚠</Text>
          <Text style={styles.errorText}>VNC Connection Error</Text>
          <Text style={styles.errorDetail}>{errorMessage}</Text>
          <TouchableOpacity style={styles.retryBtn} onPress={handleDisconnect}>
            <Text style={styles.retryBtnText}>GO BACK</Text>
          </TouchableOpacity>
        </View>
      </View>
    );
  }

  return (
    <View style={styles.container}>
      <View style={styles.toolbar}>
        <Text style={styles.title}>GAMA VNC</Text>
        <View style={styles.statusRow}>
          <View style={[
            styles.statusDot,
            {backgroundColor: vncState === 'connected' ? '#22c55e' : '#f59e0b'},
          ]} />
          <Text style={styles.statusText}>
            {vncState === 'connected' ? 'Connected' : 'Connecting...'}
          </Text>
        </View>
        <TouchableOpacity onPress={handleDisconnect} style={styles.backBtn}>
          <Text style={styles.backBtnText}>DISCONNECT</Text>
        </TouchableOpacity>
      </View>
      <NativeVncView style={styles.vncView} />
    </View>
  );
};

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#0f172a',
  },
  toolbar: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: '#1e293b',
    paddingHorizontal: 12,
    paddingVertical: 8,
    borderBottomWidth: 1,
    borderBottomColor: '#334155',
  },
  title: {
    color: '#f8fafc',
    fontSize: 16,
    fontWeight: '700',
    fontFamily: 'monospace',
  },
  statusRow: {
    flexDirection: 'row',
    alignItems: 'center',
    marginLeft: 16,
    flex: 1,
  },
  statusDot: {
    width: 8,
    height: 8,
    borderRadius: 4,
    marginRight: 6,
  },
  statusText: {
    color: '#94a3b8',
    fontSize: 11,
    fontFamily: 'monospace',
  },
  backBtn: {
    backgroundColor: '#334155',
    borderRadius: 6,
    paddingHorizontal: 10,
    paddingVertical: 6,
  },
  backBtnText: {
    color: '#f8fafc',
    fontSize: 11,
    fontWeight: '700',
    fontFamily: 'monospace',
    letterSpacing: 0.5,
  },
  vncView: {
    flex: 1,
    backgroundColor: '#000000',
  },
  errorContainer: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    padding: 24,
  },
  errorIcon: {
    fontSize: 48,
    marginBottom: 16,
  },
  errorText: {
    color: '#f8fafc',
    fontSize: 18,
    fontWeight: '700',
    fontFamily: 'monospace',
    marginBottom: 8,
  },
  errorDetail: {
    color: '#64748b',
    fontSize: 13,
    fontFamily: 'monospace',
    marginBottom: 24,
  },
  retryBtn: {
    backgroundColor: '#3b82f6',
    borderRadius: 8,
    paddingHorizontal: 24,
    paddingVertical: 12,
  },
  retryBtnText: {
    color: '#f8fafc',
    fontSize: 13,
    fontWeight: '700',
    fontFamily: 'monospace',
  },
});

export default VncScreen;
