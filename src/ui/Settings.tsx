import React from 'react';
import {
  Modal,
  View,
  Text,
  TextInput,
  Pressable,
  ScrollView,
  StyleSheet,
} from 'react-native';
import {theme} from '../theme';
import {useStore, actions} from '../store';
import {CLOUD_PRESETS, LOCAL_MODELS, LocalModel} from '../models';
import {localAvailable} from '../llm/localLlama';
import {Bridge} from '../native/bridge';

export default function Settings() {
  const open = useStore(s => s.settingsOpen);
  const settings = useStore(s => s.settings);
  const close = () => actions.openSettings(false);
  const cloud = settings.provider === 'cloud';

  return (
    <Modal visible={open} transparent animationType="slide" onRequestClose={close}>
      <View style={styles.backdrop}>
        <View style={styles.sheet}>
          <View style={styles.header}>
            <Text style={styles.title}>
              <Text style={styles.titleMark}>IntelliShell</Text> · settings
            </Text>
            <Pressable onPress={close} hitSlop={10}>
              <Text style={styles.close}>Done</Text>
            </Pressable>
          </View>

          <View style={styles.segment}>
            <Seg label="Cloud" active={cloud} onPress={() => actions.updateSettings({provider: 'cloud'})} />
            <Seg
              label="On-device"
              active={!cloud}
              onPress={() => actions.updateSettings({provider: 'local'})}
            />
          </View>

          <ScrollView style={styles.body} keyboardShouldPersistTaps="handled">
            {cloud ? <CloudSettings /> : <LocalSettings />}
            <View style={{height: 24}} />
          </ScrollView>
        </View>
      </View>
    </Modal>
  );
}

function CloudSettings() {
  const settings = useStore(s => s.settings);
  return (
    <>
      <Text style={styles.section}>Provider preset</Text>
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

      <Field
        label="Base URL"
        value={settings.cloudBaseUrl}
        onChangeText={t => actions.updateSettings({cloudBaseUrl: t})}
        placeholder="https://api.openai.com/v1"
      />
      <Field
        label="Model"
        value={settings.cloudModel}
        onChangeText={t => actions.updateSettings({cloudModel: t})}
        placeholder="gpt-4o-mini"
      />
      <Field
        label="API key"
        value={settings.cloudKey}
        onChangeText={t => actions.updateSettings({cloudKey: t})}
        placeholder="sk-…"
        secureTextEntry
      />
      <Text style={styles.hint}>
        Your key is stored only on this device and sent straight to the endpoint you chose.
      </Text>
    </>
  );
}

function LocalSettings() {
  const settings = useStore(s => s.settings);
  const downloads = useStore(s => s.downloads);
  const downloaded = useStore(s => s.downloadedModels);
  const sandbox = useStore(s => s.sandbox);
  const engineReady = localAvailable();

  const startDownload = (m: LocalModel) => {
    actions.setDownload(m.id, {pct: 0, done: false});
    Bridge.downloadModel(m.id, m.url).catch(e =>
      actions.setDownload(m.id, {pct: 0, done: false, error: String((e && e.message) || e)}),
    );
  };
  const del = (id: string) => {
    Bridge.deleteModel(id).then(() =>
      Bridge.listDownloadedModels().then(actions.setDownloadedModels).catch(() => {}),
    );
  };

  return (
    <>
      {/* Linux shell */}
      <Text style={styles.section}>Linux shell</Text>
      <View style={styles.sandboxCard}>
        <Text style={styles.sandboxText}>{sandbox.statusText}</Text>
        <Pressable style={styles.setupBtn} onPress={() => void Bridge.setupSandbox()}>
          <Text style={styles.setupBtnText}>{sandbox.alpine ? 'Reinstall' : 'Set up'}</Text>
        </Pressable>
      </View>
      <Text style={styles.hint}>
        {sandbox.proot
          ? 'Installs Alpine Linux (apk, coreutils) the agent can run commands in.'
          : 'Running the built-in toybox shell (ls, cat, grep, find, sed…). Alpine unlocks a package manager when the proot binary ships.'}
      </Text>

      {/* on-device engine status */}
      <View style={[styles.status, engineReady ? styles.statusOk : styles.statusWarn]}>
        <Text style={styles.statusText}>
          {engineReady
            ? 'On-device engine ready. Download a model, then chat fully offline.'
            : 'On-device inference engine isn’t linked in this build. Downloads and selection work; generation needs the engine build.'}
        </Text>
      </View>

      <Text style={styles.section}>Local model</Text>
      {LOCAL_MODELS.map(m => {
        const on = settings.localModelId === m.id;
        const isDown = downloaded.includes(m.id);
        const dl = downloads[m.id];
        const downloading = dl && !dl.done && !dl.error;
        return (
          <Pressable
            key={m.id}
            style={[styles.model, on && styles.modelOn]}
            onPress={() => actions.updateSettings({localModelId: m.id})}>
            <View style={[styles.radio, on && styles.radioOn]} />
            <View style={styles.modelInfo}>
              <Text style={styles.modelName}>
                {m.name} <Text style={styles.modelMeta}>· {m.params} · {m.size}</Text>
              </Text>
              <Text style={styles.modelNote}>{m.note}</Text>

              {downloading && (
                <View style={styles.progressTrack}>
                  <View style={[styles.progressFill, {width: `${dl.pct}%`}]} />
                  <Text style={styles.progressText}>{dl.pct}%</Text>
                </View>
              )}
              {dl && dl.error ? <Text style={styles.dlError}>download failed — tap to retry</Text> : null}
            </View>

            {isDown ? (
              <Pressable style={styles.delBtn} onPress={() => del(m.id)} hitSlop={6}>
                <Text style={styles.delText}>Delete</Text>
              </Pressable>
            ) : downloading ? (
              <Text style={styles.installedMark}>…</Text>
            ) : (
              <Pressable style={styles.dlBtn} onPress={() => startDownload(m)} hitSlop={6}>
                <Text style={styles.dlText}>Download</Text>
              </Pressable>
            )}
          </Pressable>
        );
      })}
      <Text style={styles.hint}>Models are GGUF files pulled from Hugging Face and run fully on-device.</Text>
    </>
  );
}

function Seg({label, active, onPress}: {label: string; active: boolean; onPress: () => void}) {
  return (
    <Pressable style={[styles.seg, active && styles.segOn]} onPress={onPress}>
      <Text style={[styles.segText, active && styles.segTextOn]}>{label}</Text>
    </Pressable>
  );
}

function Field(props: {
  label: string;
  value: string;
  onChangeText: (t: string) => void;
  placeholder?: string;
  secureTextEntry?: boolean;
}) {
  return (
    <View style={styles.field}>
      <Text style={styles.fieldLabel}>{props.label}</Text>
      <TextInput
        style={styles.fieldInput}
        value={props.value}
        onChangeText={props.onChangeText}
        placeholder={props.placeholder}
        placeholderTextColor={theme.textFaint}
        autoCapitalize="none"
        autoCorrect={false}
        secureTextEntry={props.secureTextEntry}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  backdrop: {flex: 1, backgroundColor: 'rgba(0,0,0,0.6)', justifyContent: 'flex-end'},
  sheet: {
    backgroundColor: theme.surface,
    borderTopLeftRadius: 14,
    borderTopRightRadius: 14,
    borderWidth: 1,
    borderColor: theme.border,
    maxHeight: '88%',
    paddingBottom: 20,
  },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    padding: 16,
  },
  title: {fontSize: 15, color: theme.text},
  titleMark: {fontFamily: theme.mono, color: theme.red, fontWeight: '800', letterSpacing: 1},
  close: {color: theme.purple, fontSize: 14, fontWeight: '600'},
  segment: {
    flexDirection: 'row',
    marginHorizontal: 16,
    borderWidth: 1,
    borderColor: theme.border,
    borderRadius: 4,
    overflow: 'hidden',
  },
  seg: {flex: 1, paddingVertical: 9, alignItems: 'center', backgroundColor: theme.bg},
  segOn: {backgroundColor: theme.purpleSoft},
  segText: {fontFamily: theme.mono, fontSize: 12, color: theme.textDim},
  segTextOn: {color: theme.text},
  body: {paddingHorizontal: 16, paddingTop: 14},
  section: {
    fontFamily: theme.mono,
    fontSize: 10,
    letterSpacing: 1.5,
    textTransform: 'uppercase',
    color: theme.textFaint,
    marginBottom: 8,
    marginTop: 6,
  },
  presetRow: {flexDirection: 'row', flexWrap: 'wrap', gap: 8, marginBottom: 14},
  preset: {
    borderWidth: 1,
    borderColor: theme.border,
    borderRadius: 4,
    paddingHorizontal: 12,
    paddingVertical: 7,
  },
  presetOn: {borderColor: theme.purple, backgroundColor: theme.purpleSoft},
  presetText: {color: theme.text, fontSize: 13},
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
  hint: {color: theme.textFaint, fontSize: 12, lineHeight: 18, marginTop: 4, marginBottom: 14},
  sandboxCard: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    borderWidth: 1,
    borderColor: theme.border,
    borderRadius: 4,
    padding: 12,
    marginBottom: 6,
  },
  sandboxText: {color: theme.text, fontSize: 13, flex: 1, marginRight: 10},
  setupBtn: {
    borderWidth: 1,
    borderColor: theme.purple,
    borderRadius: 4,
    paddingHorizontal: 12,
    paddingVertical: 6,
  },
  setupBtnText: {color: theme.purple, fontSize: 12, fontWeight: '700'},
  status: {borderWidth: 1, borderRadius: 4, padding: 11, marginVertical: 10},
  statusOk: {borderColor: theme.purple, backgroundColor: theme.purpleSoft},
  statusWarn: {borderColor: theme.red, backgroundColor: theme.redSoft},
  statusText: {color: theme.text, fontSize: 12.5, lineHeight: 18},
  model: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 12,
    borderWidth: 1,
    borderColor: theme.border,
    borderRadius: 4,
    padding: 12,
    marginBottom: 8,
  },
  modelOn: {borderColor: theme.purple, backgroundColor: theme.purpleSoft},
  radio: {width: 16, height: 16, borderRadius: 8, borderWidth: 2, borderColor: theme.textFaint},
  radioOn: {borderColor: theme.purple, backgroundColor: theme.purple},
  modelInfo: {flex: 1},
  modelName: {color: theme.text, fontSize: 14},
  modelMeta: {color: theme.textFaint, fontSize: 12},
  modelNote: {color: theme.textDim, fontSize: 12, marginTop: 2},
  progressTrack: {
    height: 16,
    borderRadius: 3,
    backgroundColor: theme.bg,
    marginTop: 8,
    overflow: 'hidden',
    justifyContent: 'center',
  },
  progressFill: {position: 'absolute', left: 0, top: 0, bottom: 0, backgroundColor: theme.purpleDim},
  progressText: {fontFamily: theme.mono, fontSize: 10, color: theme.text, alignSelf: 'center'},
  dlError: {color: theme.red, fontSize: 11, marginTop: 6},
  dlBtn: {borderWidth: 1, borderColor: theme.purple, borderRadius: 4, paddingHorizontal: 11, paddingVertical: 6},
  dlText: {color: theme.purple, fontSize: 12, fontWeight: '700'},
  delBtn: {borderWidth: 1, borderColor: theme.border, borderRadius: 4, paddingHorizontal: 11, paddingVertical: 6},
  delText: {color: theme.textDim, fontSize: 12},
  installedMark: {color: theme.textFaint, fontSize: 16, width: 24, textAlign: 'center'},
});
