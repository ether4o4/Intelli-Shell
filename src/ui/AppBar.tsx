import React from 'react';
import {View, Text, Pressable, StyleSheet} from 'react-native';
import {theme} from '../theme';
import {useStore, actions} from '../store';
import {stopAll} from '../agent/controller';
import {GoldText} from './Gold';

export default function AppBar() {
  const settings = useStore(s => s.settings);
  const status = useStore(s => s.status);
  const cloud = settings.provider === 'cloud';
  const label = cloud ? settings.cloudModel : settings.localModelId;
  const busy = status !== 'idle';

  return (
    <View style={styles.bar}>
      <GoldText text="IntelliShell" size={15} />
      <View style={styles.spacer} />

      {/* Emergency stop — always tappable; goes bright when the agent is active. */}
      <Pressable
        style={[styles.stop, busy && styles.stopActive]}
        onPress={stopAll}
        hitSlop={8}
        accessibilityLabel="Emergency stop">
        <View style={[styles.stopIcon, busy && styles.stopIconActive]} />
        <Text style={[styles.stopText, busy && styles.stopTextActive]}>STOP</Text>
      </Pressable>

      {/* Model chip → jump straight to AI config. */}
      <Pressable style={styles.chip} onPress={() => actions.openDashboard('config')} hitSlop={8}>
        <View style={[styles.dot, {backgroundColor: cloud ? theme.red : theme.purple}]} />
        <Text style={styles.chipText} numberOfLines={1}>
          {label}
        </Text>
      </Pressable>

      {/* Dashboard (scripts / notes / config). */}
      <Pressable style={styles.dash} onPress={() => actions.openDashboard()} hitSlop={8} accessibilityLabel="Open dashboard">
        <Text style={styles.dashIcon}>▦</Text>
      </Pressable>
    </View>
  );
}

const styles = StyleSheet.create({
  bar: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 14,
    paddingVertical: 10,
    backgroundColor: theme.surface,
    borderBottomWidth: 1,
    borderBottomColor: theme.border,
    gap: 8,
  },
  mark: {fontFamily: theme.mono, fontWeight: '800', fontSize: 17, letterSpacing: 2, color: theme.red},
  sub: {
    fontFamily: theme.mono,
    fontSize: 9,
    letterSpacing: 2,
    textTransform: 'uppercase',
    color: theme.textFaint,
    marginLeft: 4,
  },
  spacer: {flex: 1},
  stop: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 5,
    borderWidth: 1,
    borderColor: theme.red,
    borderRadius: 4,
    paddingHorizontal: 9,
    paddingVertical: 5,
  },
  stopActive: {backgroundColor: theme.red},
  stopIcon: {width: 8, height: 8, borderRadius: 1, backgroundColor: theme.red},
  stopIconActive: {backgroundColor: '#fff'},
  stopText: {fontFamily: theme.mono, fontSize: 11, fontWeight: '800', letterSpacing: 1, color: theme.red},
  stopTextActive: {color: '#fff'},
  chip: {
    flexDirection: 'row',
    alignItems: 'center',
    borderWidth: 1,
    borderColor: theme.border,
    borderRadius: 4,
    paddingHorizontal: 9,
    paddingVertical: 5,
    maxWidth: 108,
    flexShrink: 1,
  },
  dot: {width: 6, height: 6, borderRadius: 3, marginRight: 7},
  chipText: {fontFamily: theme.mono, fontSize: 11, color: theme.text, flexShrink: 1},
  dash: {
    borderWidth: 1,
    borderColor: theme.border,
    borderRadius: 4,
    paddingHorizontal: 9,
    paddingVertical: 4,
  },
  dashIcon: {color: theme.purple, fontSize: 15, lineHeight: 18},
});
