import React from 'react';
import {View, Text, StyleSheet} from 'react-native';

interface StatusIndicatorProps {
  label: string;
  status: 'ok' | 'error' | 'warning' | 'inactive';
  value?: string;
}

const statusColors: Record<string, string> = {
  ok: '#22c55e',
  error: '#ef4444',
  warning: '#f59e0b',
  inactive: '#64748b',
};

const StatusIndicator: React.FC<StatusIndicatorProps> = ({
  label,
  status,
  value,
}) => {
  const color = statusColors[status] || statusColors.inactive;

  return (
    <View style={styles.container}>
      <View style={[styles.dot, {backgroundColor: color}]} />
      <Text style={styles.label}>{label}</Text>
      {value !== undefined && <Text style={styles.value}>{value}</Text>}
    </View>
  );
};

const styles = StyleSheet.create({
  container: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingVertical: 4,
    paddingHorizontal: 8,
  },
  dot: {
    width: 10,
    height: 10,
    borderRadius: 5,
    marginRight: 8,
  },
  label: {
    color: '#94a3b8',
    fontSize: 13,
    fontFamily: 'monospace',
  },
  value: {
    color: '#e2e8f0',
    fontSize: 13,
    fontFamily: 'monospace',
    marginLeft: 'auto',
  },
});

export default StatusIndicator;
