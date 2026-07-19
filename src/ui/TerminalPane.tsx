import React, {useRef} from 'react';
import {ScrollView, View, Text, StyleSheet} from 'react-native';
import {theme} from '../theme';
import {useStore} from '../store';

export default function TerminalPane({flex}: {flex: number}) {
  const term = useStore(s => s.term);
  const sandbox = useStore(s => s.sandbox);
  const ref = useRef<ScrollView>(null);

  return (
    <View style={[styles.pane, {flexGrow: flex}]}>
      <View style={styles.bar}>
        <Text style={styles.barLabel}>shell</Text>
        <Text style={styles.barPath}>{sandbox.alpine ? 'alpine · proot' : 'toybox'}</Text>
      </View>
      <ScrollView
        ref={ref}
        style={styles.scroll}
        contentContainerStyle={styles.content}
        onContentSizeChange={() => ref.current?.scrollToEnd({animated: true})}>
        {term.map(e => {
          if (e.kind === 'cmd') {
            return (
              <Text key={e.id} style={styles.line}>
                <Text style={styles.prompt}>$ </Text>
                <Text style={styles.cmd}>{e.text}</Text>
              </Text>
            );
          }
          const style =
            e.kind === 'err' ? styles.err : e.kind === 'info' ? styles.info : styles.out;
          return (
            <Text key={e.id} style={[styles.line, style]}>
              {e.text}
            </Text>
          );
        })}
      </ScrollView>
    </View>
  );
}

const styles = StyleSheet.create({
  pane: {flexBasis: 0, backgroundColor: theme.termBg},
  bar: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 12,
    paddingVertical: 7,
    backgroundColor: '#0d0b0a',
    borderBottomWidth: 1,
    borderBottomColor: '#1e1a17',
  },
  barLabel: {
    fontFamily: theme.mono,
    fontSize: 9.5,
    letterSpacing: 2,
    textTransform: 'uppercase',
    color: theme.termDim,
  },
  barPath: {fontFamily: theme.mono, fontSize: 10, color: theme.termDim, marginLeft: 'auto'},
  scroll: {flex: 1},
  content: {paddingHorizontal: 12, paddingVertical: 10, gap: 2},
  line: {fontFamily: theme.mono, fontSize: 12.5, lineHeight: 19, color: theme.termText},
  prompt: {color: theme.termPrompt},
  cmd: {color: theme.termText},
  out: {color: theme.termDim},
  err: {color: theme.termRed},
  info: {color: theme.termDim, fontStyle: 'italic'},
});
