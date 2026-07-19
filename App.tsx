import React, {useEffect, useRef} from 'react';
import {
  View,
  StatusBar,
  StyleSheet,
  KeyboardAvoidingView,
  Platform,
} from 'react-native';
import {GestureHandlerRootView} from 'react-native-gesture-handler';
// RN core's SafeAreaView is a no-op on Android; with Android 15's forced
// edge-to-edge the app drew under the status bar and gesture bar without this.
import {SafeAreaProvider, SafeAreaView} from 'react-native-safe-area-context';

import {theme} from './src/theme';
import {useStore, actions, bootstrap} from './src/store';
import {send} from './src/agent/controller';
import AppBar from './src/ui/AppBar';
import ChatPane from './src/ui/ChatPane';
import Divider from './src/ui/Divider';
import TerminalPane from './src/ui/TerminalPane';
import Composer from './src/ui/Composer';
import Settings from './src/ui/Settings';

/**
 * IntelliShell — a standalone Android app: AI chat on top, a live terminal shell on the
 * bottom, so you watch the agent think and work at the same time.
 */
export default function App() {
  const split = useStore(s => s.split);
  const panesHeight = useRef(1);

  useEffect(() => {
    void bootstrap();
  }, []);

  return (
    <GestureHandlerRootView style={styles.root}>
      <SafeAreaProvider>
        <SafeAreaView style={styles.root} edges={['top', 'bottom', 'left', 'right']}>
          <StatusBar barStyle="light-content" backgroundColor={theme.bg} />

        <AppBar onOpenSettings={() => actions.openSettings(true)} />

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

          <Settings />
        </SafeAreaView>
      </SafeAreaProvider>
    </GestureHandlerRootView>
  );
}

const styles = StyleSheet.create({
  root: {flex: 1, backgroundColor: theme.bg},
  panes: {flex: 1},
});
