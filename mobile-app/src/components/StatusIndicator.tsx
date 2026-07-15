import React from 'react';
import {View, Text, StyleSheet} from 'react-native';
import {useResponsive} from '../hooks/useResponsive';

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
  const {s} = useResponsive();
  const dotSize = s(10);

  return (
    <View style={styles.container}>
      <View style={{width: dotSize, height: dotSize, borderRadius: dotSize / 2, backgroundColor: color, marginRight: s(8)}} />
      <Text style={{color: '#94a3b8', fontSize: s(13), fontFamily: 'monospace'}}>{label}</Text>
      {value !== undefined && <Text style={{color: '#e2e8f0', fontSize: s(13), fontFamily: 'monospace', marginLeft: 'auto'}}>{value}</Text>}
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
});

export default StatusIndicator;
