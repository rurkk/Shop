"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { write } from "@/lib/api";
import type { Catalog, CatalogContents, Page, ProductSummary } from "@/lib/contracts";
import { slugPattern } from "@/lib/format";
import { useResource, useTask } from "@/lib/hooks";
import { ImageField } from "./admin-assets";
import { Failure, Icon, Notice, Pagination, Pending } from "./ui";

const path = "/api/staff/catalogs";

export function AdminCatalogs() {
  const [page, setPage] = useState(0);
  const result = useResource<Page<Catalog>>(`${path}?page=${page}`);
  return <>
    <div className="admin-heading"><div className="stack"><p className="eyebrow">Команда KNITSEW</p><h1>Коллекции и подборки</h1></div></div>
    <details className="panel"><summary>Создать коллекцию</summary><div className="pt-4"><CatalogEditor /></div></details>
    {result.loading && <Pending />}
    <Failure error={result.error} retry={result.reload} />
    {result.value && <>
      {!result.value.items.length && <p className="empty">Коллекций пока нет.</p>}
      <ul className="admin-list">{result.value.items.map((catalog) => <li key={catalog.id} className="admin-row">
        <div><Link className="text-link" href={`/admin/catalogs/${catalog.id}`}>{catalog.name} <Icon name="arrow" /></Link><p className="muted">{catalog.slug} · {catalog.kind === "COLLECTION" ? "Коллекция" : "Подборка"}</p></div>
        <span className="badge">{catalog.published ? "Опубликована" : "Черновик"}</span>
      </li>)}</ul>
      <Pagination page={page} hasMore={result.value.hasMore} onPage={setPage} />
    </>}
  </>;
}

export function AdminCatalog({ id }: { id: string }) {
  const catalog = useResource<CatalogContents>(`${path}/${id}`);
  return <>
    <Link href="/admin/catalogs" className="text-link"><Icon name="back" /> К коллекциям</Link>
    {catalog.loading && <Pending />}
    <Failure error={catalog.error} retry={catalog.reload} />
    {catalog.value && <>
      <div className="admin-heading"><h1>{catalog.value.catalog.name}</h1></div>
      <section className="panel stack"><h2>Настройки</h2><CatalogEditor initial={catalog.value.catalog} /></section>
      <Placement id={id} />
    </>}
  </>;
}

function CatalogEditor({ initial }: { initial?: Catalog }) {
  const router = useRouter();
  const [cover, setCover] = useState(initial?.cover?.id ?? "");
  const [saved, setSaved] = useState(false);
  const task = useTask();
  return <form className="stack" onChange={() => setSaved(false)} onSubmit={(event) => {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    void task.run(async () => {
      const saved = await write<Catalog>(initial ? `${path}/${initial.id}` : path, initial ? "PUT" : "POST", {
        slug: String(form.get("slug")).trim(), name: String(form.get("name")).trim(), kind: form.get("kind"),
        description: String(form.get("description")).trim(), coverImageId: cover || null,
        displayOrder: Number(form.get("displayOrder")), published: form.has("published"),
      });
      if (initial) setSaved(true);
      else router.push(`/admin/catalogs/${saved.id}`);
    });
  }}>
    <div className="field-row">
      <label className="field">Название <input name="name" defaultValue={initial?.name} maxLength={160} required /></label>
      <label className="field">Адрес (slug) <input name="slug" defaultValue={initial?.slug} maxLength={80} pattern={slugPattern} placeholder="rus" required /></label>
      <label className="field">Тип <select name="kind" defaultValue={initial?.kind ?? "COLLECTION"}><option value="COLLECTION">Коллекция</option><option value="SELECTION">Подборка</option></select></label>
      <label className="field">Порядок показа <input name="displayOrder" type="number" min={0} max={2147483647} defaultValue={initial?.displayOrder ?? 0} required /></label>
    </div>
    <label className="field">Описание <textarea name="description" defaultValue={initial?.description} maxLength={4000} rows={3} /></label>
    <ImageField label="Обложка коллекции" value={cover} onChange={(value) => { setCover(value); setSaved(false); }} disabled={task.busy} />
    <label className="check-field"><input type="checkbox" name="published" defaultChecked={initial?.published} /> Опубликовать коллекцию</label>
    <Failure error={task.error} />
    {saved && <Notice>Коллекция сохранена.</Notice>}
    <button className="button solid" disabled={task.busy}>{initial ? "Сохранить коллекцию" : "Создать коллекцию"}</button>
  </form>;
}

function Placement({ id }: { id: string }) {
  const [page, setPage] = useState(0);
  const [productPage, setProductPage] = useState(0);
  const [notice, setNotice] = useState("");
  const route = `${path}/${id}/products`;
  const current = useResource<CatalogContents>(`${path}/${id}?page=${page}`);
  const candidates = useResource<Page<ProductSummary>>(`/api/staff/products?page=${productPage}&size=50`);
  const task = useTask();
  const place = async (productId: string, order: number) => {
    await write(`${route}/${productId}`, "PUT", { displayOrder: order });
    setNotice("Размещение сохранено.");
    current.reload();
  };
  return <section className="panel stack">
    <h2>Товары в коллекции</h2>
    {current.loading && <Pending />}
    <Failure error={current.error} retry={current.reload} />
    {current.value && <>
      {!current.value.products.items.length && <p className="muted">На этой странице нет размещённых товаров.</p>}
      <ul className="admin-list">
        {current.value.products.items.map((item) => <li className="admin-row" key={item.id}>
          <div><Link className="text-link" href={`/admin/products/${item.id}`}>{item.name}</Link><p className="muted">{item.published ? "Опубликован" : "Черновик"}</p></div>
          <form className="actions" onSubmit={(event) => {
            event.preventDefault();
            const form = new FormData(event.currentTarget);
            void task.run(() => place(item.id, Number(form.get("order"))));
          }}>
            <label className="field">Порядок <input name="order" type="number" min={0} max={2147483647} defaultValue={item.displayOrder ?? 0} required /></label>
            <button className="button outline small" disabled={task.busy}>Сохранить порядок</button>
            <button className="text-link" type="button" disabled={task.busy} onClick={() => void task.run(async () => {
              await write(`${route}/${item.id}`, "DELETE");
              setNotice("Товар убран из коллекции.");
              current.reload();
            })}>Убрать</button>
          </form>
        </li>)}
      </ul>
      <Pagination page={page} hasMore={current.value.products.hasMore} onPage={setPage} />
    </>}
    <div className="edit-block">
      <h3>Добавить товар</h3>
      {candidates.loading && <Pending />}
      <Failure error={candidates.error} retry={candidates.reload} />
      {candidates.value && (candidates.value.items.length ? <>
        <form className="stack" onSubmit={(event) => {
          event.preventDefault();
          const form = new FormData(event.currentTarget);
          void task.run(() => place(String(form.get("productId")), Number(form.get("order"))));
        }}>
          <div className="field-row">
            <label className="field">Товар <select name="productId" defaultValue="" required><option value="" disabled>Выберите товар</option>
              {candidates.value.items.map((item) => <option key={item.id} value={item.id}>{item.name} ({item.slug})</option>)}
            </select></label>
            <label className="field">Порядок показа <input name="order" type="number" min={0} max={2147483647} defaultValue={0} required /></label>
          </div>
          <button className="button solid" disabled={task.busy}>Добавить в коллекцию</button>
        </form>
        <Pagination page={productPage} hasMore={candidates.value.hasMore} onPage={setProductPage} />
      </> : <p className="muted">Сначала <Link className="text-link" href="/admin/products">создайте товар</Link>.</p>)}
    </div>
    <Failure error={task.error} />
    {notice && !task.error && <Notice>{notice}</Notice>}
  </section>;
}
