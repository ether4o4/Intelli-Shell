import React from 'react';
import {View, Text, Pressable, StyleSheet} from 'react-native';
import {theme} from '../../theme';
import {useStore, actions} from '../../store';
import {CLOUD_PRESETS} from '../../models';
import {localAvailable} from '../../llm/localLlama';
import {Bridge} from '../../native/bridge';
import Field from '../Field';
import ModelList from '../ModelList';

/**
 * AI config tab — everything the agent needs to think: provider + API keys, the
 * Hugging Face token for private downloads, on-device models, the Linux shell,
 * and Termux-level file access.
 */
export default function ConfigTab() {
  const settings = useStore(s => s.settings);
  const sandbox = useStore(s => s.sandbox);
  const cloud = settings.provider === 'cloud';
  const engineReady = localAvailable();

  const [storageOk, setStorageOk] = React.useState<boolean | null>(null);
  const refreshStorage = React.useCallback(() => {
    Bridge.hasStoragePermission().then(setStorageOk).catch(() => setStorageOk(null));
  }, []);
  React.useEffect(() => {
    refreshStorage();
  }, [refreshStorage]);

  return (
    <View>
      {/* Provider */}
      <Text style={styles.section}>Provider</Text>
      <View style={styles.segment}>
        <Seg label="Cloud" active={cloud} onPress={() => actions.updateSettings({provider: 'cloud'})} />
        <Seg label="On-device" active={!cloud} onPress={() => actions.updateSettings({provider: 'local'})} />
      </View>

      {cloud ? (
        <>
          <View style={styles.presetRow}>
            {CLOUD_PRESETS.map(p => (
              <Pressable
                key={p.id}
                style={[styles.preset, settings.cloudBaseUrl === p.baseUrl && styles.presetOn]}
                onPress={() => actions.updateSettings({cloudBaseUrl: p.baseUrl, cloudModel: p.model})}>
                <Text style={styles.presetText}>{p.name}</Text>
              </Pressable>
            ))}
          </View>
          <Field label="Base URL" value={settings.cloudBaseUrl} onChangeText={t => actions.updateSettings({cloudBaseUrl: t})} placeholder="https://api.openai.com/v1" />
          <Field label="Model" value={settings.cloudModel} onChangeText={t => actions.updateSettings({cloudModel: t})} placeholder="gpt-4o-mini" />
          <Field label="OpenAI / API key" value={settings.cloudKey} onChangeText={t => actions.updateSettings({cloudKey: t})} placeholder="sk-…" secureTextEntry />
          <Text style={styles.hint}>Keys are stored only on this device and sent straight to the endpoint you chose.</Text>
        </>
      ) : (
        <>
          <View style={[styles.status, engineReady ? styles.statusOk : styles.statusWarn]}>
            <Text style={styles.statusText}>
              {engineReady
                ? 'On-device engine ready. Download a model, then chat fully offline.'
                : 'On-device inference engine isn’t linked in this build. Downloads and selection work; generation needs the engine build.'}
            </Text>
          </View>
        </>
      )}

      {/* Hugging Face */}
      <Text style={styles.section}>Hugging Face</Text>
      <Field
        label="Access token (optional)"
        value={settings.hfToken}
        onChangeText={t => actions.updateSettings({hfToken: t.trim()})}
        placeholder="hf_… (for private / gated models)"
        secureTextEntry
      />
      <Text style={styles.hint}>
        Needed only to download private or gated models — like your own repos. Stored on this device,
        sent only to huggingface.co.
      </Text>

      {/* Local models */}
      <Text style={styles.section}>Local models</Text>
      <ModelList />

      {/* Linux shell */}
      <Text style={styles.section}>Linux shell</Text>
      <View style={styles.card}>
        <Text style={styles.cardText}>{sandbox.statusText}</Text>
        <Pressable style={styles.actionBtn} onPress={() => void Bridge.setupSandbox()}>
          <Text style={styles.actionBtnText}>{sandbox.alpine ? 'Reinstall' : 'Set up'}</Text>
        </Pressable>
      </View>
      <Text style={styles.hint}>
        Alpine Linux gives the agent a real shell (apk, coreutils, git). Without it, a built-in
        toybox shell (ls, cat, grep, sed…) is always available.
      </Text>

      {/* File access */}
      <Text style={styles.section}>File access</Text>
      <View style={[styles.card, storageOk ? styles.cardOk : null]}>
        <Text style={styles.cardText}>
          {storageOk === null
            ? 'File access status unknown.'
            : storageOk
              ? '✓ Full file access granted — the agent can read/write your storage.'
              : 'Grant full file access so the agent can read and write files like Termux.'}
        </Text>
        {!storageOk ? (
          <Pressable
            style={styles.actionBtn}
            onPress={() => {
              void Bridge.requestStoragePermission();
              setTimeout(refreshStorage, 800);
            }}>
            <Text style={styles.actionBtnText}>Grant</Text>
          </Pressable>
        ) : null}
      </View>
      <Text style={styles.hint}>
        Opens Android’s “All files access” page for IntelliShell. Come back and this flips to
        granted. The agent’s shell then has the same file reach you do.
      </Text>
      <View style={styles.footerSpace} />
    </View>
  );
}

function Seg({label, active, onPress}: {label: string; active: boolean; onPress: () => void}) {
  return (
    <Pressable style={[styles.seg, active && styles.segOn]} onPress={onPress}>
      <Text style={[styles.segText, active && styles.segTextOn]}>{label}</Text>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  section: {fontFamily: theme.mono, fontSize: 10, letterSpacing: 1.5, textTransform: 'uppercase', color: theme.textFaint, marginBottom: 8, marginTop: 10},
  segment: {flexDirection: 'row', borderWidth: 1, borderColor: theme.border, borderRadius: 4, overflow: 'hidden', marginBottom: 12},
  seg: {flex: 1, paddingVertical: 9, alignItems: 'center', backgroundColor: theme.bg},
  segOn: {backgroundColor: theme.purpleSoft},
  segText: {fontFamily: theme.mono, fontSize: 12, color: theme.textDim},
  segTextOn: {color: theme.text},
  presetRow: {flexDirection: 'row', flexWrap: 'wrap', gap: 8, marginBottom: 14},
  preset: {borderWidth: 1, borderColor: theme.border, borderRadius: 4, paddingHorizontal: 12, paddingVertical: 7},
  presetOn: {borderColor: theme.purple, backgroundColor: theme.purpleSoft},
  presetText: {color: theme.text, fontSize: 13},
  hint: {color: theme.textFaint, fontSize: 12, lineHeight: 18, marginTop: 4, marginBottom: 14},
  status: {borderWidth: 1, borderColor: theme.border, borderRadius: 4, padding: 11, marginBottom: 12},
  statusOk: {borderColor: theme.purple, backgroundColor: theme.purpleSoft},
  statusWarn: {borderColor: theme.red, backgroundColor: theme.redSoft},
  statusText: {color: theme.text, fontSize: 12.5, lineHeight: 18},
  card: {flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: 10, borderWidth: 1, borderColor: theme.border, borderRadius: 4, padding: 12, marginBottom: 6},
  cardOk: {borderColor: theme.purple, backgroundColor: theme.purpleSoft},
  cardText: {color: theme.text, fontSize: 13, flex: 1},
  actionBtn: {borderWidth: 1, borderColor: theme.purple, borderRadius: 4, paddingHorizontal: 12, paddingVertical: 6},
  actionBtnText: {color: theme.purple, fontSize: 12, fontWeight: '700'},
  footerSpace: {height: 28},
});
