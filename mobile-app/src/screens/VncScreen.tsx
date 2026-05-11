import React, {useState, useEffect, useRef, useCallback} from 'react';
import {View, Text, StyleSheet, DeviceEventEmitter, requireNativeComponent, BackHandler, TouchableOpacity, NativeModules} from 'react-native';

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
  const connectedRef = useRef(false);
  const listenerRef = useRef<any>(null);

  const toggleKeyboard = useCallback(() => {
    NativeModules.SimulationModule.toggleKeyboard();
  }, []);

  useEffect(() => {
    listenerRef.current = DeviceEventEmitter.addListener('VncStateChange', (event: {state: string}) => {
      switch (event.state) {
        case 'connected':
          connectedRef.current = true;
          setVncState('connected');
          break;
        case 'error':
          setVncState('error');
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
  }, []);

  useEffect(() => {
    const backHandler = BackHandler.addEventListener('hardwareBackPress', () => {
      onBack();
      return true;
    });
    return () => backHandler.remove();
  }, [onBack]);

  return (
    <View style={styles.container}>
      <NativeVncView style={styles.vncView} />
      {vncState !== 'connected' && (
        <View style={StyleSheet.absoluteFill} pointerEvents="none">
          <View style={styles.center}>
            <Text style={styles.loadingText}>
              {vncState === 'error' ? 'Connection failed' : 'Connecting to VNC...'}
            </Text>
          </View>
        </View>
      )}
      <TouchableOpacity style={styles.kbdButton} onPress={toggleKeyboard} activeOpacity={0.6}>
        <View style={styles.kbdInner}>
          <Text style={styles.kbdIcon}>⌨</Text>
        </View>
      </TouchableOpacity>
    </View>
  );
};

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#0f172a',
  },
  center: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
  },
  loadingText: {
    color: '#64748b',
    fontSize: 13,
    fontFamily: 'monospace',
  },
  vncView: {
    flex: 1,
    backgroundColor: '#000000',
  },
  kbdButton: {
    position: 'absolute',
    bottom: 28,
    right: 28,
    width: 52,
    height: 52,
    borderRadius: 26,
    backgroundColor: 'rgba(30, 41, 59, 0.85)',
    borderWidth: 1,
    borderColor: 'rgba(148, 163, 184, 0.3)',
    elevation: 8,
    zIndex: 9999,
    shadowColor: '#000',
    shadowOffset: {width: 0, height: 4},
    shadowOpacity: 0.3,
    shadowRadius: 6,
  },
  kbdInner: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
  },
  kbdIcon: {
    color: '#e2e8f0',
    fontSize: 22,
  },
});

export default VncScreen;
