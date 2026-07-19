import React, {useState} from 'react';
import {View, TextInput, Pressable, Text, StyleSheet} from 'react-native';
import {theme} from '../theme';
import {useStore} from '../store';
import {GoldFill} from './Gold';

export default function Composer({onSend}: {onSend: (text: string) => void}) {
  const [text, setText] = useState('');
  const status = useStore(s => s.status);
  const busy = status !== 'idle';

  const submit = () => {
    const t = text.trim();
    if (!t || busy) {
      return;
    }
    setText('');
    onSend(t);
  };

  return (
    <View style={styles.bar}>
      <TextInput
        style={styles.input}
        value={text}
        onChangeText={setText}
        placeholder="Ask the agent to do something…"
        placeholderTextColor={theme.textFaint}
        onSubmitEditing={submit}
        returnKeyType="send"
        editable={!busy}
      />
      <Pressable
        style={busy && styles.sendBusy}
        onPress={submit}
        disabled={busy}
        hitSlop={6}>
        <GoldFill style={styles.send}>
          <Text style={styles.sendIcon}>↑</Text>
        </GoldFill>
      </Pressable>
    </View>
  );
}

const styles = StyleSheet.create({
  bar: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 9,
    paddingHorizontal: 12,
    paddingVertical: 10,
    backgroundColor: theme.surface,
    borderTopWidth: 1,
    borderTopColor: theme.border,
  },
  input: {
    flex: 1,
    fontSize: 14,
    color: theme.text,
    backgroundColor: theme.bg,
    borderWidth: 1,
    borderColor: theme.border,
    borderRadius: 4,
    paddingHorizontal: 13,
    paddingVertical: 9,
  },
  send: {
    width: 38,
    height: 38,
    alignItems: 'center',
    justifyContent: 'center',
  },
  sendBusy: {opacity: 0.4},
  sendIcon: {color: '#1a1205', fontSize: 18, fontWeight: '800'},
});
