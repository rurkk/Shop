"use client";

import { useEffect, useRef, useState, type FormEvent, type MouseEvent } from "react";
import { filePath, read, write } from "@/lib/api";
import { useTask } from "@/lib/hooks";
import { Failure, Modal, Notice, Pending, Picture } from "./ui";
import styles from "./admin-order.module.css";

type Job = { status: "QUEUED" | "RUNNING" | "SUCCEEDED" | "FAILED"; failure: string | null };
type Draft = { fileId: string; interpretation: string | null; published: boolean };
type Workspace = { available: boolean; latestJob: Job | null; drafts: Draft[] };

function isActive(job: Job | null | undefined) {
  return job?.status === "QUEUED" || job?.status === "RUNNING";
}

const failureLabels: Record<string, string> = {
  CONTEXT_TOO_LARGE: "Переписка и вложения превышают лимит генерации.",
  INTERRUPTED: "Генерация прервалась. Запустите её ещё раз.",
  GENERATION_FAILED: "Не удалось получить эскиз. Попробуйте ещё раз.",
};

export function AdminSketchGeneration({ orderId, path, publishedFileId, busy, onPublish }: {
  orderId: string; path: string; publishedFileId?: string; busy: boolean;
  onPublish: (fileId: string) => Promise<unknown>;
}) {
  const [workspace, setWorkspace] = useState<Workspace>();
  const [loadError, setLoadError] = useState<Error>();
  const [refresh, setRefresh] = useState(0);
  const [revisionId, setRevisionId] = useState<string>();
  const [feedback, setFeedback] = useState("");
  const [preview, setPreview] = useState<{ draft: Draft; title: string; publish: boolean }>();
  const [published, setPublished] = useState<string>();
  const [generationFor, setGenerationFor] = useState<string>();
  const generationStatus = useRef<HTMLDivElement>(null);
  const revisionInput = useRef<HTMLTextAreaElement>(null);
  const revisionTrigger = useRef<HTMLButtonElement>(null);
  const generationTask = useTask();

  useEffect(() => {
    const controller = new AbortController();
    let timer: ReturnType<typeof setTimeout> | undefined;
    async function load() {
      try {
        const next = await read<Workspace>(path, controller.signal);
        if (controller.signal.aborted) return;
        setWorkspace(next);
        setLoadError(undefined);
        if (isActive(next.latestJob) && next.available) timer = setTimeout(() => void load(), 2000);
      } catch (error) {
        if (!controller.signal.aborted) setLoadError(error instanceof Error ? error : new Error("Не удалось загрузить эскизы."));
      }
    }
    void load();
    return () => {
      controller.abort();
      clearTimeout(timer);
    };
  }, [path, refresh]);

  useEffect(() => {
    if (revisionId) revisionInput.current?.focus();
  }, [revisionId]);

  useEffect(() => {
    if (published) generationStatus.current?.focus();
  }, [published]);

  const reload = () => setRefresh((value) => value + 1);
  const latestJob = workspace?.latestJob;
  const active = isActive(latestJob) && workspace?.available;
  const cannotStart = busy || generationTask.busy || active || Boolean(loadError);
  const start = (event: FormEvent<HTMLFormElement>, previousFileId?: string) => {
    event.preventDefault();
    if (cannotStart || !workspace?.available) return;
    const maxVariants = Number(new FormData(event.currentTarget).get("maxVariants"));
    setPublished(undefined);
    setGenerationFor(previousFileId);
    void generationTask.run(async () => {
      try {
        const job = await write<Job>(path, "POST", previousFileId
          ? { maxVariants, previousFileId, feedback: feedback.trim() }
          : { maxVariants });
        setWorkspace((current) => current && { ...current, latestJob: job });
        if (previousFileId) {
          setRevisionId(undefined);
          setFeedback("");
          generationStatus.current?.focus();
        }
      } finally {
        reload();
      }
    });
  };

  const openRevision = (event: MouseEvent<HTMLButtonElement>, fileId: string) => {
    if (fileId !== revisionId) {
      revisionTrigger.current = event.currentTarget;
      setRevisionId(fileId);
      setFeedback("");
    } else {
      revisionInput.current?.focus();
    }
  };

  return <section id="sketch-generation" className="panel stack" aria-labelledby="sketch-generation-title">
    <h2 id="sketch-generation-title">Эскизы с ИИ</h2>
    <Failure error={loadError} retry={reload} />
    {!generationFor && <Failure error={generationTask.error} />}
    {!workspace && !loadError && <Pending>Загружаем эскизы…</Pending>}
    {workspace && <>
      {workspace.available ? <div className={styles.generation}>
        <p className="muted">ИИ использует исходные фото, пожелания и переписку по заказу.</p>
        <form className={styles.generationForm} onSubmit={start}>
          <VariantLimit disabled={cannotStart} />
          <button className="button solid" disabled={cannotStart}>Сгенерировать эскиз</button>
        </form>
      </div> : <Notice>
        Сервис генерации эскизов не подключён. <a className="text-link" href="#published-sketch">К загрузке своего эскиза</a>
      </Notice>}
      <div ref={generationStatus} className={styles.generationStatus} role="status" tabIndex={-1}>
        {active && <p>{latestJob?.status === "QUEUED" ? "Эскизы в очереди…" : "Готовим эскизы по фото и переписке…"}</p>}
        {!active && !loadError && latestJob?.status === "SUCCEEDED" && <p>Эскизы готовы к просмотру.</p>}
        {published && published === publishedFileId &&
          <p>Эскиз опубликован. <a className="text-link" href="#published-sketch">Перейти к версии для покупателя</a></p>}
      </div>
      {!loadError && latestJob?.status === "FAILED" &&
        <Notice error>{failureLabels[latestJob.failure ?? ""] ?? failureLabels.GENERATION_FAILED}</Notice>}
      {workspace.drafts.length > 0 && <div className={styles.variants}>
        {workspace.drafts.map((draft, index) => {
          const title = `Вариант ${index + 1}`;
          const image = filePath(orderId, draft.fileId);
          const current = draft.fileId === publishedFileId;
          const editing = revisionId === draft.fileId;
          return <article className={styles.variant} key={draft.fileId} aria-label={title}>
            <button type="button" className={styles.variantPreview} aria-label={`Увеличить ${title.toLowerCase()}`}
              onClick={() => setPreview({ draft, title, publish: false })}>
              <Picture src={image} alt={`Вариант эскиза ${index + 1}`} />
              <span className={styles.enlarge} aria-hidden="true">Увеличить</span>
            </button>
            <div className={styles.variantBody}>
              <div className={styles.variantTitle}>
                <h3>{title}</h3>
                {current ? <span className={styles.published}>У покупателя</span> :
                  draft.published && <span className="muted">Ранее опубликован</span>}
              </div>
              {draft.interpretation && <p className="muted preline">{draft.interpretation}</p>}
              <div className={styles.variantActions}>
                {workspace.available && <button type="button" className="button outline" disabled={cannotStart}
                  aria-expanded={editing} aria-controls={editing ? "sketch-revision" : undefined}
                  onClick={(event) => openRevision(event, draft.fileId)}>Внести правки</button>}
                {!current && !draft.published && <button type="button" className="button outline" disabled={busy || Boolean(loadError)}
                  onClick={() => setPreview({ draft, title, publish: true })}>Опубликовать покупателю</button>}
              </div>
              {editing && <form id="sketch-revision" className={styles.revision} onSubmit={(event) => start(event, draft.fileId)}>
                <label className="field">Замечания к эскизу
                  <textarea ref={revisionInput} value={feedback} onChange={(event) => setFeedback(event.target.value)}
                    required maxLength={2000} rows={3} disabled={generationTask.busy} />
                </label>
                <VariantLimit disabled={cannotStart} />
                {generationFor === draft.fileId && <Failure error={generationTask.error} />}
                <div className={styles.variantActions}>
                  <button className="button solid" disabled={cannotStart || !feedback.trim()}>Сгенерировать с правками</button>
                  <button type="button" className="button outline" disabled={generationTask.busy} onClick={() => {
                    setRevisionId(undefined);
                    revisionTrigger.current?.focus();
                  }}>Отменить правки</button>
                </div>
              </form>}
            </div>
          </article>;
        })}
      </div>}
    </>}
    {preview && <SketchPreview title={preview.title} image={filePath(orderId, preview.draft.fileId)} publish={preview.publish}
      onClose={() => setPreview(undefined)} onPublish={async () => {
        await onPublish(preview.draft.fileId);
        setPublished(preview.draft.fileId);
        setPreview(undefined);
        reload();
      }} />}
  </section>;
}

function VariantLimit({ disabled }: { disabled?: boolean }) {
  return <label className="field">Максимум вариантов
    <span className={styles.selectControl}>
      <select name="maxVariants" defaultValue="1" disabled={disabled}>
        <option value="1">1</option><option value="2">2</option><option value="3">3</option>
      </select>
    </span>
  </label>;
}

function SketchPreview({ title, image, publish, onClose, onPublish }: {
  title: string; image: string; publish: boolean; onClose: () => void; onPublish: () => Promise<void>;
}) {
  const task = useTask();
  return <Modal title={publish ? "Публикация эскиза" : title} closeDisabled={task.busy} onClose={onClose}>
    <div className="stack">
      <h2>{publish ? `Опубликовать ${title.toLowerCase()}?` : title}</h2>
      <Picture src={image} alt={title} className={styles.previewImage} />
      {publish && <>
        <p>Покупатель увидит этот эскиз в заказе и сможет согласовать его или попросить правки.</p>
        <Failure error={task.error} />
        <div className={styles.variantActions}>
          <button type="button" className="button solid" disabled={task.busy}
            onClick={() => void task.run(onPublish)}>{task.busy ? "Публикуем…" : "Подтвердить публикацию"}</button>
          <button type="button" className="button outline" disabled={task.busy} onClick={onClose}>Отмена</button>
        </div>
      </>}
    </div>
  </Modal>;
}
