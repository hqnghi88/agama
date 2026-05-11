import React, {useState, useEffect, useRef} from 'react';
import {View, Text, StyleSheet, DeviceEventEmitter, requireNativeComponent, BackHandler} from 'react-native';

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
});

export default VncScreen;
