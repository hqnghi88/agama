import React from 'react';
import {SafeAreaView, StyleSheet} from 'react-native';
import VncScreen from './src/screens/VncScreen';

const App: React.FC = () => {
  return (
    <SafeAreaView style={styles.container}>
      <VncScreen onBack={() => {}} />
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
