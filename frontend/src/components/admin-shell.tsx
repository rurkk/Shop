"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import type { ReactNode } from "react";
import { ApiError, write } from "@/lib/api";
import { useResource, useTask } from "@/lib/hooks";
import { AccessForm } from "./access";
import { Failure, Pending } from "./ui";

const links = [
  ["/admin", "Заказы"],
  ["/admin/catalogs", "Коллекции"],
  ["/admin/products", "Товары"],
  ["/admin/storefront", "Витрина"],
  ["/admin/media", "Изображения"],
];

export function AdminShell({ children }: { children: ReactNode }) {
  const session = useResource<{ canManageStaff: boolean }>("/api/staff/session");
  const pathname = usePathname();
  const task = useTask();
  const needsAccess = session.error instanceof ApiError && [401, 403].includes(session.error.status);
  if (!session.value) return <section className="wrap narrow page-block">
    <p className="eyebrow">Мастерская KNITSEW</p>
    <h1>Вход для команды</h1>
    {session.loading && <Pending>Проверяем доступ…</Pending>}
    {!needsAccess && <Failure error={session.error} retry={session.reload} />}
    {needsAccess && <><p className="lede">Для входа понадобятся рабочая почта и код из приложения-аутентификатора.</p>
      <div className="panel"><AccessForm path="/api/staff/access" onVerified={session.reload} requireTotp /></div></>}
  </section>;
  return <div className="wrap admin-shell">
    <div className="admin-bar">
      <nav aria-label="Меню команды">{links.map(([path, title]) =>
        <Link key={path} href={path} aria-current={pathname === path || (path === "/admin" && pathname.startsWith("/admin/orders/")) ? "page" : undefined}>{title}</Link>
      )}{session.value.canManageStaff &&
        <Link href="/admin/staff" aria-current={pathname === "/admin/staff" ? "page" : undefined}>Сотрудники</Link>
      }</nav>
      <button type="button" className="text-link" disabled={task.busy} onClick={() => void task.run(async () => {
        await write("/api/session/logout", "POST");
        session.reload();
      })}>Выйти</button>
    </div>
    <Failure error={task.error} />
    {children}
  </div>;
}
