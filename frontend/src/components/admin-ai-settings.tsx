"use client";

import { useState } from "react";
import { write } from "@/lib/api";
import { useResource, useTask } from "@/lib/hooks";
import { Failure, Notice, Pending } from "./ui";

const path = "/api/staff/sketch-settings";

type Selection = { version: number; responseModel: string; imageModel: string; systemPrompt: string };
type Model = { id: string; label: string };
type Reference = { id: string; name: string; mediaType: string };
type Settings = { selection: Selection; responseModels: Model[]; imageModels: Model[]; references: Reference[] };

export function AdminAiSettings() {
  const result = useResource<Settings>(path);
  return <>
    <div className="admin-heading"><h1>Эскизы с ИИ</h1></div>
    {result.loading && <Pending>Загружаем настройки…</Pending>}
    <Failure error={result.error} retry={result.reload} />
    {result.value && <ModelForm initial={result.value} reload={result.reload} />}
  </>;
}

function ModelForm({ initial, reload }: { initial: Settings; reload: () => void }) {
  const [settings, setSettings] = useState(initial);
  const [responseModel, setResponseModel] = useState(initial.selection.responseModel);
  const [imageModel, setImageModel] = useState(initial.selection.imageModel);
  const [systemPrompt, setSystemPrompt] = useState(initial.selection.systemPrompt);
  const [references, setReferences] = useState(initial.references);
  const [files, setFiles] = useState<File[]>([]);
  const [fileError, setFileError] = useState<string>();
  const [saved, setSaved] = useState(false);
  const task = useTask();
  const changed = responseModel !== settings.selection.responseModel || imageModel !== settings.selection.imageModel
    || systemPrompt !== settings.selection.systemPrompt || references.length !== settings.references.length || files.length > 0;
  const referencesFull = references.length + files.length >= 4;

  return <form className="panel stack" onSubmit={(event) => {
    event.preventDefault();
    void task.run(async () => {
      const body = new FormData();
      body.append("settings", new Blob([JSON.stringify({
        selection: { version: settings.selection.version, responseModel, imageModel, systemPrompt },
        retainedReferenceIds: references.map(({ id }) => id),
      })], { type: "application/json" }));
      files.forEach((file) => body.append("files", file));
      const next = await write<Settings>(path, "PUT", body);
      setSettings(next);
      setSystemPrompt(next.selection.systemPrompt);
      setReferences(next.references);
      setFiles([]);
      setFileError(undefined);
      setSaved(true);
    });
  }}>
    <p>Эти модели используются для всех заказов команды. Изменения применяются со следующего запуска генерации.</p>
    <div className="field-row">
      <label className="field">Модель для разбора переписки
        <select value={responseModel} disabled={task.busy} onChange={(event) => {
          setResponseModel(event.target.value);
          setSaved(false);
        }}>
          {settings.responseModels.map((model) => <option key={model.id} value={model.id}>{model.label}</option>)}
        </select>
      </label>
      <label className="field">Модель для генерации и правок
        <select value={imageModel} disabled={task.busy} onChange={(event) => {
          setImageModel(event.target.value);
          setSaved(false);
        }}>
          {settings.imageModels.map((model) => <option key={model.id} value={model.id}>{model.label}</option>)}
        </select>
      </label>
    </div>
    <label className="field">Системный промпт
      <textarea rows={12} required maxLength={12000} value={systemPrompt} disabled={task.busy} onChange={(event) => {
        setSystemPrompt(event.target.value);
        setSaved(false);
      }} />
    </label>
    <fieldset className="stack" disabled={task.busy}>
      <legend>Файлы-примеры</legend>
      <p>Gemini использует эти файлы вместе с промптом. Можно приложить фото ниток, готовые эскизы и текстовые инструкции.</p>
      <label className="field">Добавить примеры
        <input type="file" multiple disabled={referencesFull} accept=".jpg,.jpeg,.png,.txt" onChange={(event) => {
          const selected = Array.from(event.currentTarget.files ?? []);
          event.currentTarget.value = "";
          if (!selected.length) return;
          if (references.length + files.length + selected.length > 4) {
            setFileError("Можно прикрепить до четырёх файлов.");
            return;
          }
          if (selected.some((file) => !/\.(jpe?g|png|txt)$/i.test(file.name) || !file.size || file.size > 2 * 1024 * 1024)) {
            setFileError("Выберите JPG, PNG или TXT размером до 2 МиБ.");
            return;
          }
          setFiles([...files, ...selected]);
          setFileError(undefined);
          setSaved(false);
        }} />
      </label>
      <small>До 4 файлов по 2 МиБ. Форматы JPG, PNG и TXT (UTF-8).</small>
      {fileError && <p role="alert">{fileError}</p>}
      {references.length + files.length > 0 && <ul>
        {references.map(({ id, name }) => <li key={id}>
          <a href={`${path}/references/${id}`}>{name}</a>{" "}
          <button type="button" className="text-link" aria-label={`Убрать ${name}`} onClick={() => {
            setReferences(references.filter((reference) => reference.id !== id));
            setFileError(undefined);
            setSaved(false);
          }}>Убрать</button>
        </li>)}
        {files.map((file, index) => <li key={`${file.name}-${index}`}>
          {file.name}{" "}
          <button type="button" className="text-link" aria-label={`Убрать ${file.name}`} onClick={() => {
            setFiles(files.filter((_, position) => position !== index));
            setFileError(undefined);
          }}>Убрать</button>
        </li>)}
      </ul>}
    </fieldset>
    <Failure error={task.error} />
    {saved && <Notice>Настройки эскизов сохранены.</Notice>}
    <div className="actions">
      <button className="button solid" disabled={!changed || !systemPrompt.trim() || task.busy}>Сохранить настройки</button>
      <button type="button" className="text-link" disabled={task.busy} onClick={reload}>Загрузить сохранённые настройки</button>
    </div>
  </form>;
}
