"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { write } from "@/lib/api";
import type { Availability, Page, Product, ProductMeasurements, ProductSummary, Variant } from "@/lib/contracts";
import { availabilityLabels, kopecksInput, money, rublesToKopecks, slugPattern } from "@/lib/format";
import { useResource, useTask } from "@/lib/hooks";
import { ImageField } from "./admin-assets";
import { Failure, Icon, Modal, Notice, Pagination, Pending } from "./ui";

const path = "/api/staff/products";

export function AdminProducts() {
  const [page, setPage] = useState(0);
  const result = useResource<Page<ProductSummary>>(`${path}?page=${page}`);
  return <>
    <div className="admin-heading"><div className="stack"><p className="eyebrow">Команда KNITSEW</p><h1>Товары</h1></div></div>
    <details className="panel"><summary>Создать товар</summary><div className="pt-4"><ProductEditor /></div></details>
    {result.loading && <Pending />}
    <Failure error={result.error} retry={result.reload} />
    {result.value && <>
      {!result.value.items.length && <p className="empty">Товаров пока нет.</p>}
      <ul className="admin-list">{result.value.items.map((item) => <li key={item.id} className="admin-row">
        <div><Link className="text-link" href={`/admin/products/${item.id}`}>{item.name} <Icon name="arrow" /></Link><p className="muted">{item.garmentType} · {item.slug}</p></div>
        <span className="badge">{item.published ? "Опубликован" : "Черновик"}</span>
      </li>)}</ul>
      <Pagination page={page} hasMore={result.value.hasMore} onPage={setPage} />
    </>}
  </>;
}

export function AdminProduct({ id }: { id: string }) {
  const result = useResource<Product>(`${path}/${id}`);
  return <>
    <Link href="/admin/products" className="text-link"><Icon name="back" /> К товарам</Link>
    {result.loading && <Pending />}
    <Failure error={result.error} retry={result.reload} />
    {result.value && <ProductPanel initial={result.value} />}
  </>;
}

function ProductPanel({ initial }: { initial: Product }) {
  const [product, setProduct] = useState(initial);
  return <>
    <div className="admin-heading"><h1>{product.name}</h1><span className="badge">{product.published ? "Опубликован" : "Черновик"}</span></div>
    <section className="panel stack"><h2>Карточка товара</h2><ProductEditor initial={product} onSave={setProduct} /></section>
    <Variants product={product} onChange={(variants) => setProduct((current) => ({ ...current, variants }))} />
  </>;
}

function ProductEditor({ initial, onSave }: { initial?: Product; onSave?: (product: Product) => void }) {
  const router = useRouter();
  const [photos, setPhotos] = useState(() => initial?.images.map(({ id, caption }) => ({ key: id, id, caption: caption ?? "" })) ?? []);
  const [measurementRows, setMeasurementRows] = useState<{ key: string; value?: ProductMeasurements }[]>(() =>
    (initial?.measurements ?? []).map((value, index) => ({ key: String(index), value })));
  const [saved, setSaved] = useState(false);
  const task = useTask();

  function changePhotos(update: (current: typeof photos) => typeof photos) {
    setPhotos(update);
    setSaved(false);
  }
  function move(index: number, delta: number) {
    changePhotos((current) => {
      const next = [...current];
      [next[index], next[index + delta]] = [next[index + delta], next[index]];
      return next;
    });
  }

  return <form className="stack" onChange={() => setSaved(false)} onSubmit={(event) => {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    void task.run(async () => {
      const product = await write<Product>(initial ? `${path}/${initial.id}` : path, initial ? "PUT" : "POST", {
        slug: String(form.get("slug")).trim(), name: String(form.get("name")).trim(), design: String(form.get("design")).trim(),
        garmentType: String(form.get("garmentType")).trim(), description: String(form.get("description")).trim(),
        images: photos.map(({ id, caption }) => ({ id, caption: caption.trim() || null })),
        measurements: measurementRows.map(({ key }) => {
          const sleeve = form.get(`measurement-sleeve-${key}`);
          return {
            size: String(form.get(`measurement-size-${key}`)).trim(),
            widthCm: Number(form.get(`measurement-width-${key}`)),
            lengthCm: Number(form.get(`measurement-length-${key}`)),
            sleeveLengthCm: sleeve ? Number(sleeve) : null,
          };
        }),
        published: form.has("published"),
      });
      if (onSave) { onSave(product); setSaved(true); }
      else router.push(`/admin/products/${product.id}`);
    });
  }}>
    <div className="field-row">
      <label className="field">Название <input name="name" defaultValue={initial?.name} maxLength={160} required /></label>
      <label className="field">Адрес (slug) <input name="slug" defaultValue={initial?.slug} maxLength={80} pattern={slugPattern} placeholder="sever" required /></label>
      <label className="field">Тип изделия <input name="garmentType" defaultValue={initial?.garmentType} maxLength={80} placeholder="Худи" required /></label>
      <label className="field">Дизайн <input name="design" defaultValue={initial?.design} maxLength={160} required /></label>
    </div>
    <label className="field">Описание <textarea name="description" defaultValue={initial?.description} maxLength={4000} rows={3} /></label>
    <div className="stack">
      <h3>Фотографии</h3><p className="muted">Первое фото станет обложкой. Для фото на человеке укажите его рост и размер изделия в подписи.</p>
      <div className="editor-list">{photos.map((item, index) => <div key={item.key} className="panel stack">
        <ImageField label={index === 0 ? "Обложка товара" : `Фото ${index + 1}`} required value={item.id} disabled={task.busy}
          onChange={(id) => changePhotos((current) => current.map((photo) => photo.key === item.key ? { ...photo, id } : photo))} />
        <label className="field">Подпись к фото <span className="optional">необязательно</span>
          <input value={item.caption} maxLength={200} disabled={task.busy} onChange={(event) => {
            const caption = event.currentTarget.value;
            changePhotos((current) => current.map((photo) => photo.key === item.key ? { ...photo, caption } : photo));
          }} />
        </label>
        <div className="actions">
          <button className="button outline small" type="button" aria-label={`Переместить фото ${index + 1} раньше`} disabled={task.busy || index === 0} onClick={() => move(index, -1)}><Icon name="up" /></button>
          <button className="button outline small" type="button" aria-label={`Переместить фото ${index + 1} позже`} disabled={task.busy || index === photos.length - 1} onClick={() => move(index, 1)}><Icon name="down" /></button>
          <button type="button" className="text-link" disabled={task.busy} onClick={() => changePhotos((current) => current.filter((photo) => photo.key !== item.key))}>Убрать фото</button>
        </div>
      </div>)}</div>
      <button type="button" className="button outline small" disabled={task.busy || photos.length >= 12}
        onClick={() => {
          const photo = { id: "", caption: "", key: crypto.randomUUID() };
          changePhotos((current) => [...current, photo]);
        }}><Icon name="plus" /> Добавить фото</button>
    </div>
    <div className="stack">
      <h3>Замеры изделия</h3>
      {measurementRows.length > 0 && <p className="muted">Измерьте изделие на ровной поверхности. Ширину измеряйте под проймами, длину — от плеча до низа, рукав — от плеча до манжеты.</p>}
      {measurementRows.map(({ key, value }) => <fieldset key={key} className="panel stack" aria-label="Замеры размера" disabled={task.busy}>
        <div className="field-row">
          <label className="field">Размер изделия <input name={`measurement-size-${key}`} defaultValue={value?.size} maxLength={32} required /></label>
          <label className="field">Ширина, см <input name={`measurement-width-${key}`} defaultValue={value?.widthCm} type="number" min="0.1" max="999.9" step="0.1" required /></label>
          <label className="field">Длина, см <input name={`measurement-length-${key}`} defaultValue={value?.lengthCm} type="number" min="0.1" max="999.9" step="0.1" required /></label>
          <label className="field">Рукав, см <span className="optional">необязательно</span>
            <input name={`measurement-sleeve-${key}`} defaultValue={value?.sleeveLengthCm ?? undefined} type="number" min="0.1" max="999.9" step="0.1" />
          </label>
        </div>
        <button className="text-link" type="button" onClick={() => {
          setMeasurementRows(measurementRows.filter((row) => row.key !== key));
          setSaved(false);
        }}>Удалить строку замеров</button>
      </fieldset>)}
      <button className="button outline small" type="button" disabled={task.busy || measurementRows.length >= 32} onClick={() => {
        setMeasurementRows([...measurementRows, { key: crypto.randomUUID(), value: undefined }]);
        setSaved(false);
      }}><Icon name="plus" /> Добавить замеры для размера</button>
    </div>
    {initial ? <label className="check-field"><input type="checkbox" name="published" defaultChecked={initial.published}
      disabled={task.busy || (!initial.published && !initial.variants.length)} /> Опубликовать товар</label>
      : <p className="muted">После сохранения черновика добавьте варианты и опубликуйте товар.</p>}
    {initial && !initial.variants.length && <p className="muted">Для публикации нужен хотя бы один вариант и фотография.</p>}
    <Failure error={task.error} />
    {saved && <Notice>Карточка товара сохранена.</Notice>}
    <button className="button solid" disabled={task.busy}>{initial ? "Сохранить товар" : "Создать черновик товара"}</button>
  </form>;
}

function Variants({ product, onChange }: { product: Product; onChange: (variants: Variant[]) => void }) {
  const [notice, setNotice] = useState("");
  const [remove, setRemove] = useState<Variant>();
  const [newFormVersion, setNewFormVersion] = useState(0);
  const task = useTask();
  const route = `${path}/${product.id}/variants`;
  return <section className="panel stack">
    <h2>Размеры и цвета</h2>
    {product.variants.length === 0 && <p className="muted">Вариантов пока нет.</p>}
    {product.variants.map((variant) => <details className="edit-block" key={variant.id}>
      <summary>{variant.size} · {variant.color} · {money(variant.priceKopecks)} · {variant.sku}</summary>
      <VariantEditor initial={variant} busy={task.busy} onSave={(body) => task.run(async () => {
        const next = await write<Variant>(`${route}/${variant.id}`, "PUT", body);
        onChange(product.variants.map((item) => item.id === variant.id ? next : item));
        setNotice("Вариант сохранён.");
      })} />
      <button type="button" className="text-link" disabled={task.busy} onClick={() => setRemove(variant)}>Удалить вариант</button>
    </details>)}
    <details className="edit-block">
      <summary>Добавить вариант</summary>
      <VariantEditor key={newFormVersion} busy={task.busy} onSave={(body) => task.run(async () => {
        onChange([...product.variants, await write<Variant>(route, "POST", body)]);
        setNewFormVersion((value) => value + 1);
        setNotice("Вариант добавлен.");
      })} />
    </details>
    {!remove && <Failure error={task.error} />}
    {notice && !task.error && <Notice>{notice}</Notice>}
    {remove && <Modal title="Удалить вариант" onClose={() => { if (!task.busy) setRemove(undefined); }}>
      <div className="stack">
        <h2>Удалить вариант?</h2><p>{remove.sku} · {remove.size} · {remove.color}</p>
        <Failure error={task.error} />
        <button type="button" className="button solid" disabled={task.busy} onClick={() => void task.run(async () => {
          await write(`${route}/${remove.id}`, "DELETE");
          onChange(product.variants.filter((item) => item.id !== remove.id));
          setRemove(undefined);
          setNotice("Вариант удалён.");
        })}>Удалить</button>
      </div>
    </Modal>}
  </section>;
}

function VariantEditor({ initial, busy, onSave }: { initial?: Variant; busy: boolean; onSave: (body: object) => void }) {
  const [availability, setAvailability] = useState<Availability>(initial?.availability ?? "IN_STOCK");
  return <form className="stack" onSubmit={(event) => {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    const rubles = String(data.get("price"));
    const input = event.currentTarget.elements.namedItem("price") as HTMLInputElement;
    let priceKopecks: number;
    try { priceKopecks = rublesToKopecks(rubles); }
    catch (error) { input.setCustomValidity((error as Error).message); input.reportValidity(); return; }
    if (priceKopecks <= 0) { input.setCustomValidity("Цена должна быть больше нуля."); input.reportValidity(); return; }
    onSave({
      sku: String(data.get("sku")).trim(), size: String(data.get("size")).trim(), color: String(data.get("color")).trim(),
      priceKopecks, availability, productionDays: availability === "MADE_TO_ORDER" ? Number(data.get("days")) : null,
    });
  }}>
    <div className="field-row">
      <label className="field">Артикул (SKU) <input name="sku" defaultValue={initial?.sku} maxLength={64} pattern={"[A-Z0-9][A-Z0-9_\\-]*"} required /></label>
      <label className="field">Размер <input name="size" defaultValue={initial?.size} maxLength={32} required /></label>
      <label className="field">Цвет <input name="color" defaultValue={initial?.color} maxLength={80} required /></label>
      <label className="field">Цена, ₽ <input name="price" defaultValue={initial ? kopecksInput(initial.priceKopecks) : undefined}
        onInput={(event) => event.currentTarget.setCustomValidity("")} inputMode="decimal" pattern="[0-9]+([.,][0-9]{1,2})?" required /></label>
      <label className="field">Наличие <select value={availability} onChange={(event) => setAvailability(event.target.value as Availability)}>
        {Object.entries(availabilityLabels).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select></label>
      {availability === "MADE_TO_ORDER" && <label className="field">Изготовление, дни <input name="days" type="number" min={1} max={2147483647} defaultValue={initial?.productionDays ?? undefined} required /></label>}
    </div>
    <button className="button outline" disabled={busy}>{initial ? "Сохранить вариант" : "Создать вариант"}</button>
  </form>;
}
