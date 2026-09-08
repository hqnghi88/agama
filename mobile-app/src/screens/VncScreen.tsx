import React, {useState, useEffect, useRef, useCallback} from 'react';
import {View, Text, StyleSheet, DeviceEventEmitter, requireNativeComponent, BackHandler, TouchableOpacity, NativeModules, ScrollView} from 'react-native';
import {useResponsive} from '../hooks/useResponsive';

interface VncScreenProps {
  onBack: () => void;
}

type VncState = 'connecting' | 'connected' | 'error' | 'timeout';

interface NativeVncViewProps {
  style?: object;
}

const NativeVncView = requireNativeComponent<NativeVncViewProps>('VncView');
const MAX_RETRIES = 100;
const RETRY_INTERVAL = 3000;

const VncScreen: React.FC<VncScreenProps> = ({onBack}) => {
  const [vncState, setVncState] = useState<VncState>('connecting');
  const [setupLog, setSetupLog] = useState<string[]>([]);
  const connectedRef = useRef(false);
  const listenerRef = useRef<any>(null);
  const progressRef = useRef<any>(null);
  const retryCount = useRef(0);
  const {s} = useResponsive();
  const logScrollRef = useRef<ScrollView>(null);

  useEffect(() => {
    if (logScrollRef.current && setupLog.length > 0) {
      logScrollRef.current.scrollToEnd({animated: false});
    }
  }, [setupLog]);

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

  const clean = (line: string) =>
    line.replace(/\u001b\[[0-9;]*m/g, '').replace(/\r/g, '').trim();

  const lineColor = (line: string) => {
    const l = line.toLowerCase();
    if (l.includes('error') || l.includes('fail') || l.includes('exception')) return '#f87171';
    if (l.includes('complete') || l.includes('ready') || l.includes('started') || l.includes('ok')) return '#4ade80';
    if (l.includes('extract') || l.includes('download') || l.includes('boot') || l.includes('start')) return '#7dd3fc';
    return '#c9d7e8';
  };

  const cs = consoleStyles(s);

  return (
    <View style={styles.container}>
      <NativeVncView style={styles.vncView} />
      {vncState !== 'connected' && (
        <View style={StyleSheet.absoluteFill}>
          <View style={styles.center}>
            {vncState === 'connecting' && (
              <View style={cs.consoleWrap}>
                <View style={cs.consoleHeader}>
                  <Text style={cs.consoleTitle}>GAMA Mobile</Text>
                  <Text style={cs.consoleHeaderStatus}>boot console</Text>
                </View>
                <ScrollView
                  ref={logScrollRef}
                  style={cs.consoleBody}
                  contentContainerStyle={cs.consoleContent}
                  showsVerticalScrollIndicator={false}>
                  {setupLog.length === 0 ? (
                    <Text style={[cs.consoleLine, {color: '#4a6d8c'}]}>&gt; waiting for backend…</Text>
                  ) : (
                    setupLog.map((line, i) => (
                      <Text key={i} style={[cs.consoleLine, {color: lineColor(line)}]}>
                        {clean(line)}
                      </Text>
                    ))
                  )}
                </ScrollView>
                <View style={cs.consoleFooter}>
                  <Text style={cs.consolePrompt}>
                    {clean(lastLog) ? `▌ ${clean(lastLog)}` : '▌ initializing…'}
                  </Text>
                </View>
              </View>
            )}
            {(vncState === 'timeout' || vncState === 'error') && (
              <>
                <Text style={{color: '#ef4444', fontSize: s(20), fontFamily: 'monospace', fontWeight: '600', marginBottom: s(8)}}>
                  {vncState === 'timeout' ? 'Startup timed out' : 'Connection failed'}
                </Text>
                <Text style={{color: '#475569', fontSize: s(14), fontFamily: 'monospace', marginBottom: s(20), textAlign: 'center', paddingHorizontal: s(24)}}>
                  Backend may not have started correctly.
                </Text>
                <TouchableOpacity
                  style={{backgroundColor: '#334155', borderRadius: s(8), paddingHorizontal: s(20), paddingVertical: s(10)}}
                  onPress={onBack}>
                  <Text style={{color: '#f8fafc', fontSize: s(16), fontWeight: '700', fontFamily: 'monospace', letterSpacing: 1}}>RETRY</Text>
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
  consoleWrap: {
    flex: 1,
    alignSelf: 'stretch',
    marginTop: 48,
    marginHorizontal: 20,
    marginBottom: 24,
    backgroundColor: '#060a12',
    borderRadius: 12,
    borderWidth: 1,
    borderColor: '#1b2a3a',
    overflow: 'hidden',
    shadowColor: '#000',
    shadowOffset: {width: 0, height: 6},
    shadowOpacity: 0.45,
    shadowRadius: 12,
    elevation: 10,
  },
  consoleHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: 20,
    paddingVertical: 14,
    backgroundColor: '#0b1220',
    borderBottomWidth: 1,
    borderBottomColor: '#16233a',
  },
  consoleTitle: {
    color: '#7dd3fc',
    fontFamily: 'monospace',
    fontWeight: '700',
    fontSize: 26,
    letterSpacing: 1,
  },
  consoleHeaderStatus: {
    color: '#3d6b8f',
    fontFamily: 'monospace',
    fontSize: 18,
  },
  consoleBody: {
    flex: 1,
  },
  consoleContent: {
    paddingHorizontal: 22,
    paddingVertical: 18,
  },
  consoleLine: {
    color: '#c9d7e8',
    fontFamily: 'monospace',
    fontSize: 36,
    lineHeight: 44,
  },
  consoleFooter: {
    paddingHorizontal: 20,
    paddingVertical: 14,
    backgroundColor: '#0b1220',
    borderTopWidth: 1,
    borderTopColor: '#16233a',
  },
  consolePrompt: {
    color: '#22d3ee',
    fontFamily: 'monospace',
    fontSize: 32,
    lineHeight: 40,
  },
});

const consoleStyles = (s: (n: number) => number) => ({
  consoleWrap: {
    flex: 1,
    alignSelf: 'stretch' as const,
    marginTop: s(48),
    marginHorizontal: s(20),
    marginBottom: s(24),
    backgroundColor: '#060a12',
    borderRadius: s(12),
    borderWidth: 1,
    borderColor: '#1b2a3a',
    overflow: 'hidden' as const,
    shadowColor: '#000',
    shadowOffset: {width: 0, height: 6},
    shadowOpacity: 0.45,
    shadowRadius: 12,
    elevation: 10,
  },
  consoleHeader: {
    flexDirection: 'row' as const,
    alignItems: 'center' as const,
    justifyContent: 'space-between' as const,
    paddingHorizontal: s(20),
    paddingVertical: s(14),
    backgroundColor: '#0b1220',
    borderBottomWidth: 1,
    borderBottomColor: '#16233a',
  },
  consoleTitle: {
    color: '#7dd3fc',
    fontFamily: 'monospace',
    fontWeight: '700' as const,
    fontSize: s(26),
    letterSpacing: 1,
  },
  consoleHeaderStatus: {
    color: '#3d6b8f',
    fontFamily: 'monospace',
    fontSize: s(18),
  },
  consoleBody: {
    flex: 1,
  },
  consoleContent: {
    paddingHorizontal: s(22),
    paddingVertical: s(18),
  },
  consoleLine: {
    color: '#c9d7e8',
    fontFamily: 'monospace',
    fontSize: s(36),
    lineHeight: s(44),
  },
  consoleFooter: {
    paddingHorizontal: s(20),
    paddingVertical: s(14),
    backgroundColor: '#0b1220',
    borderTopWidth: 1,
    borderTopColor: '#16233a',
  },
  consolePrompt: {
    color: '#22d3ee',
    fontFamily: 'monospace',
    fontSize: s(32),
    lineHeight: s(40),
  },
});

export default VncScreen;
