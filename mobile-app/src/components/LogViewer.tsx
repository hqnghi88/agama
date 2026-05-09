import React from 'react';
import {View, Text, FlatList, StyleSheet} from 'react-native';
import {useSimulationStore, type LogEntry} from '../store/useSimulationStore';

const levelColors: Record<string, string> = {
  error: '#ef4444',
  warn: '#f59e0b',
  info: '#22c55e',
  debug: '#64748b',
};

const LogItem: React.FC<{entry: LogEntry}> = React.memo(({entry}) => {
  const color = levelColors[entry.level] || levelColors.debug;
  const time = entry.timestamp.slice(11, 19);

  return (
    <View style={styles.logItem}>
      <Text style={[styles.logTime, {color}]}>{time}</Text>
      <Text style={[styles.logLevel, {color}]}>
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

  if (logs.length === 0) {
    return (
      <View style={styles.empty}>
        <Text style={styles.emptyText}>No logs</Text>
      </View>
    );
  }

  return (
    <FlatList
      data={logs}
      keyExtractor={(_, i) => String(i)}
      renderItem={({item}) => <LogItem entry={item} />}
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
  logTime: {
    fontFamily: 'monospace',
    fontSize: 11,
    width: 60,
  },
  logLevel: {
    fontFamily: 'monospace',
    fontSize: 11,
    width: 55,
    fontWeight: '700',
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
  emptyText: {
    color: '#64748b',
    fontFamily: 'monospace',
    fontSize: 12,
  },
});

export default LogViewer;
