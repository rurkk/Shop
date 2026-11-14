"use client";

import { useEffect, useRef, useState } from "react";
import { read } from "./api";

export function useResource<T>(path: string | null) {
  const [attempt, setAttempt] = useState(0);
  const [result, setResult] = useState<{ path: string; attempt: number; value?: T; error?: Error }>();
  useEffect(() => {
    if (!path) return;
    const controller = new AbortController();
    read<T>(path, controller.signal).then(
      (value) => { if (!controller.signal.aborted) setResult({ path, attempt, value }); },
      (error: Error) => { if (!controller.signal.aborted) setResult({ path, attempt, error }); },
    );
    return () => controller.abort();
  }, [path, attempt]);
  const current = result?.path === path && result.attempt === attempt ? result : undefined;
  return {
    value: current?.value, error: current?.error,
    loading: path !== null && !current,
    reload: () => setAttempt((value) => value + 1),
  };
}

export function useTask() {
  const running = useRef(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<Error>();
  async function run(action: () => Promise<void>) {
    if (running.current) return;
    running.current = true;
    setBusy(true);
    setError(undefined);
    try { await action(); }
    catch (err) { setError(err instanceof Error ? err : new Error("Не удалось завершить действие.")); }
    finally { running.current = false; setBusy(false); }
  }
  return { run, busy, error };
}
