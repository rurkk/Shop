"use client";

import Link from "next/link";
import { useRef, useState, type ReactNode } from "react";
import { imageAccept, read, upload, write } from "@/lib/api";
import type { OrderDetails, OrderEvent, OrderStatus, Page } from "@/lib/contracts";
import { dateLabel, days, historyLabels, money, rublesToKopecks } from "@/lib/format";
import { useResource, useTask } from "@/lib/hooks";
import { DeliverySummary, OrderFiles, OrderHead, OrderSummary, SketchImage } from "./order-common";
import { OrderChat } from "./order-chat";
import { Failure, Icon, Modal, Notice, Pagination, Pending } from "./ui";

const cancelStages: OrderStatus[] = [
  "IN_REVIEW", "AWAITING_DELIVERY_QUOTE", "AWAITING_PAYMENT", "PREPARING_SKETCH", "SKETCH_REVIEW", "CHANGES_REQUESTED",
  "IN_PRODUCTION", "PREPARING_ORDER", "READY_TO_SHIP",
];
const amountPattern = "[0-9]+([.,][0-9]{1,2})?";

export function AdminOrder({ id }: { id: string }) {
  const path = `/api/staff/orders/${id}`;
  const resource = useResource<OrderDetails>(path);
  return <>
    <Link href="/admin" className="text-link"><Icon name="back" /> К очереди</Link>
    <Failure error={resource.error} retry={resource.reload} />
    {resource.loading && <Pending>Загружаем заказ…</Pending>}
    {resource.value && <WorkOrder path={path} initial={resource.value} />}
  </>;
}

function WorkOrder({ path, initial }: { path: string; initial: OrderDetails }) {
  const [details, setDetails] = useState(initial);
  const [version, setVersion] = useState(0);
  const [refundPrompt, setRefundPrompt] = useState(false);
  const sketchUpload = useRef<{ file: File; id: string }>(null);
  const task = useTask();
  const { order, delivery, payment } = details;
  const publicPath = `/api/orders/${order.id}`;

  const update = (next: OrderDetails) => {
    setDetails(next);
    setVersion((value) => value + 1);
  };
  const refresh = async () => update(await read<OrderDetails>(path));
  const command = async (action: string, body?: object) => update(await write<OrderDetails>(path + action, "POST", body));
  const canQuoteDelivery = order.type === "CATALOG" ? order.status === "IN_REVIEW" : order.status === "AWAITING_DELIVERY_QUOTE";
  const canPublish = order.type === "CUSTOM_PHOTO" && ["PREPARING_SKETCH", "CHANGES_REQUESTED", "SKETCH_REVIEW"].includes(order.status);
  const canRefund = (order.status === "CANCELLATION_REQUESTED" || order.status === "SHIPPED")
    && payment?.paidAt && (!payment.refund || ["CANCELED", "CREATING"].includes(payment.refund.status));

  return <>
    <OrderHead details={details} staff busy={task.busy} onRefresh={() => void task.run(refresh)} />
    <Failure error={task.error} />
    {order.rejectionReason && <Notice>Причина отказа: {order.rejectionReason}</Notice>}
    {order.cancellationReason && <Notice>Причина отмены: {order.cancellationReason}</Notice>}
    <div className="order-layout">
      <div className="stack">
        <section className="panel stack">
          <h2>Действия по заказу</h2>
          {order.type === "CUSTOM_PHOTO" && order.status === "IN_REVIEW" &&
            <CommandForm title="Оценить изделие" busy={task.busy} onSubmit={(data) => task.run(() => command("/quote", {
              itemKopecks: rublesToKopecks(String(data.get("price"))), productionDays: Number(data.get("days")),
            }))} label="Сохранить оценку">
              <div className="field-row"><AmountField label="Цена изделия, ₽" /><DaysField label="Срок изготовления, дни" /></div>
            </CommandForm>}
          {canQuoteDelivery && (delivery ? <CommandForm key={delivery.version} title="Рассчитать доставку СДЭК" busy={task.busy}
            onSubmit={(data) => task.run(async () => {
              const offerValidThrough = String(data.get("offerValidThrough"));
              const deliverBy = String(data.get("deliverBy"));
              if (deliverBy <= offerValidThrough) throw new Error("Срок передачи заказа должен быть позже даты окончания предложения.");
              await command("/delivery-quote", {
                deliveryVersion: delivery.version, pickupPointCode: String(data.get("code")).trim(), pickupPointAddress: String(data.get("address")).trim(),
                priceKopecks: rublesToKopecks(String(data.get("price"))), deliveryDays: Number(data.get("days")), offerValidThrough, deliverBy,
              });
            })} label="Подтвердить доставку и выставить сумму">
            {order.type === "CATALOG" && <p className="muted">Перед выставлением суммы проверьте, что выбранный вариант можно отправить или изготовить.</p>}
            <div className="field-row">
              <label className="field">Код ПВЗ <input name="code" required maxLength={40} defaultValue={delivery.pickupPointCode ?? ""} /></label>
              <label className="field">Адрес ПВЗ <input name="address" required maxLength={300} defaultValue={delivery.pickupPointAddress ?? ""} /></label>
              <AmountField label="Стоимость доставки, ₽" /><DaysField label="Доставка после отправки, дни" />
              <label className="field">Подтвердить до <input name="offerValidThrough" type="date" max="9999-12-31" required /></label>
              <label className="field">Передать получателю не позднее <input name="deliverBy" type="date" max="9999-12-31" required /></label>
            </div>
            <p className="muted">Даты по Москве, включительно. При расчёте срока передачи учтите {order.type === "CUSTOM_PHOTO" ? "подготовку и согласование эскиза, вышивку и доставку" : "подготовку изделия и доставку"}.</p>
          </CommandForm> : <p className="muted">Покупатель ещё не указал получателя и пункт выдачи.</p>)}
          {canPublish && <CommandForm title="Опубликовать эскиз" busy={task.busy} onSubmit={(data) => task.run(async () => {
            const file = data.get("file");
            if (!(file instanceof File) || !file.size) throw new Error("Выберите файл эскиза.");
            if (sketchUpload.current?.file.name !== file.name || sketchUpload.current.file.lastModified !== file.lastModified || sketchUpload.current.file.size !== file.size) {
              const uploaded = await upload<{ id: string }>(publicPath + "/files?purpose=SKETCH", file);
              sketchUpload.current = { file, id: uploaded.id };
            }
            await command("/sketches", { fileId: sketchUpload.current.id });
            sketchUpload.current = null;
          })} label="Опубликовать эскиз">
            <label className="field">Файл эскиза <span className="optional">JPG или PNG, до 10 МиБ</span><input key={details.sketch?.id ?? "new"} type="file" name="file" accept={imageAccept} required /></label>
          </CommandForm>}
          {["IN_PRODUCTION", "PREPARING_ORDER"].includes(order.status) &&
            <button type="button" className="button solid" disabled={task.busy} onClick={() => void task.run(() => command("/production-complete"))}>Отметить готовность</button>}
          {order.status === "READY_TO_SHIP" && <CommandForm title="Отправка" busy={task.busy} onSubmit={(data) => task.run(() => command("/shipment", {
            trackingNumber: String(data.get("trackingNumber")).trim(),
          }))} label="Отметить отправку">
            <label className="field">Трек-номер СДЭК <input name="trackingNumber" required maxLength={120} /></label>
          </CommandForm>}
          {payment?.checkoutStatus && <button type="button" className="button outline" disabled={task.busy} onClick={() => void task.run(async () => {
            await write(publicPath + "/payment/sync", "POST");
            await refresh();
          })}>Сверить оплату и возврат</button>}
          {order.status === "IN_REVIEW" && <ReasonForm title="Отклонить заявку" label="Отклонить заявку" busy={task.busy}
            onSubmit={(reason) => task.run(() => command("/reject", { reason }))} />}
          {cancelStages.includes(order.status) && <ReasonForm title="Отменить заказ" label="Начать отмену" busy={task.busy}
            onSubmit={(reason) => task.run(() => command("/cancel", { reason }))} />}
          {canRefund && <button type="button" className="button outline" disabled={task.busy} onClick={() => setRefundPrompt(true)}>Оформить полный возврат</button>}
          {order.status === "CANCELLATION_REQUESTED" && !payment?.paidAt && <p className="muted">Заказ ждёт результата выданной ссылки на оплату. Сверьте её состояние.</p>}
          {["SHIPPED", "REJECTED", "CANCELLED"].includes(order.status) && !payment?.checkoutStatus && <p className="muted">По заказу нет ожидающих действий.</p>}
        </section>
        {details.sketch && <section className="panel stack"><SketchImage details={details} /></section>}
        <OrderChat id={order.id} role="STAFF" />
      </div>
      <aside className="stack">
        <OrderSummary details={details} />
        <DeliverySummary details={details} />
        {details.sourceFileIds.length > 0 && <section className="panel stack"><h2>Исходные фото</h2><OrderFiles orderId={order.id} ids={details.sourceFileIds} /></section>}
        <History key={version} path={path} />
      </aside>
    </div>
    {refundPrompt && payment && <Modal title="Подтвердите полный возврат" onClose={() => { if (!task.busy) setRefundPrompt(false); }}>
      <div className="stack">
        <h2>Полный возврат</h2>
        <p>Вернуть покупателю {money(payment.amountKopecks)}?</p>
        <Failure error={task.error} />
        <button type="button" className="button solid" disabled={task.busy} onClick={() => void task.run(async () => {
          await write(path + "/refund", "POST");
          setRefundPrompt(false);
          await refresh();
        })}>Подтвердить возврат</button>
      </div>
    </Modal>}
  </>;
}

function CommandForm({ title, busy, label, children, onSubmit }: {
  title: string; busy: boolean; label: string; children: ReactNode; onSubmit: (data: FormData) => void;
}) {
  return <details className="edit-block">
    <summary>{title}</summary>
    <form className="stack" onSubmit={(event) => { event.preventDefault(); onSubmit(new FormData(event.currentTarget)); }}>
      {children}<button className="button solid" disabled={busy}>{label}</button>
    </form>
  </details>;
}

function ReasonForm({ title, busy, label, onSubmit }: { title: string; busy: boolean; label: string; onSubmit: (reason: string) => void }) {
  return <CommandForm title={title} busy={busy} label={label} onSubmit={(data) => onSubmit(String(data.get("reason")).trim())}>
    <label className="field">Причина <textarea name="reason" required maxLength={400} rows={2} /></label>
  </CommandForm>;
}

function AmountField({ label }: { label: string }) {
  return <label className="field">{label} <input name="price" inputMode="decimal" pattern={amountPattern} required /></label>;
}
function DaysField({ label }: { label: string }) {
  return <label className="field">{label} <input name="days" type="number" min={1} max={2147483647} required /></label>;
}

function History({ path }: { path: string }) {
  const [page, setPage] = useState(0);
  const result = useResource<Page<OrderEvent>>(`${path}/history?page=${page}`);
  return <section className="panel stack">
    <h2>История</h2>
    {result.loading && <Pending />}
    <Failure error={result.error} retry={result.reload} />
    {result.value && <>
      {!result.value.items.length && <p className="muted">Действий пока нет.</p>}
      <ol className="history-list">{result.value.items.map((entry) => {
        const { itemKopecks, deliveryKopecks, totalKopecks, amountKopecks, productionDays, deliveryDays, reason, trackingNumber } = entry.details;
        const info = [
          typeof itemKopecks === "number" && `Изделие: ${money(itemKopecks)}`,
          typeof deliveryKopecks === "number" && `Доставка: ${money(deliveryKopecks)}`,
          typeof totalKopecks === "number" && `Итого: ${money(totalKopecks)}`,
          typeof amountKopecks === "number" && money(amountKopecks),
          typeof productionDays === "number" && days(productionDays),
          typeof deliveryDays === "number" && days(deliveryDays),
          reason, trackingNumber,
        ].filter(Boolean).join(" · ");
        return <li key={entry.id}><strong>{historyLabels[entry.action]}</strong>
          {info && <p>{info}</p>}
          <p className="muted"><time dateTime={entry.createdAt}>{dateLabel(entry.createdAt)}</time>{entry.actorEmail && ` · ${entry.actorEmail}`}</p></li>;
      })}</ol>
      <Pagination page={page} hasMore={result.value.hasMore} onPage={setPage} />
    </>}
  </section>;
}
