import React from 'react';
import {View, Text, TextInput, Pressable, StyleSheet} from 'react-native';
import {theme} from '../../theme';
import {useStore, actions} from '../../store';
import {Note, itemId, preview} from '../../dashboardStorage';

/** Notes tab — quick persistent notes. Tap a note to edit it. */
export default function NotesTab() {
  const notes = useStore(s => s.notes);
  const [editing, setEditing] = React.useState<Note | null>(null);
  const [title, setTitle] = React.useState('');
  const [body, setBody] = React.useState('');
  const [seed, setSeed] = React.useState(1);

  const startNew = () => {
    setEditing(null);
    setTitle('');
    setBody('');
  };
  const startEdit = (n: Note) => {
    setEditing(n);
    setTitle(n.title);
    setBody(n.body);
  };
  const save = () => {
    if (!body.trim() && !title.trim()) {
      return;
    }
    const id = editing ? editing.id : itemId(Date.now() + seed);
    setSeed(seed + 1);
    actions.saveNote({
      id,
      title: title.trim() || 'Untitled',
      body,
      updatedAt: Date.now(),
    });
    startNew();
  };

  const empty = !body.trim() && !title.trim();

  return (
    <View>
      <View style={styles.editor}>
        <Text style={styles.editorTitle}>{editing ? 'Edit note' : 'New note'}</Text>
        <TextInput
          style={styles.titleInput}
          value={title}
          onChangeText={setTitle}
          placeholder="Title"
          placeholderTextColor={theme.textFaint}
        />
        <TextInput
          style={styles.bodyInput}
          value={body}
          onChangeText={setBody}
          placeholder="Write a note…"
          placeholderTextColor={theme.textFaint}
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
          <Pressable style={[styles.primaryBtn, empty && styles.btnOff]} disabled={empty} onPress={save}>
            <Text style={styles.primaryBtnText}>{editing ? 'Update' : 'Save'}</Text>
          </Pressable>
        </View>
      </View>

      <Text style={styles.section}>Saved notes</Text>
      {notes.length === 0 ? (
        <Text style={styles.emptyText}>No notes yet.</Text>
      ) : (
        notes.map(n => (
          <View key={n.id} style={styles.card}>
            <Pressable style={styles.cardMain} onPress={() => startEdit(n)}>
              <Text style={styles.cardName} numberOfLines={1}>
                {n.title}
              </Text>
              <Text style={styles.cardPreview} numberOfLines={2}>
                {preview(n.body, 120)}
              </Text>
            </Pressable>
            <View style={styles.cardActions}>
              <View style={styles.grow} />
              <Pressable onPress={() => actions.deleteNote(n.id)} hitSlop={8}>
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
  titleInput: {
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
    fontSize: 14,
    lineHeight: 20,
    minHeight: 110,
  },
  editorRow: {flexDirection: 'row', alignItems: 'center', marginTop: 10},
  grow: {flex: 1},
  primaryBtn: {backgroundColor: theme.purpleSoft, borderWidth: 1, borderColor: theme.purple, borderRadius: 4, paddingHorizontal: 18, paddingVertical: 8},
  primaryBtnText: {color: theme.purple, fontSize: 13, fontWeight: '700'},
  btnOff: {opacity: 0.4},
  ghostBtn: {borderWidth: 1, borderColor: theme.border, borderRadius: 4, paddingHorizontal: 14, paddingVertical: 8},
  ghostBtnText: {color: theme.textDim, fontSize: 13},
  section: {fontFamily: theme.mono, fontSize: 10, letterSpacing: 1.5, textTransform: 'uppercase', color: theme.textFaint, marginBottom: 8},
  emptyText: {color: theme.textFaint, fontSize: 13},
  card: {borderWidth: 1, borderColor: theme.border, borderRadius: 6, marginBottom: 8, backgroundColor: theme.surface, overflow: 'hidden'},
  cardMain: {padding: 12},
  cardName: {color: theme.text, fontSize: 14, marginBottom: 3},
  cardPreview: {color: theme.textFaint, fontSize: 12, lineHeight: 17},
  cardActions: {flexDirection: 'row', alignItems: 'center', borderTopWidth: 1, borderTopColor: theme.border, paddingHorizontal: 12, paddingVertical: 8},
  del: {color: theme.red, fontSize: 12},
});
