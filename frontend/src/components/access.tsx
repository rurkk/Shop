"use client";

import { useEffect, useId, useLayoutEffect, useRef, useState } from "react";
import { ApiError, write } from "@/lib/api";
import { useTask } from "@/lib/hooks";
import { PrivacyLink } from "./privacy-link";
import { Failure, Icon } from "./ui";

const resendDelay = 60_000;
const codeLifetime = 600_000;

function useSecondsUntil(deadline: number) {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const current = Date.now();
    setNow(current);
    if (current >= deadline) return;
    const timer = window.setInterval(() => {
      const current = Date.now();
      setNow(current);
      if (current >= deadline) window.clearInterval(timer);
    }, 1000);
    return () => window.clearInterval(timer);
  }, [deadline]);
  return Math.max(0, Math.ceil((deadline - now) / 1000));
}

export function useAccessCode(email: string) {
  const address = email.trim().toLowerCase();
  const [requests, setRequests] = useState(() => new Map<string, number>());
  const requestedAt = requests.get(address);
  const resendSeconds = useSecondsUntil(requestedAt === undefined ? 0 : requestedAt + resendDelay);

  async function requestCode(path: string) {
    await write(path + "/code", "POST", { email: address });
    const at = Date.now();
    setRequests((previous) => new Map(previous).set(address, at));
    return at;
  }

  return { address, resendSeconds, requestCode };
}

export function ResendWait({ seconds, id }: { seconds: number; id?: string }) {
  if (!seconds) return null;
  return <p className="muted" id={id}>Новый код можно запросить через {seconds} с.</p>;
}

function accessError(error?: Error) {
  if (error instanceof ApiError && error.status === 403) {
    return new Error("Не удалось подтвердить сессию браузера. Проверьте, разрешены ли cookies для этого сайта, и повторите действие.");
  }
  return error;
}

export function AccessForm({ path, onVerified, requireTotp = false }: {
  path: string; onVerified: () => void; requireTotp?: boolean;
}) {
  const [email, setEmail] = useState("");
  const [requestedAt, setRequestedAt] = useState<number>();
  const [editing, setEditing] = useState(false);
  const task = useTask();
  const access = useAccessCode(email);
  if (requestedAt !== undefined) return <AccessCodeForm path={path} email={access.address} requestedAt={requestedAt}
    resendSeconds={access.resendSeconds} onResend={async () => setRequestedAt(await access.requestCode(path))}
    onVerified={onVerified} onEditEmail={() => { setRequestedAt(undefined); setEditing(true); }} requireTotp={requireTotp} />;
  return <form className="stack" onSubmit={(event) => {
    event.preventDefault();
    if (!access.resendSeconds) void task.run(async () => setRequestedAt(await access.requestCode(path)));
  }}>
    <label className="field">Email
      <input type="email" autoComplete="email" maxLength={254} required value={email} autoFocus={editing} readOnly={task.busy} onChange={(event) => setEmail(event.target.value)} />
    </label>
    {!requireTotp && <PrivacyLink />}
    <Failure error={accessError(task.error)} />
    <button className="button solid" disabled={task.busy || access.resendSeconds > 0}>{task.busy ? "Запрашиваем код…" : "Получить код"} <Icon name="arrow" /></button>
    <ResendWait seconds={access.resendSeconds} />
  </form>;
}

export function AccessCodeForm({
  path, email, requestedAt, resendSeconds, onResend, onVerified, onEditEmail, onBusyChange, active = true, requireTotp = false,
}: {
  path: string; email: string; requestedAt: number; resendSeconds: number; onResend: () => Promise<void>;
  onVerified: () => void; onEditEmail: () => void; onBusyChange?: (busy: boolean) => void; active?: boolean; requireTotp?: boolean;
}) {
  const [code, setCode] = useState("");
  const [totp, setTotp] = useState("");
  const input = useRef<HTMLInputElement>(null);
  const hintId = useId();
  const resendId = useId();
  const task = useTask();
  const expired = useSecondsUntil(requestedAt + codeLifetime) === 0;
  const invalidCode = task.error instanceof ApiError && task.error.status === 401;
  useEffect(() => { if (active && !expired) input.current?.focus(); }, [active, expired]);
  useLayoutEffect(() => {
    onBusyChange?.(task.busy);
    return () => onBusyChange?.(false);
  }, [onBusyChange, task.busy]);

  return <form className="stack" onSubmit={(event) => {
    event.preventDefault();
    if (expired) return;
    void task.run(async () => {
      await write(path + "/verify", "POST", requireTotp ? { email, code, totp } : { email, code });
      onVerified();
    });
  }}>
    <p className="preline">Email <strong className="break-all">{email}</strong></p>
    {!requireTotp && <PrivacyLink />}
    {expired ? <p role="alert">Срок действия кода истёк. Запросите новый код.</p> : <>
      <p className="muted" id={hintId}>Если адрес указан верно, код придёт на email. Он действует 10 минут. Если письма нет, проверьте папку «Спам» и адрес.</p>
      <label className="field">Код из письма
        <input ref={input} type="text" inputMode="numeric" autoComplete="one-time-code" pattern="[0-9]{6}" maxLength={6} required
          aria-describedby={hintId} aria-invalid={invalidCode || undefined}
          value={code} onChange={(event) => setCode(event.target.value)} />
      </label>
      {requireTotp && <label className="field">Код из приложения-аутентификатора
        <input type="text" inputMode="numeric" autoComplete="off" pattern="[0-9]{6}" maxLength={6} required
          aria-invalid={invalidCode || undefined} value={totp} onChange={(event) => setTotp(event.target.value)} />
      </label>}
      <button className="button solid" disabled={task.busy}>{task.busy ? "Подождите…" : requireTotp ? "Подтвердить коды" : "Подтвердить код"} <Icon name="arrow" /></button>
    </>}
    {(!expired || !invalidCode) && <Failure error={requireTotp && invalidCode
      ? new Error("Проверьте оба кода. Если срок кода из письма истёк, запросите новый.") : accessError(task.error)} />}
    <div className="actions">
      <button type="button" className="text-link" disabled={task.busy || resendSeconds > 0}
        aria-describedby={resendSeconds ? resendId : undefined} onClick={() => void task.run(async () => {
          await onResend();
          setCode("");
          setTotp("");
          input.current?.focus();
        })}>Отправить код ещё раз</button>
      <button type="button" className="text-link" disabled={task.busy} onClick={onEditEmail}>Изменить email</button>
    </div>
    <ResendWait seconds={resendSeconds} id={resendId} />
  </form>;
}
