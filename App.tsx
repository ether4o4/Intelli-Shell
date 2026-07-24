import React, {useEffect, useRef} from 'react';
import {
  View,
  StatusBar,
  StyleSheet,
  KeyboardAvoidingView,
  Platform,
  BackHandler,
} from 'react-native';
import {GestureHandlerRootView} from 'react-native-gesture-handler';
// RN core's SafeAreaView is a no-op on Android; with Android 15's forced
// edge-to-edge the app drew under the status bar and gesture bar without this.
import {SafeAreaProvider, SafeAreaView} from 'react-native-safe-area-context';

import {theme} from './src/theme';
import {useStore, actions, getState, bootstrap} from './src/store';
import {send} from './src/agent/controller';
import AppBar from './src/ui/AppBar';
import ChatPane from './src/ui/ChatPane';
import Divider from './src/ui/Divider';
import TerminalPane from './src/ui/TerminalPane';
import Composer from './src/ui/Composer';
import Dashboard from './src/ui/Dashboard';

/**
 * IntelliShell — Termux with a built-in AI agent. Two views:
 *   Terminal  — AI chat on top, a live shell on the bottom (watch it think + work)
 *   Dashboard — Scripts, Notes, and AI config (keys, HF token, local models)
 */
export default function App() {
  const view = useStore(s => s.view);

  useEffect(() => {
    void bootstrap();
  }, []);

  // Android hardware back: from the dashboard, return to the terminal instead of
  // exiting the app.
  useEffect(() => {
    const onBack = () => {
      if (getState().view === 'dashboard') {
        actions.setView('terminal');
        return true;
      }
      return false;
    };
    const sub = BackHandler.addEventListener('hardwareBackPress', onBack);
    return () => sub.remove();
  }, []);

  return (
    <GestureHandlerRootView style={styles.root}>
      <SafeAreaProvider>
        <SafeAreaView style={styles.root} edges={['top', 'bottom', 'left', 'right']}>
          <StatusBar barStyle="light-content" backgroundColor={theme.bg} />
          {view === 'dashboard' ? <Dashboard /> : <Terminal />}
        </SafeAreaView>
      </SafeAreaProvider>
    </GestureHandlerRootView>
  );
}

/** The Termux-style split view: AI chat over a live shell, with the composer. */
function Terminal() {
  const split = useStore(s => s.split);
  const panesHeight = useRef(1);

  return (
    <>
      <AppBar />

      <View
        style={styles.panes}
        onLayout={e => {
          panesHeight.current = e.nativeEvent.layout.height;
        }}>
        <ChatPane flex={split} />
        <Divider getHeight={() => panesHeight.current} />
        <TerminalPane flex={1 - split} />
      </View>

      <KeyboardAvoidingView behavior={Platform.OS === 'ios' ? 'padding' : undefined}>
        <Composer onSend={send} />
      </KeyboardAvoidingView>
    </>
  );
}

const styles = StyleSheet.create({
  root: {flex: 1, backgroundColor: theme.bg},
  panes: {flex: 1},
});
