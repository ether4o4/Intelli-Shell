import React from 'react';
import {View, Text, TextInput, Pressable, StyleSheet} from 'react-native';
import {theme} from '../../theme';
import {useStore, actions} from '../../store';
import {Script, itemId, preview} from '../../dashboardStorage';
import {runScript} from '../../agent/controller';

/**
 * Scripts tab — save shell scripts and run them in the terminal with one tap.
 * Tap a saved script to edit it; Run executes it in the shell.
 */
export default function ScriptsTab() {
  const scripts = useStore(s => s.scripts);
  const [editing, setEditing] = React.useState<Script | null>(null);
  const [name, setName] = React.useState('');
  const [body, setBody] = React.useState('');
  const [seed, setSeed] = React.useState(1);

  const startNew = () => {
    setEditing(null);
    setName('');
    setBody('');
  };
  const startEdit = (s: Script) => {
    setEditing(s);
    setName(s.name);
    setBody(s.body);
  };
  const save = () => {
    if (!body.trim()) {
      return;
    }
    const id = editing ? editing.id : itemId(Date.now() + seed);
    setSeed(seed + 1);
    actions.saveScript({
      id,
      name: name.trim() || 'script',
      body,
      updatedAt: Date.now(),
    });
    startNew();
  };

  return (
    <View>
      <View style={styles.editor}>
        <Text style={styles.editorTitle}>{editing ? 'Edit script' : 'New script'}</Text>
        <TextInput
          style={styles.nameInput}
          value={name}
          onChangeText={setName}
          placeholder="Script name"
          placeholderTextColor={theme.textFaint}
          autoCapitalize="none"
        />
        <TextInput
          style={styles.bodyInput}
          value={body}
          onChangeText={setBody}
          placeholder={'#!/bin/sh\napk add curl\ncurl -s https://example.com'}
          placeholderTextColor={theme.textFaint}
          autoCapitalize="none"
          autoCorrect={false}
          multiline
          textAlignVertical="top"
        />
        <View style={styles.editorRow}>
          {editing ? (
            <Pressable style={styles.ghostBtn} onPress={startNew}>
              <Text style={styles.ghostBtnText}>Cancel</Text>
            </Pressable>
          ) : null}
          <View style={styles.grow} />
          <Pressable style={[styles.primaryBtn, !body.trim() && styles.btnOff]} disabled={!body.trim()} onPress={save}>
            <Text style={styles.primaryBtnText}>{editing ? 'Update' : 'Save'}</Text>
          </Pressable>
        </View>
      </View>

      <Text style={styles.section}>Saved scripts</Text>
      {scripts.length === 0 ? (
        <Text style={styles.empty}>No scripts yet. Write one above and Save.</Text>
      ) : (
        scripts.map(s => (
          <View key={s.id} style={styles.card}>
            <Pressable style={styles.cardMain} onPress={() => startEdit(s)}>
              <Text style={styles.cardName} numberOfLines={1}>
                {s.name}
              </Text>
              <Text style={styles.cardPreview} numberOfLines={1}>
                {preview(s.body)}
              </Text>
            </Pressable>
            <View style={styles.cardActions}>
              <Pressable style={styles.runBtn} onPress={() => void runScript(s.body)} hitSlop={6}>
                <Text style={styles.runBtnText}>▶ Run</Text>
              </Pressable>
              <Pressable onPress={() => actions.deleteScript(s.id)} hitSlop={8}>
                <Text style={styles.del}>Delete</Text>
              </Pressable>
            </View>
          </View>
        ))
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  editor: {borderWidth: 1, borderColor: theme.border, borderRadius: 6, padding: 12, marginBottom: 18, backgroundColor: theme.surface},
  editorTitle: {color: theme.textDim, fontSize: 12, fontFamily: theme.mono, marginBottom: 8, letterSpacing: 1},
  nameInput: {
    backgroundColor: theme.bg,
    borderWidth: 1,
    borderColor: theme.border,
    borderRadius: 4,
    paddingHorizontal: 12,
    paddingVertical: 9,
    color: theme.text,
    fontSize: 14,
    marginBottom: 8,
  },
  bodyInput: {
    backgroundColor: theme.bg,
    borderWidth: 1,
    borderColor: theme.border,
    borderRadius: 4,
    paddingHorizontal: 12,
    paddingVertical: 9,
    color: theme.text,
    fontFamily: theme.mono,
    fontSize: 13,
    lineHeight: 19,
    minHeight: 120,
  },
  editorRow: {flexDirection: 'row', alignItems: 'center', marginTop: 10},
  grow: {flex: 1},
  primaryBtn: {backgroundColor: theme.purpleSoft, borderWidth: 1, borderColor: theme.purple, borderRadius: 4, paddingHorizontal: 18, paddingVertical: 8},
  primaryBtnText: {color: theme.purple, fontSize: 13, fontWeight: '700'},
  btnOff: {opacity: 0.4},
  ghostBtn: {borderWidth: 1, borderColor: theme.border, borderRadius: 4, paddingHorizontal: 14, paddingVertical: 8},
  ghostBtnText: {color: theme.textDim, fontSize: 13},
  section: {fontFamily: theme.mono, fontSize: 10, letterSpacing: 1.5, textTransform: 'uppercase', color: theme.textFaint, marginBottom: 8},
  empty: {color: theme.textFaint, fontSize: 13, lineHeight: 19},
  card: {borderWidth: 1, borderColor: theme.border, borderRadius: 6, marginBottom: 8, backgroundColor: theme.surface, overflow: 'hidden'},
  cardMain: {padding: 12},
  cardName: {color: theme.text, fontSize: 14, marginBottom: 3},
  cardPreview: {color: theme.textFaint, fontSize: 12, fontFamily: theme.mono},
  cardActions: {flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', borderTopWidth: 1, borderTopColor: theme.border, paddingHorizontal: 12, paddingVertical: 8},
  runBtn: {borderWidth: 1, borderColor: theme.purple, borderRadius: 4, paddingHorizontal: 14, paddingVertical: 6},
  runBtnText: {color: theme.purple, fontSize: 12, fontWeight: '700'},
  del: {color: theme.red, fontSize: 12},
});
