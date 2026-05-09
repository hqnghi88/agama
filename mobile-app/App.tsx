import React from 'react';
import {SafeAreaView, StyleSheet} from 'react-native';
import DashboardScreen from './src/screens/DashboardScreen';

const App: React.FC = () => {
  return (
    <SafeAreaView style={styles.container}>
      <DashboardScreen />
    </SafeAreaView>
  );
};

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#0f172a',
  },
});

export default App;
