import React from 'react';
import {View, Text, TextInput, Pressable, StyleSheet} from 'react-native';
import {theme} from '../theme';
import {useStore, actions} from '../store';
import {LOCAL_MODELS, LocalModel, customModelFromUrl} from '../models';
import {Bridge} from '../native/bridge';

/**
 * On-device model catalog: select, download (live progress), delete, and add
 * custom GGUFs by URL. Used by the dashboard's AI-config tab.
 */
export default function ModelList() {
  const settings = useStore(s => s.settings);
  const downloads = useStore(s => s.downloads);
  const downloaded = useStore(s => s.downloadedModels);

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
      {[...LOCAL_MODELS, ...settings.customModels].map(m => {
        const isCustom = settings.customModels.some(c => c.id === m.id);
        const removeCustom = () => {
          if (downloaded.includes(m.id)) {
            del(m.id);
          }
          const patch: any = {customModels: settings.customModels.filter(c => c.id !== m.id)};
          if (settings.localModelId === m.id) {
            patch.localModelId = LOCAL_MODELS[0].id;
          }
          actions.updateSettings(patch);
        };
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
            <View style={styles.actionCol}>
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
              {isCustom && (
                <Pressable onPress={removeCustom} hitSlop={6}>
                  <Text style={styles.removeText}>Remove</Text>
                </Pressable>
              )}
            </View>
          </Pressable>
        );
      })}
      <AddCustomModel onAdd={startDownload} />
      <Text style={styles.hint}>Models are GGUF files pulled from Hugging Face and run fully on-device.</Text>
    </>
  );
}

function AddCustomModel({onAdd}: {onAdd: (m: LocalModel) => void}) {
  const settings = useStore(s => s.settings);
  const [url, setUrl] = React.useState('');
  const [error, setError] = React.useState('');

  const add = () => {
    const m = customModelFromUrl(url);
    if (!m) {
      setError('Paste a direct https link to a .gguf file (a Hugging Face "resolve" or "blob" URL works).');
      return;
    }
    setError('');
    setUrl('');
    const exists =
      LOCAL_MODELS.some(x => x.id === m.id) || settings.customModels.some(x => x.id === m.id);
    if (exists) {
      actions.updateSettings({localModelId: m.id});
      return;
    }
    actions.updateSettings({customModels: [...settings.customModels, m], localModelId: m.id});
    onAdd(m);
  };

  return (
    <View style={styles.addBox}>
      <Text style={styles.addTitle}>Add any GGUF from Hugging Face</Text>
      <View style={styles.addRow}>
        <TextInput
          style={styles.addInput}
          value={url}
          onChangeText={t => {
            setUrl(t);
            if (error) {
              setError('');
            }
          }}
          placeholder="https://huggingface.co/…/resolve/main/model.Q4_K_M.gguf"
          placeholderTextColor={theme.textFaint}
          autoCapitalize="none"
          autoCorrect={false}
        />
        <Pressable style={styles.dlBtn} onPress={add} hitSlop={6}>
          <Text style={styles.dlText}>Add</Text>
        </Pressable>
      </View>
      {error ? <Text style={styles.dlError}>{error}</Text> : null}
    </View>
  );
}

const styles = StyleSheet.create({
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
  actionCol: {alignItems: 'center', gap: 6},
  removeText: {color: theme.red, fontSize: 11},
  addBox: {marginTop: 12},
  addTitle: {color: theme.text, fontSize: 13, fontWeight: '600', marginBottom: 8},
  addRow: {flexDirection: 'row', alignItems: 'center', gap: 8},
  addInput: {
    flex: 1,
    backgroundColor: theme.bg,
    borderWidth: 1,
    borderColor: theme.border,
    borderRadius: 4,
    paddingHorizontal: 12,
    paddingVertical: 9,
    color: theme.text,
    fontSize: 14,
  },
  hint: {color: theme.textFaint, fontSize: 12, lineHeight: 18, marginTop: 8, marginBottom: 14},
});
