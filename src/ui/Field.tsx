import React from 'react';
import {View, Text, TextInput, StyleSheet} from 'react-native';
import {theme} from '../theme';

/** Shared labeled text input. */
export default function Field(props: {
  label: string;
  value: string;
  onChangeText: (t: string) => void;
  placeholder?: string;
  secureTextEntry?: boolean;
  multiline?: boolean;
}) {
  return (
    <View style={styles.field}>
      <Text style={styles.fieldLabel}>{props.label}</Text>
      <TextInput
        style={[styles.fieldInput, props.multiline && styles.multiline]}
        value={props.value}
        onChangeText={props.onChangeText}
        placeholder={props.placeholder}
        placeholderTextColor={theme.textFaint}
        autoCapitalize="none"
        autoCorrect={false}
        secureTextEntry={props.secureTextEntry}
        multiline={props.multiline}
        textAlignVertical={props.multiline ? 'top' : 'center'}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  field: {marginBottom: 12},
  fieldLabel: {fontSize: 12, color: theme.textDim, marginBottom: 5},
  fieldInput: {
    backgroundColor: theme.bg,
    borderWidth: 1,
    borderColor: theme.border,
    borderRadius: 4,
    paddingHorizontal: 12,
    paddingVertical: 9,
    color: theme.text,
    fontSize: 14,
  },
  multiline: {minHeight: 120, fontFamily: theme.mono, fontSize: 13, lineHeight: 19},
});
