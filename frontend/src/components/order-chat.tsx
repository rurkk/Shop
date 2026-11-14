"use client";

import { useEffect, useId, useRef, useState } from "react";
import { filePath, imageAccept, read, upload, write } from "@/lib/api";
import type { Message, MessageHistory } from "@/lib/contracts";
import { dateLabel } from "@/lib/format";
import { useResource, useTask } from "@/lib/hooks";
import { photoDataNotice, PrivacyLink } from "./privacy-link";
import { Failure, Icon, Pending } from "./ui";

function merge(messages: Message[], newMessages: Message[]) {
  return [...new Map([...messages, ...newMessages].map((message) => [message.id, message])).values()]
    .sort((a, b) => a.sequence - b.sequence);
}

export function OrderChat({ id, role }: { id: string; role: Message["senderRole"] }) {
  const path = `/api/orders/${id}/messages`;
  const history = useResource<MessageHistory>(path);
  return <section className="panel stack" aria-label="Переписка">
    <h2>Переписка</h2>
    {history.loading && <Pending>Загружаем сообщения…</Pending>}
    <Failure error={history.error} retry={history.reload} />
    {history.value && <Conversation path={path} orderId={id} role={role} initial={history.value} />}
  </section>;
}

function Conversation({ path, orderId, role, initial }: {
  path: string; orderId: string; role: Message["senderRole"]; initial: MessageHistory;
}) {
  const [messages, setMessages] = useState(initial.messages);
  const [hasMore, setHasMore] = useState(initial.hasMore);
  const [stream, setStream] = useState<"connecting" | "online" | "offline" | "closed">("connecting");
  const [streamError, setStreamError] = useState<Error>();
  const [body, setBody] = useState("");
  const [file, setFile] = useState<File>();
  const attachmentHint = useId();
  const input = useRef<HTMLInputElement>(null);
  const list = useRef<HTMLDivElement>(null);
  const follow = useRef(true);
  const previous = useTask();
  const send = useTask();
  const pending = useRef<{ clientMessageId: string; body: string; file?: File; attachmentId: string | null }>(null);
  const cursor = useRef(initial.lastSequence);

  useEffect(() => {
    const source = new EventSource(`${path}/stream?after=${cursor.current}`);
    source.onopen = () => setStream("online");
    source.onerror = () => setStream(source.readyState === EventSource.CLOSED ? "closed" : "offline");
    source.addEventListener("message.created", (event: MessageEvent<string>) => {
      try {
        const message = JSON.parse(event.data) as Message;
        cursor.current = Math.max(cursor.current, message.sequence);
        setMessages((existing) => merge(existing, [message]));
      } catch {
        setStreamError(new Error("Не удалось прочитать новое сообщение. Обновите страницу."));
      }
    });
    return () => source.close();
  }, [path]);

  useEffect(() => {
    if (follow.current && list.current) list.current.scrollTop = list.current.scrollHeight;
  }, [messages]);

  return <>
    {stream !== "online" && <p className="muted" role="status">{
      stream === "connecting" ? "Подключаем чат…" : stream === "offline" ? "Связь прервалась. Восстанавливаем подключение…" : "Чат отключён. Обновите страницу для подключения."
    }</p>}
    <Failure error={streamError} />
    {hasMore && <button type="button" className="text-link" disabled={previous.busy} onClick={() => {
      void previous.run(async () => {
        const result = await read<MessageHistory>(`${path}?before=${messages[0].sequence}`);
        follow.current = false;
        setMessages((existing) => merge(existing, result.messages));
        setHasMore(result.hasMore);
      });
    }}>Загрузить предыдущие сообщения</button>}
    <Failure error={previous.error} />
    <div className="chat-list" ref={list} onScroll={(event) => {
      const element = event.currentTarget;
      follow.current = element.scrollHeight - element.clientHeight - element.scrollTop < 90;
    }}>
      {messages.length === 0 && <p className="muted">Сообщений пока нет.</p>}
      {messages.map((message) => <article key={message.id} className={`chat-message ${message.senderRole === role ? "own" : ""}`}>
        <div className="chat-meta"><strong>{message.senderRole === role ? "Вы" : role === "CUSTOMER" ? "Команда KNITSEW" : "Покупатель"}</strong>
          <time dateTime={message.createdAt}>{dateLabel(message.createdAt)}</time></div>
        {message.body && <p className="preline">{message.body}</p>}
        {message.attachmentId && <a className="text-link" href={filePath(orderId, message.attachmentId)} target="_blank" rel="noreferrer">Открыть вложение <Icon name="external" /></a>}
      </article>)}
    </div>
    <form className="stack" onSubmit={(event) => {
      event.preventDefault();
      void send.run(async () => {
        const text = body.trim();
        if (!text && !file) throw new Error("Напишите сообщение или прикрепите фото.");
        const draft = pending.current?.body === text && pending.current?.file === file ? pending.current
          : { clientMessageId: crypto.randomUUID(), body: text, file, attachmentId: null };
        pending.current = draft;
        if (file && !draft.attachmentId) {
          const uploaded = await upload<{ id: string }>(`/api/orders/${orderId}/files?purpose=CHAT`, file);
          draft.attachmentId = uploaded.id;
        }
        const result = await write<Message>(path, "POST", {
          clientMessageId: draft.clientMessageId, body: draft.body, attachmentId: draft.attachmentId,
        });
        follow.current = true;
        setMessages((existing) => merge(existing, [result]));
        setBody("");
        setFile(undefined);
        if (input.current) input.current.value = "";
        pending.current = null;
      });
    }}>
      <label className="field">Сообщение <textarea value={body} disabled={send.busy} maxLength={4000} rows={3} onChange={(event) => setBody(event.target.value)} /></label>
      <label className="field">Вложение <span className="optional">JPG или PNG, до 10 МиБ</span>
        <input ref={input} type="file" disabled={send.busy} accept={imageAccept} aria-describedby={role === "CUSTOMER" ? attachmentHint : undefined}
          onChange={(event) => setFile(event.target.files?.[0])} />
      </label>
      {role === "CUSTOMER" && <p className="muted" id={attachmentHint}>{photoDataNotice} <PrivacyLink /></p>}
      <Failure error={send.error} />
      <button className="button solid" disabled={send.busy || (!body.trim() && !file)}>{send.busy ? "Отправляем…" : "Отправить сообщение"} <Icon name="arrow" /></button>
    </form>
  </>;
}
