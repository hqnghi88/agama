import React, {useState} from 'react';
import {SafeAreaView, StyleSheet} from 'react-native';
import DashboardScreen from './src/screens/DashboardScreen';
import VncScreen from './src/screens/VncScreen';

type Screen = 'dashboard' | 'vnc';

const App: React.FC = () => {
  const [currentScreen, setCurrentScreen] = useState<Screen>('dashboard');

  return (
    <SafeAreaView style={styles.container}>
      {currentScreen === 'dashboard' ? (
        <DashboardScreen onOpenVnc={() => setCurrentScreen('vnc')} />
      ) : (
        <VncScreen onBack={() => setCurrentScreen('dashboard')} />
      )}
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
