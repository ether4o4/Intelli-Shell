/**
 * Emergency-stop core. One place that everything cancellable registers with, so
 * a single stopAll() instantly halts the model request, on-device generation,
 * and the agent loop. The native shell process is killed separately by the
 * controller via Bridge.killAll().
 */
type Aborter = () => void;

let signal = {aborted: false};
const aborters = new Set<Aborter>();

/** Start a fresh run; returns the signal the loop and providers check. */
export function beginRun(): {aborted: boolean} {
  signal = {aborted: false};
  return signal;
}

export function currentSignal(): {aborted: boolean} {
  return signal;
}

export function isAborted(): boolean {
  return signal.aborted;
}

/** Register an in-flight canceller (e.g. xhr.abort). Returns an unregister fn. */
export function registerAborter(fn: Aborter): () => void {
  aborters.add(fn);
  return () => {
    aborters.delete(fn);
  };
}

/** Hard stop: flip the flag and fire every registered canceller immediately. */
export function stopAll(): void {
  signal.aborted = true;
  const list = Array.from(aborters);
  aborters.clear();
  list.forEach(fn => {
    try {
      fn();
    } catch (_e) {}
  });
}
