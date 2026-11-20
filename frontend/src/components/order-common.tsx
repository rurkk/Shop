"use client";

import Link from "next/link";
import { useState } from "react";
import { filePath } from "@/lib/api";
import type { OrderDetails } from "@/lib/contracts";
import { calendarDateLabel, dateLabel, days, garmentLabel, money, moscowDateTimeLabel, providerLabels, statusLabels, typeLabels } from "@/lib/format";
import { useTask } from "@/lib/hooks";
import { Failure, Icon, Picture } from "./ui";

export function OrderHead({ details, onRefresh, busy }: {
  details: OrderDetails; onRefresh: () => void; busy: boolean;
}) {
  const { order, catalogItem, acceptance, delivery } = details;
  const task = useTask();
  const [copied, setCopied] = useState(false);
  let status = statusLabels[order.status];
  if (order.status === "AWAITING_PAYMENT" && !acceptance) {
    status = delivery?.offerValidThrough && delivery.deliverBy ? "Условия готовы" : "Условия уточняются";
  }
  return <header className="order-head">
    <p className="eyebrow">Мой заказ / {typeLabels[order.type]}</p>
    <h1>{catalogItem ? catalogItem.productName : "Вышивка по фото"}</h1>
    <div className="actions"><span className="badge" role="status">{status}</span><time className="muted" dateTime={order.createdAt}>{dateLabel(order.createdAt)}</time></div>
    <div className="actions">
      <span className="order-number">{order.id}</span>
      <button type="button" className="text-link" disabled={task.busy} onClick={() => void task.run(async () => {
        await navigator.clipboard.writeText(window.location.href);
        setCopied(true);
      })}><Icon name="link" /> {copied ? "Ссылка скопирована" : "Скопировать ссылку"}</button>
    </div>
    <Failure error={task.error} />
    <button type="button" className="text-link" onClick={onRefresh} disabled={busy}>Обновить заказ</button>
  </header>;
}

export function OrderAmounts({ details, showDelivery = true }: { details: OrderDetails; showDelivery?: boolean }) {
  const { order, delivery, totalKopecks } = details;
  return <dl className="order-facts" aria-label="Стоимость заказа">
    <div><dt>Стоимость изделия</dt><dd>{order.itemKopecks !== null ? money(order.itemKopecks) : "На оценке"}</dd></div>
    {showDelivery && <div><dt>Доставка СДЭК</dt><dd>{delivery?.priceKopecks != null ? money(delivery.priceKopecks) : "Рассчитает менеджер"}</dd></div>}
    {totalKopecks !== null && <div className="total"><dt>Итого</dt><dd>{money(totalKopecks)}</dd></div>}
  </dl>;
}

export function OrderSummary({ details, sectionId, showAmounts = true, showTracking = true, showDeliveryDays = true, showProductionDays = true, showAcceptance = true }: {
  details: OrderDetails; showAmounts?: boolean; showTracking?: boolean; showDeliveryDays?: boolean; showProductionDays?: boolean;
  showAcceptance?: boolean; sectionId?: string;
}) {
  const { order, catalogItem, delivery, payment, acceptance } = details;
  return <section id={sectionId} className="panel stack" aria-label="Детали заказа">
    <h2>Детали заказа</h2>
    <dl className="order-facts">
      <div><dt>Изделие</dt><dd>{garmentLabel(order.garment)}</dd></div>
      <div><dt>Размер и цвет</dt><dd>{order.size} · {order.color}</dd></div>
      {catalogItem && <div><dt>Артикул</dt><dd>{catalogItem.sku}</dd></div>}
      <div><dt>Email</dt><dd>{order.email}</dd></div>
      {showProductionDays && order.productionDays !== null && <div><dt>Подготовка изделия</dt><dd>{days(order.productionDays)}</dd></div>}
      {showDeliveryDays && delivery?.deliveryDays != null && <div><dt>Доставка после отправки</dt><dd>{days(delivery.deliveryDays)}</dd></div>}
      {showAcceptance && delivery?.deliverBy && <div><dt>Передать получателю не позднее</dt><dd><time dateTime={delivery.deliverBy}>{calendarDateLabel(delivery.deliverBy)}</time></dd></div>}
      {showAcceptance && !acceptance && delivery?.offerValidThrough && <div><dt>Подтвердить до</dt><dd><time dateTime={delivery.offerValidThrough}>{calendarDateLabel(delivery.offerValidThrough)}</time> включительно, мск</dd></div>}
      {payment && (acceptance || payment.paidAt || payment.checkoutStatus) && <div><dt>Оплата</dt><dd>{payment.paidAt ? `Подтверждена · ${dateLabel(payment.paidAt)}` : payment.checkoutStatus ? providerLabels[payment.checkoutStatus] : "Ожидается"}</dd></div>}
      {payment?.refund && <div><dt>Возврат</dt><dd>{providerLabels[payment.refund.status]} · {money(payment.amountKopecks)}</dd></div>}
    </dl>
    {showAcceptance && acceptance && <OrderAcceptance acceptance={acceptance} />}
    {showAmounts && <OrderAmounts details={details} />}
    {order.description && <p className="preline"><strong>Пожелания:</strong> {order.description}</p>}
    {showTracking && order.trackingNumber && <OrderTracking trackingNumber={order.trackingNumber} />}
  </section>;
}

export function OrderAcceptance({ acceptance }: { acceptance: NonNullable<OrderDetails["acceptance"]> }) {
  return <p className="muted">Заказ подтверждён <time dateTime={acceptance.acceptedAt}>{moscowDateTimeLabel(acceptance.acceptedAt)}</time>.{" "}
    <Link className="text-link" href={`/offer/${encodeURIComponent(acceptance.offerVersion)}`} target="_blank" rel="noopener noreferrer"
      title="Откроется в новой вкладке">Принятая редакция оферты</Link>
  </p>;
}

export function OrderTracking({ trackingNumber, allowCopy = false }: { trackingNumber: string; allowCopy?: boolean }) {
  const [copied, setCopied] = useState(false);
  const task = useTask();
  return <div className="stack">
    <h3>Трек-номер СДЭК</h3>
    <p className="order-number">{trackingNumber}</p>
    <div className="actions">
      <a className="text-link" href="https://www.cdek.ru/ru/tracking/" target="_blank" rel="noreferrer">Отследить на сайте СДЭК <Icon name="external" /></a>
      {allowCopy && <button type="button" className="text-link" disabled={task.busy} onClick={() => void task.run(async () => {
        await navigator.clipboard.writeText(trackingNumber);
        setCopied(true);
      })}>{copied ? "Трек-номер скопирован" : "Скопировать трек-номер"}</button>}
    </div>
    <p className="sr-only" role="status">{copied ? "Трек-номер скопирован." : ""}</p>
    <Failure error={task.error} />
  </div>;
}

export function DeliverySummary({ details }: { details: OrderDetails }) {
  const { delivery } = details;
  if (!delivery) return null;
  return <section className="panel stack">
    <h2>Пункт выдачи СДЭК</h2>
    <DeliveryDetails delivery={delivery} />
  </section>;
}

export function DeliveryDetails({ delivery }: { delivery: NonNullable<OrderDetails["delivery"]> }) {
  return <dl className="order-facts">
    <div><dt>Получатель</dt><dd>{delivery.recipientName}</dd></div>
    <div><dt>Телефон</dt><dd>{delivery.phone}</dd></div>
    <div><dt>Город</dt><dd>{delivery.city}</dd></div>
    {delivery.pickupPointCode && <div><dt>Код ПВЗ</dt><dd>{delivery.pickupPointCode}</dd></div>}
    {delivery.pickupPointAddress && <div><dt>Адрес ПВЗ</dt><dd>{delivery.pickupPointAddress}</dd></div>}
  </dl>;
}

export function OrderPhoto({ orderId, id, number }: { orderId: string; id: string; number: number }) {
  const url = filePath(orderId, id);
  return <a href={url} target="_blank" rel="noreferrer"><Picture src={url} alt={`Исходное фото ${number}`} /></a>;
}

export function OrderFiles({ orderId, ids }: { orderId: string; ids: string[] }) {
  if (ids.length === 0) return null;
  return <div className="order-files">{ids.map((id, index) =>
    <OrderPhoto key={id} orderId={orderId} id={id} number={index + 1} />
  )}</div>;
}

export function SketchImage({ details }: { details: OrderDetails }) {
  if (!details.sketch) return null;
  const { version, fileId, approvedAt } = details.sketch;
  return <>
    <h2>Эскиз · версия {version}</h2>
    <a href={filePath(details.order.id, fileId)} target="_blank" rel="noreferrer">
      <Picture key={fileId} src={filePath(details.order.id, fileId)} alt={`Эскиз, версия ${version}`} className="sketch-image" />
    </a>
    {approvedAt && <p className="muted">Согласован {dateLabel(approvedAt)}</p>}
  </>;
}
