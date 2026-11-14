"use client";

import { useState } from "react";
import { write } from "@/lib/api";
import type { Storefront } from "@/lib/contracts";
import { useResource, useTask } from "@/lib/hooks";
import { ImageField } from "./admin-assets";
import { Failure, Icon, Notice, Pending } from "./ui";

const path = "/api/staff/storefront";
type Example = { key: string; embroideryImageId: string; sourceImageId: string; caption: string };

export function AdminStorefront() {
  const result = useResource<Storefront>(path);
  return <>
    <div className="admin-heading"><div className="stack"><p className="eyebrow">Команда KNITSEW</p><h1>Витрина</h1></div>
      <button type="button" className="text-link" disabled={result.loading} onClick={result.reload}>Загрузить сохранённую версию</button>
    </div>
    {result.loading && <Pending />}
    <Failure error={result.error} retry={result.reload} />
    {result.value && <Editor initial={result.value} />}
  </>;
}

function Editor({ initial }: { initial: Storefront }) {
  const [title, setTitle] = useState(initial.heroTitle ?? "");
  const [heroId, setHeroId] = useState(initial.heroImage?.id ?? "");
  const [photoOrderPriceHint, setPhotoOrderPriceHint] = useState(initial.photoOrderPriceHint ?? "");
  const [photoOrderTimingHint, setPhotoOrderTimingHint] = useState(initial.photoOrderTimingHint ?? "");
  const [deliveryPriceHint, setDeliveryPriceHint] = useState(initial.deliveryPriceHint ?? "");
  const [published, setPublished] = useState(initial.published);
  const [version, setVersion] = useState(initial.version);
  const [gallery, setGallery] = useState<Example[]>(() => initial.gallery.map((item, index) => ({
    key: `saved-${index}`, embroideryImageId: item.embroidery.id, sourceImageId: item.source?.id ?? "", caption: item.caption ?? "",
  })));
  const [saved, setSaved] = useState(false);
  const task = useTask();

  function patch(key: string, value: Partial<Example>) {
    setGallery((current) => current.map((item) => item.key === key ? { ...item, ...value } : item));
    setSaved(false);
  }
  function move(index: number, delta: number) {
    setGallery((current) => {
      const next = [...current];
      [next[index], next[index + delta]] = [next[index + delta], next[index]];
      return next;
    });
    setSaved(false);
  }

  return <form className="stack" onSubmit={(event) => {
    event.preventDefault();
    void task.run(async () => {
      setSaved(false);
      const next = await write<Storefront>(path, "PUT", {
        version, published, heroTitle: title.trim() || null, heroImageId: heroId || null,
        photoOrderPriceHint: photoOrderPriceHint.trim() || null,
        photoOrderTimingHint: photoOrderTimingHint.trim() || null,
        deliveryPriceHint: deliveryPriceHint.trim() || null,
        gallery: gallery.map(({ embroideryImageId, sourceImageId, caption }) => ({
          embroideryImageId, sourceImageId: sourceImageId || null, caption: caption.trim() || null,
        })),
      });
      setVersion(next.version);
      setSaved(true);
    });
  }}>
    <section className="panel stack">
      <h2>Баннер</h2>
      <label className="field">Заголовок баннера <input value={title} maxLength={120} disabled={task.busy} required={published}
        onChange={(event) => { setTitle(event.target.value); setSaved(false); }} /></label>
      <ImageField label="Фото баннера" value={heroId} required={published} disabled={task.busy} onChange={(id) => { setHeroId(id); setSaved(false); }} />
    </section>
    <section className="panel stack">
      <h2>Ориентиры по заказу</h2>
      <p className="muted">Укажите подтверждённые командой ориентиры. Для доставки уточните, к какому городу или направлению относится расчёт. Пустые поля скрыты от покупателей.</p>
      <label className="field">Цена вышивки по фото <input value={photoOrderPriceHint} maxLength={160} disabled={task.busy}
        onChange={(event) => { setPhotoOrderPriceHint(event.target.value); setSaved(false); }} /></label>
      <label className="field">Срок вышивки по фото <input value={photoOrderTimingHint} maxLength={160} disabled={task.busy}
        onChange={(event) => { setPhotoOrderTimingHint(event.target.value); setSaved(false); }} /></label>
      <label className="field">Стоимость и срок доставки из коллекции <input value={deliveryPriceHint} maxLength={160} disabled={task.busy}
        onChange={(event) => { setDeliveryPriceHint(event.target.value); setSaved(false); }} /></label>
    </section>
    <section className="panel stack">
      <h2>Примеры работ</h2>
      <p className="muted">Публикуйте только изображения, на которые у вас есть права. Проверьте, что по изображениям и подписям нельзя опознать человека.</p>
      {gallery.map((item, index) => <div key={item.key} className="edit-block">
        <h3>Пример {index + 1}</h3>
        <div className="field-row">
          <ImageField label="Готовая вышивка" value={item.embroideryImageId} required disabled={task.busy} onChange={(value) => patch(item.key, { embroideryImageId: value })} />
          <ImageField label="Исходное фото" value={item.sourceImageId} disabled={task.busy} onChange={(value) => patch(item.key, { sourceImageId: value })} />
        </div>
        <label className="field">Подпись <input value={item.caption} maxLength={160} disabled={task.busy} onChange={(event) => patch(item.key, { caption: event.target.value })} /></label>
        <div className="actions">
          <button type="button" className="button outline small" aria-label={`Поднять пример ${index + 1}`} disabled={index === 0 || task.busy} onClick={() => move(index, -1)}><Icon name="up" /></button>
          <button type="button" className="button outline small" aria-label={`Опустить пример ${index + 1}`} disabled={index + 1 === gallery.length || task.busy} onClick={() => move(index, 1)}><Icon name="down" /></button>
          <button type="button" className="text-link" disabled={task.busy} onClick={() => {
            setGallery((current) => current.filter((example) => example.key !== item.key)); setSaved(false);
          }}>Убрать пример</button>
        </div>
      </div>)}
      <button type="button" className="button outline" disabled={task.busy || gallery.length >= 24} onClick={() => {
        setGallery([...gallery, { key: crypto.randomUUID(), embroideryImageId: "", sourceImageId: "", caption: "" }]); setSaved(false);
      }}><Icon name="plus" /> Добавить пример</button>
    </section>
    <label className="check-field"><input type="checkbox" checked={published} disabled={task.busy}
      onChange={(event) => { setPublished(event.target.checked); setSaved(false); }} /> Опубликовать витрину</label>
    <Failure error={task.error} />
    {saved && <Notice>Настройки витрины сохранены.</Notice>}
    <button className="button solid" disabled={task.busy}>{task.busy ? "Сохраняем…" : "Сохранить витрину"}</button>
  </form>;
}
