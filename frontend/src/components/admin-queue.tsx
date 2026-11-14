"use client";

import Link from "next/link";
import { useState } from "react";
import type { Order, Page } from "@/lib/contracts";
import { dateLabel, statusLabels, typeLabels } from "@/lib/format";
import { useResource } from "@/lib/hooks";
import { Failure, Icon, Pagination, Pending } from "./ui";

export function AdminQueue() {
  const [page, setPage] = useState(0);
  const [filter, setFilter] = useState({ search: "", type: "", status: "" });
  const query = new URLSearchParams({ page: String(page) });
  for (const [key, value] of Object.entries(filter)) if (value) query.set(key, value);
  const result = useResource<Page<Order>>(`/api/staff/orders?${query}`);
  return <>
    <div className="admin-heading"><div className="stack"><p className="eyebrow">Команда KNITSEW</p><h1>Заказы</h1></div>
      <button type="button" className="text-link" disabled={result.loading} onClick={result.reload}>Обновить очередь</button>
    </div>
    <form className="admin-filter" onSubmit={(event) => {
      event.preventDefault();
      const data = new FormData(event.currentTarget);
      setFilter({ search: String(data.get("search")).trim(), type: String(data.get("type")), status: String(data.get("status")) });
      setPage(0);
      result.reload();
    }}>
      <label className="field">Номер заказа или email <input type="search" name="search" maxLength={254} /></label>
      <label className="field">Тип <select name="type"><option value="">Все типы</option>{Object.entries(typeLabels).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select></label>
      <label className="field">Статус <select name="status"><option value="">Все статусы</option>{Object.entries(statusLabels).filter(([value]) => !["DRAFT", "EXPIRED"].includes(value))
        .map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select></label>
      <button className="button solid" disabled={result.loading}>Найти <Icon name="arrow" /></button>
    </form>
    <Failure error={result.error} retry={result.reload} />
    {result.loading && <Pending>Загружаем заказы…</Pending>}
    {result.value && <>
      {!result.value.items.length && <p className="empty">Заказы по этим условиям не найдены.</p>}
      <ul className="admin-list">{result.value.items.map((order) => <li key={order.id} className="admin-row">
        <div><Link href={`/admin/orders/${order.id}`} className="text-link">{order.email} <Icon name="arrow" /></Link><p className="order-number">{order.id}</p></div>
        <div className="actions"><span className="muted">{typeLabels[order.type]} · {dateLabel(order.createdAt)}</span><span className="badge">{statusLabels[order.status]}</span></div>
      </li>)}</ul>
      <Pagination page={page} hasMore={result.value.hasMore} onPage={setPage} />
    </>}
  </>;
}
