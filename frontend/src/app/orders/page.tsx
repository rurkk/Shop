"use client";

import { useRouter } from "next/navigation";
import { uuidPattern } from "@/lib/format";
import { Icon } from "@/components/ui";

export default function Page() {
  const router = useRouter();
  return <section className="wrap page-block narrow">
    <p className="eyebrow">Личный доступ</p><h1>Мой заказ</h1>
    <p className="lede">Введите номер заказа. Его можно найти в письме KNITSEW или в сохранённой ссылке на заказ.</p>
    <form className="panel stack" onSubmit={(event) => {
      event.preventDefault();
      const id = String(new FormData(event.currentTarget).get("id"));
      router.push(`/orders/${id}`);
    }}>
      <label className="field">Номер заказа <input name="id" required pattern={uuidPattern} maxLength={36} autoCapitalize="none" spellCheck={false} /></label>
      <button className="button solid">Открыть заказ <Icon name="arrow" /></button>
    </form>
  </section>;
}
