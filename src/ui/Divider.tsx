import React, {useRef} from 'react';
import {View, PanResponder, StyleSheet} from 'react-native';
import {theme} from '../theme';
import {getState, actions} from '../store';
import {GoldFill} from './Gold';

/**
 * Draggable purple inlay handle between the chat and terminal. Converts a
 * vertical drag into a new split ratio using the panes' container height
 * (supplied by the parent).
 */
export default function Divider({getHeight}: {getHeight: () => number}) {
  const startSplit = useRef(0.54);

  const responder = useRef(
    PanResponder.create({
      onStartShouldSetPanResponder: () => true,
      onMoveShouldSetPanResponder: () => true,
      onPanResponderGrant: () => {
        startSplit.current = getState().split;
      },
      onPanResponderMove: (_evt, gesture) => {
        const h = getHeight() || 1;
        actions.setSplit(startSplit.current + gesture.dy / h);
      },
    }),
  ).current;

  return (
    <View style={styles.wrap} {...responder.panHandlers}>
      <GoldFill style={styles.grip} radius={2} />
    </View>
  );
}

const styles = StyleSheet.create({
  wrap: {
    height: 16,
    backgroundColor: theme.surface,
    borderTopWidth: 1,
    borderBottomWidth: 1,
    borderColor: theme.border,
    alignItems: 'center',
    justifyContent: 'center',
  },
  grip: {width: 44, height: 3, borderRadius: 2},
});
