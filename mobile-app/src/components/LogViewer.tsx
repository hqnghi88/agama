import React from 'react';
import {View, Text, FlatList, StyleSheet} from 'react-native';
import {useSimulationStore, type LogEntry} from '../store/useSimulationStore';
import {useResponsive} from '../hooks/useResponsive';

const levelColors: Record<string, string> = {
  error: '#ef4444',
  warn: '#f59e0b',
  info: '#22c55e',
  debug: '#64748b',
};

const LogItem: React.FC<{entry: LogEntry; s: (v: number) => number}> = React.memo(({entry, s}) => {
  const color = levelColors[entry.level] || levelColors.debug;
  const time = entry.timestamp.slice(11, 19);

  return (
    <View style={styles.logItem}>
      <Text style={{fontFamily: 'monospace', fontSize: s(11), color, width: s(60)}}>{time}</Text>
      <Text style={{fontFamily: 'monospace', fontSize: s(11), color, width: s(55), fontWeight: '700'}}>
        {entry.level.toUpperCase().padEnd(5)}
      </Text>
      <Text style={styles.logMessage} numberOfLines={2}>
        {entry.message}
      </Text>
    </View>
  );
});

const LogViewer: React.FC = () => {
  const logs = useSimulationStore(s => s.logs);
  const {s} = useResponsive();

  if (logs.length === 0) {
    return (
      <View style={styles.empty}>
        <Text style={{color: '#64748b', fontFamily: 'monospace', fontSize: s(12)}}>No logs</Text>
      </View>
    );
  }

  return (
    <FlatList
      data={logs}
      keyExtractor={(_, i) => String(i)}
      renderItem={({item}) => <LogItem entry={item} s={s} />}
      style={styles.list}
      initialNumToRender={20}
      maxToRenderPerBatch={20}
    />
  );
};

const styles = StyleSheet.create({
  list: {
    flex: 1,
    backgroundColor: '#1e293b',
    borderRadius: 8,
    padding: 4,
  },
  logItem: {
    flexDirection: 'row',
    paddingVertical: 2,
    paddingHorizontal: 4,
  },
  logMessage: {
    fontFamily: 'monospace',
    fontSize: 11,
    color: '#cbd5e1',
    flex: 1,
  },
  empty: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
  },
});

export default LogViewer;
