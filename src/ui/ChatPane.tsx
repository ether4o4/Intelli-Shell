import React, {useRef} from 'react';
import {ScrollView, View, Text, StyleSheet} from 'react-native';
import {theme} from '../theme';
import {useStore} from '../store';

export default function ChatPane({flex}: {flex: number}) {
  const messages = useStore(s => s.messages);
  const status = useStore(s => s.status);
  const ref = useRef<ScrollView>(null);

  return (
    <ScrollView
      ref={ref}
      style={[styles.pane, {flexGrow: flex}]}
      contentContainerStyle={styles.content}
      keyboardShouldPersistTaps="handled"
      onContentSizeChange={() => ref.current?.scrollToEnd({animated: true})}>
      {messages.length === 0 && (
        <Text style={styles.empty}>
          Ask me to find something, run a command, or build a file — I’ll work in the shell below,
          live.
        </Text>
      )}

      {messages.map(m =>
        m.role === 'user' ? (
          <View key={m.id} style={styles.userRow}>
            <Text style={styles.userBubble}>{m.text}</Text>
          </View>
        ) : (
          <View key={m.id} style={styles.aiRow}>
            <Text style={styles.tag}>IntelliShell</Text>
            <Text style={styles.aiText}>{m.text || (status === 'thinking' ? '…' : '')}</Text>
          </View>
        ),
      )}

      {status === 'working' && (
        <View style={styles.working}>
          <View style={styles.pulse} />
          <Text style={styles.workingText}>working in the shell…</Text>
        </View>
      )}
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  pane: {flexBasis: 0, backgroundColor: theme.surface},
  content: {padding: 16, gap: 16},
  empty: {color: theme.textDim, fontSize: 14.5, lineHeight: 22, maxWidth: 320},
  userRow: {alignItems: 'flex-end'},
  userBubble: {
    backgroundColor: theme.surface2,
    borderWidth: 1,
    borderColor: theme.border,
    color: theme.text,
    fontSize: 14.5,
    lineHeight: 21,
    paddingHorizontal: 12,
    paddingVertical: 8,
    borderRadius: 4,
    maxWidth: '86%',
  },
  aiRow: {alignItems: 'flex-start'},
  tag: {
    fontFamily: theme.mono,
    fontSize: 9.5,
    letterSpacing: 2,
    textTransform: 'uppercase',
    color: theme.gold,
    marginBottom: 5,
  },
  aiText: {color: theme.text, fontSize: 14.5, lineHeight: 22},
  working: {flexDirection: 'row', alignItems: 'center', gap: 8, marginTop: 2},
  pulse: {width: 6, height: 6, borderRadius: 3, backgroundColor: theme.red},
  workingText: {fontFamily: theme.mono, fontSize: 11, color: theme.textDim},
});
