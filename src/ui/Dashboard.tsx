import React from 'react';
import {View, Text, Pressable, ScrollView, StyleSheet} from 'react-native';
import {theme} from '../theme';
import {useStore, actions, DashTab} from '../store';
import ScriptsTab from './dashboard/ScriptsTab';
import NotesTab from './dashboard/NotesTab';
import ConfigTab from './dashboard/ConfigTab';

const TABS: {key: DashTab; label: string}[] = [
  {key: 'scripts', label: 'Scripts'},
  {key: 'notes', label: 'Notes'},
  {key: 'config', label: 'AI config'},
];

/** The dashboard: a header, a three-tab bar, and the active tab's content. */
export default function Dashboard() {
  const tab = useStore(s => s.dashTab);

  return (
    <View style={styles.root}>
      <View style={styles.header}>
        <Text style={styles.title}>Dashboard</Text>
        <Pressable onPress={() => actions.setView('terminal')} hitSlop={10} accessibilityLabel="Back to terminal">
          <Text style={styles.close}>Terminal ›</Text>
        </Pressable>
      </View>

      <View style={styles.tabBar}>
        {TABS.map(t => (
          <Pressable
            key={t.key}
            style={[styles.tab, tab === t.key && styles.tabOn]}
            onPress={() => actions.setDashTab(t.key)}>
            <Text style={[styles.tabText, tab === t.key && styles.tabTextOn]}>{t.label}</Text>
          </Pressable>
        ))}
      </View>

      <ScrollView style={styles.body} keyboardShouldPersistTaps="handled">
        {tab === 'scripts' && <ScriptsTab />}
        {tab === 'notes' && <NotesTab />}
        {tab === 'config' && <ConfigTab />}
        <View style={styles.footerSpace} />
      </ScrollView>
    </View>
  );
}

const styles = StyleSheet.create({
  root: {flex: 1, backgroundColor: theme.bg},
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: 16,
    paddingVertical: 13,
    backgroundColor: theme.surface,
    borderBottomWidth: 1,
    borderBottomColor: theme.border,
  },
  title: {color: theme.text, fontSize: 15, fontWeight: '700', fontFamily: theme.mono, letterSpacing: 1},
  close: {color: theme.purple, fontSize: 14, fontWeight: '600'},
  tabBar: {flexDirection: 'row', backgroundColor: theme.surface, borderBottomWidth: 1, borderBottomColor: theme.border},
  tab: {flex: 1, alignItems: 'center', paddingVertical: 11, borderBottomWidth: 2, borderBottomColor: 'transparent'},
  tabOn: {borderBottomColor: theme.purple},
  tabText: {color: theme.textDim, fontSize: 13, fontFamily: theme.mono},
  tabTextOn: {color: theme.text, fontWeight: '700'},
  body: {flex: 1, paddingHorizontal: 16, paddingTop: 14},
  footerSpace: {height: 32},
});
