import React, {useState, useEffect, useRef, useCallback} from 'react';
import {View, Text, StyleSheet, DeviceEventEmitter, requireNativeComponent, BackHandler, TouchableOpacity, NativeModules, Animated, Easing} from 'react-native';
import {useResponsive} from '../hooks/useResponsive';

interface VncScreenProps {
  onBack: () => void;
}

type VncState = 'connecting' | 'connected' | 'error' | 'timeout';

interface NativeVncViewProps {
  style?: object;
}

const NativeVncView = requireNativeComponent<NativeVncViewProps>('VncView');
const MAX_RETRIES = 40;
const RETRY_INTERVAL = 3000;

const VncScreen: React.FC<VncScreenProps> = ({onBack}) => {
  const [vncState, setVncState] = useState<VncState>('connecting');
  const [setupLog, setSetupLog] = useState<string[]>([]);
  const connectedRef = useRef(false);
  const listenerRef = useRef<any>(null);
  const progressRef = useRef<any>(null);
  const retryCount = useRef(0);
  const {s} = useResponsive();
  const shimmer = useRef(new Animated.Value(0)).current;

  useEffect(() => {
    Animated.loop(
      Animated.timing(shimmer, {
        toValue: 1,
        duration: 1500,
        easing: Easing.linear,
        useNativeDriver: false,
      }),
    ).start();
  }, [shimmer]);

  const toggleKeyboard = useCallback(() => {
    NativeModules.SimulationModule.toggleKeyboard();
  }, []);

  useEffect(() => {
    progressRef.current = DeviceEventEmitter.addListener('SetupProgress', (event: {message: string}) => {
      retryCount.current = 0;
      setSetupLog(prev => {
        const next = [...prev, event.message];
        return next.slice(-20);
      });
    });

    listenerRef.current = DeviceEventEmitter.addListener('VncStateChange', (event: {state: string}) => {
      switch (event.state) {
        case 'connected':
          connectedRef.current = true;
          retryCount.current = 0;
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

    const retryInterval = setInterval(() => {
      if (vncState === 'connecting') {
        retryCount.current += 1;
        if (retryCount.current >= MAX_RETRIES) {
          setVncState('timeout');
        }
      }
    }, 3000);

    return () => {
      if (listenerRef.current) listenerRef.current.remove();
      if (progressRef.current) progressRef.current.remove();
      clearInterval(retryInterval);
    };
  }, [vncState]);

  useEffect(() => {
    const backHandler = BackHandler.addEventListener('hardwareBackPress', () => {
      onBack();
      return true;
    });
    return () => backHandler.remove();
  }, [onBack]);

  const btnSize = s(52);
  const btnOffset = s(28);

  const lastLog = setupLog[setupLog.length - 1] || '';

  return (
    <View style={styles.container}>
      <NativeVncView style={styles.vncView} />
      {vncState !== 'connected' && (
        <View style={StyleSheet.absoluteFill}>
          <View style={styles.center}>
            {vncState === 'connecting' && (
              <>
                <Text style={{color: '#3b82f6', fontSize: s(13), fontFamily: 'monospace', fontWeight: '700', marginBottom: s(20)}}>
                  GAMA Mobile
                </Text>
                {setupLog.length > 0 && (
                  <View style={{width: '85%', maxHeight: s(200), backgroundColor: '#0c0f1a', borderRadius: s(8), padding: s(10), marginBottom: s(16), borderWidth: 1, borderColor: '#1e293b'}}>
                    {setupLog.map((line, i) => (
                      <Text key={i} style={{color: line.includes('error') || line.includes('fail') ? '#ef4444' : line.includes('complete') || line.includes('ready') ? '#22c55e' : '#94a3b8', fontSize: s(10), fontFamily: 'monospace', lineHeight: s(16)}}>
                        {line}
                      </Text>
                    ))}
                  </View>
                )}
                <View style={{width: s(200), height: s(4), backgroundColor: '#1e293b', borderRadius: s(2), overflow: 'hidden'}}>
                  <Animated.View
                    style={{
                      width: s(200),
                      height: s(4),
                      backgroundColor: '#3b82f6',
                      borderRadius: s(2),
                      opacity: shimmer.interpolate({
                        inputRange: [0, 0.5, 1],
                        outputRange: [0.3, 1, 0.3],
                      }),
                      transform: [{
                        translateX: shimmer.interpolate({
                          inputRange: [0, 1],
                          outputRange: [-s(100), s(100)],
                        }),
                      }],
                    }}
                  />
                </View>
                {lastLog ? (
                  <Text style={{color: '#64748b', fontSize: s(10), fontFamily: 'monospace', marginTop: s(12), textAlign: 'center', paddingHorizontal: s(20)}}>
                    {lastLog}
                  </Text>
                ) : (
                  <Text style={{color: '#64748b', fontSize: s(10), fontFamily: 'monospace', marginTop: s(12)}}>
                    Initializing...
                  </Text>
                )}
              </>
            )}
            {(vncState === 'timeout' || vncState === 'error') && (
              <>
                <Text style={{color: '#ef4444', fontSize: s(14), fontFamily: 'monospace', fontWeight: '600', marginBottom: s(8)}}>
                  {vncState === 'timeout' ? 'Startup timed out' : 'Connection failed'}
                </Text>
                <Text style={{color: '#475569', fontSize: s(11), fontFamily: 'monospace', marginBottom: s(20), textAlign: 'center', paddingHorizontal: s(24)}}>
                  Backend may not have started correctly.
                </Text>
                <TouchableOpacity
                  style={{backgroundColor: '#334155', borderRadius: s(8), paddingHorizontal: s(20), paddingVertical: s(10)}}
                  onPress={onBack}>
                  <Text style={{color: '#f8fafc', fontSize: s(12), fontWeight: '700', fontFamily: 'monospace', letterSpacing: 1}}>RETRY</Text>
                </TouchableOpacity>
              </>
            )}
          </View>
        </View>
      )}
      {vncState === 'connected' && (
        <TouchableOpacity
          style={{
            position: 'absolute',
            bottom: btnOffset,
            right: btnOffset,
            width: btnSize,
            height: btnSize,
            borderRadius: btnSize / 2,
            backgroundColor: 'rgba(30, 41, 59, 0.85)',
            borderWidth: 1,
            borderColor: 'rgba(148, 163, 184, 0.3)',
            elevation: 8,
            zIndex: 9999,
            shadowColor: '#000',
            shadowOffset: {width: 0, height: 4},
            shadowOpacity: 0.3,
            shadowRadius: 6,
          }}
          onPress={toggleKeyboard}
          activeOpacity={0.6}>
          <View style={styles.kbdInner}>
            <Text style={{color: '#e2e8f0', fontSize: s(22)}}>⌨</Text>
          </View>
        </TouchableOpacity>
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
    backgroundColor: 'rgba(15, 23, 42, 0.95)',
  },
  vncView: {
    flex: 1,
    backgroundColor: '#000000',
  },
  kbdInner: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
  },
});

export default VncScreen;
