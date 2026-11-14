"use client";

import { useRef, useState } from "react";
import { imageAccept, upload, write } from "@/lib/api";
import { isUuid, uuidPattern } from "@/lib/format";
import { useTask } from "@/lib/hooks";
import { Failure, Icon, Modal, Notice, Picture } from "./ui";

const route = "/api/staff/catalog-images";
export const staffImagePath = (id: string) => `${route}/${id}`;

function ImageUpload({ onUpload, disabled = false }: { onUpload: (id: string) => void; disabled?: boolean }) {
  const task = useTask();
  const input = useRef<HTMLInputElement>(null);
  return <div className="stack">
    <input ref={input} type="file" accept={imageAccept} hidden onChange={(event) => {
      const file = event.target.files?.[0];
      event.target.value = "";
      if (file) void task.run(async () => onUpload((await upload<{ id: string }>(route, file)).id));
    }} />
    <button type="button" className="button outline small" disabled={disabled || task.busy} onClick={() => input.current?.click()}>
      <Icon name="upload" /> {task.busy ? "Загружаем…" : "Загрузить JPG или PNG"}
    </button>
    <Failure error={task.error} />
  </div>;
}

export function ImageField({ label, value, onChange, required, disabled }: {
  label: string; value: string; onChange: (value: string) => void; required?: boolean; disabled?: boolean;
}) {
  return <div className="stack">
    <label className="field">{label} <span className="optional">Загрузите изображение или введите его ID</span>
      <input value={value} disabled={disabled} onChange={(event) => onChange(event.target.value)} pattern={uuidPattern} required={required} maxLength={36} spellCheck={false} autoCapitalize="none" />
    </label>
    {isUuid(value) && <Picture key={value} src={staffImagePath(value)} alt={label} className="asset-preview" />}
    <ImageUpload onUpload={onChange} disabled={disabled} />
  </div>;
}

export function AdminMedia() {
  const [id, setId] = useState("");
  const [active, setActive] = useState("");
  const [notice, setNotice] = useState("");
  const [remove, setRemove] = useState(false);
  const task = useTask();
  return <div className="narrow stack">
    <p className="eyebrow">Команда KNITSEW</p><h1>Изображения</h1>
    <p className="lede">Загрузите фото и скопируйте ID, чтобы использовать его в баннере, галерее или карточке товара.</p>
    <div className="panel stack">
      <ImageUpload disabled={task.busy} onUpload={(value) => {
        setId(value); setActive(value); setNotice("Изображение загружено.");
      }} />
      <form className="stack" onSubmit={(event) => { event.preventDefault(); setActive(id); setNotice(""); }}>
        <label className="field">ID изображения <input value={id} onChange={(event) => setId(event.target.value)} pattern={uuidPattern} required maxLength={36} /></label>
        <button className="button outline small">Открыть по ID</button>
      </form>
      {active && <>
        <Picture key={active} src={staffImagePath(active)} alt="Изображение медиатеки" className="asset-preview" />
        <div className="actions">
          <button type="button" className="text-link" disabled={task.busy} onClick={() => void task.run(async () => {
            await navigator.clipboard.writeText(active); setNotice("ID скопирован.");
          })}>Скопировать ID</button>
          <button type="button" className="text-link" disabled={task.busy} onClick={() => setRemove(true)}>Удалить изображение</button>
        </div>
      </>}
      {notice && <Notice>{notice}</Notice>}
      {!remove && <Failure error={task.error} />}
    </div>
    {remove && <Modal title="Удалить изображение" onClose={() => { if (!task.busy) setRemove(false); }}>
      <div className="stack">
        <h2>Удалить изображение?</h2><p>Фото можно удалить после снятия всех привязок к баннеру, галерее, коллекциям и товарам.</p>
        <Failure error={task.error} />
        <button type="button" className="button solid" disabled={task.busy} onClick={() => void task.run(async () => {
          await write(staffImagePath(active), "DELETE");
          setRemove(false); setId(""); setActive(""); setNotice("Изображение удалено.");
        })}>Удалить</button>
      </div>
    </Modal>}
  </div>;
}
