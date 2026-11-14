"use client";

import Link from "next/link";
import { useEffect, useId, useRef, useState, type ChangeEvent } from "react";
import { ApiError, filePath, imageAccept, read, upload, write } from "@/lib/api";
import type { OrderDetails, OrderStatus, Payment } from "@/lib/contracts";
import { calendarDateLabel, days, garmentLabel } from "@/lib/format";
import { useResource, useTask } from "@/lib/hooks";
import { AccessForm } from "./access";
import { DeliveryFields, deliveryFrom } from "./delivery-fields";
import { DeliveryDetails, DeliverySummary, OrderAcceptance, OrderAmounts, OrderFiles, OrderHead, OrderPhoto, OrderSummary, OrderTracking, SketchImage } from "./order-common";
import { OrderChat } from "./order-chat";
import { photoDataNotice, PrivacyLink } from "./privacy-link";
import { Failure, Icon, Modal, Notice, Pending } from "./ui";
import styles from "./customer-order.module.css";

const nextStepText: Partial<Record<OrderStatus, string>> = {
  IN_REVIEW: "Заявка отправлена. Менеджер проверит детали и напишет вам.",
  PREPARING_SKETCH: "Мы готовим эскиз и пришлём письмо, когда его можно будет согласовать.",
  CHANGES_REQUESTED: "Мы обновим эскиз и пришлём письмо, когда новая версия будет готова.",
  IN_PRODUCTION: "Мы вышиваем изделие и сообщим, когда заказ будет отправлен.",
  PREPARING_ORDER: "Мы готовим изделие и сообщим, когда заказ будет отправлен.",
  READY_TO_SHIP: "Заказ готов. Трек-номер появится после передачи посылки в СДЭК.",
  CANCELLATION_REQUESTED: "Мы оформляем отмену. Обновления появятся в карточке заказа.",
};
const sourcePhotoLimit = 5;

export function CustomerOrder({ id }: { id: string }) {
  const path = `/api/orders/${id}`;
  const resource = useResource<OrderDetails>(path);
  const needsAccess = resource.error instanceof ApiError && [401, 404].includes(resource.error.status);
  return <section className={`wrap page-block ${resource.value ? "" : "narrow"}`}>
    {resource.loading && <Pending>Загружаем заказ…</Pending>}
    {needsAccess && <>
      <Link href="/orders" className="text-link"><Icon name="back" /> Другой заказ</Link>
      <p className="eyebrow">Вход по коду</p><h1>Открыть заказ</h1>
      <p className="order-number">{id}</p>
      <p className="lede">Введите email, который указали при оформлении.</p>
      <div className="panel"><AccessForm path={path + "/access"} onVerified={resource.reload} /></div>
    </>}
    {!needsAccess && <Failure error={resource.error} retry={resource.reload} />}
    {resource.value && <OrderContent path={path} initial={resource.value} onLogout={resource.reload} />}
  </section>;
}

function OrderContent({ path, initial, onLogout }: { path: string; initial: OrderDetails; onLogout: () => void }) {
  const [details, setDetails] = useState(initial);
  const [editingDelivery, setEditingDelivery] = useState(false);
  const [statusMessage, setStatusMessage] = useState("");
  const task = useTask();
  const { order, delivery, sketch, acceptance } = details;
  const canSubmit = order.status === "DRAFT";
  const isPhoto = order.type === "CUSTOM_PHOTO";
  const showQuote = isPhoto && order.status === "AWAITING_DELIVERY_QUOTE"
    && order.itemKopecks !== null && order.productionDays !== null;
  const canEditDelivery = order.type === "CATALOG"
    ? ["DRAFT", "IN_REVIEW"].includes(order.status) : order.status === "AWAITING_DELIVERY_QUOTE";
  const paymentStage = order.status === "AWAITING_PAYMENT";
  const trackingNumber = order.status === "SHIPPED" ? order.trackingNumber : null;
  const nextStep = nextStepText[order.status];
  const initialSync = useRef(false);

  useEffect(() => {
    if (initialSync.current || initial.order.status !== "AWAITING_PAYMENT" || initial.payment?.checkoutStatus !== "PENDING") return;
    initialSync.current = true;
    void task.run(async () => {
      await write<Payment>(path + "/payment/sync", "POST");
      setDetails(await read<OrderDetails>(path));
    });
  }, [initial, path, task]);

  const update = (action: string) => task.run(async () => setDetails(await write<OrderDetails>(path + action, "POST")));
  const refresh = () => task.run(async () => {
    const latest = await read<OrderDetails>(path);
    if (latest.order.status !== order.status) setEditingDelivery(false);
    setDetails(latest);
  });
  const checkPayment = () => task.run(async () => {
    await write<Payment>(path + "/payment/sync", "POST");
    setDetails(await read<OrderDetails>(path));
  });
  const checkout = () => task.run(async () => {
    const offerVersion = acceptance?.offerVersion ?? details.offerVersion;
    let result: Payment;
    try {
      result = await write<Payment>(path + "/payment/checkout", "POST", { offerVersion });
    } catch (error) {
      try {
        setDetails(await read<OrderDetails>(path));
      } catch {
        throw new Error("Не удалось проверить состояние заказа. Обновите заказ перед повторной попыткой.", { cause: error });
      }
      throw error;
    }
    if (!result.checkoutUrl) {
      const latest = await read<OrderDetails>(path);
      setDetails(latest);
      if (latest.order.status !== "AWAITING_PAYMENT") return;
      throw new Error("Платёжная ссылка недоступна. Проверьте состояние оплаты в карточке.");
    }
    window.location.assign(result.checkoutUrl);
  });

  return <>
    <OrderHead details={details} onRefresh={refresh} busy={task.busy} />
    <Failure error={task.error} />
    {order.status === "EXPIRED" && <Notice error>Срок черновика истёк. Оформите новый заказ.</Notice>}
    {order.rejectionReason && <Notice error><strong>Причина отказа</strong> {order.rejectionReason}</Notice>}
    {order.cancellationReason && <Notice><strong>Причина отмены</strong> {order.cancellationReason}</Notice>}
    <div className="order-layout">
      <div className="stack">
        {nextStep && <Notice>{nextStep}</Notice>}
        <p className="sr-only" role="status">{statusMessage}</p>
        {canEditDelivery && <OrderDeliveryForm details={details} showQuote={showQuote} task={task}
          editing={editingDelivery} onEditingChange={setEditingDelivery} onSave={async (form) => {
            const updated = await write<OrderDetails>(path + "/delivery", "PUT", deliveryFrom(form, delivery));
            setDetails(updated);
            setStatusMessage("Доставка сохранена.");
          }} />}
        {canSubmit && <DraftSubmit details={details} path={path} task={task} onUpdate={setDetails} deliveryEditing={editingDelivery} />}
        {paymentStage && <OrderPaymentPanel details={details} busy={task.busy}
          onCheckout={() => void checkout()} onSync={() => void checkPayment()} />}
        {trackingNumber && <section className="panel stack">
          <h2>Отслеживание посылки</h2>
          <OrderTracking key={trackingNumber} trackingNumber={trackingNumber} allowCopy />
        </section>}
        {sketch && <section className="panel stack">
          <SketchImage details={details} />
          {order.status === "SKETCH_REVIEW" && <>
            <p>После согласования начнём вышивку. Если нужны изменения, запросите правки и напишите пожелания в переписке.</p>
            <div className="actions">
              <button className="button solid" type="button" disabled={task.busy} onClick={() => void update(`/sketches/${sketch.id}/approve`)}>Согласовать эскиз</button>
              <button className="button outline" type="button" disabled={task.busy} onClick={() => void update(`/sketches/${sketch.id}/request-changes`)}>Запросить правки</button>
            </div>
          </>}
        </section>}
        {order.status !== "DRAFT" && order.status !== "EXPIRED" && <OrderChat id={order.id} role="CUSTOMER" />}
      </div>
      <aside className="stack">
        <OrderSummary details={details} showAmounts={!paymentStage && !showQuote} showTracking={!trackingNumber} showDeliveryDays={!paymentStage}
          showProductionDays={!paymentStage && !showQuote} showAcceptance={!paymentStage} />
        {!canEditDelivery && !paymentStage && <DeliverySummary details={details} />}
        {details.sourceFileIds.length > 0 && order.status !== "DRAFT" && <section className="panel stack"><h2>Исходные фото</h2><OrderFiles orderId={order.id} ids={details.sourceFileIds} /></section>}
        <Link href="/offer#returns" className="text-link" target="_blank" rel="noopener noreferrer"
          title="Откроется в новой вкладке">Доставка и возврат</Link>
        <button type="button" className="text-link" disabled={task.busy} onClick={() => void task.run(async () => {
          await write("/api/session/logout", "POST");
          onLogout();
        })}>Выйти из заказа</button>
      </aside>
    </div>
  </>;
}

function OrderPaymentPanel({ details, busy, onCheckout, onSync }: {
  details: OrderDetails; busy: boolean; onCheckout: () => void; onSync: () => void;
}) {
  const { order, delivery, payment, acceptance, catalogItem, totalKopecks } = details;
  const pending = payment?.checkoutStatus === "PENDING";
  if (!delivery?.offerValidThrough || !delivery.deliverBy || !payment || totalKopecks === null) {
    return <section className="panel stack">
      <h2>Оплата заказа</h2>
      <Notice error>Условия заказа ещё не готовы. Напишите нам в переписке по заказу.</Notice>
      {pending && <button className="button outline" type="button" disabled={busy} onClick={onSync}>Проверить оплату</button>}
    </section>;
  }

  const offerHref = `/offer/${encodeURIComponent(details.offerVersion)}`;
  const canSync = pending || payment.checkoutStatus === "CREATING";
  const action = acceptance ? (pending ? "Продолжить оплату" : "Перейти к оплате") : "Подтвердить заказ и перейти к оплате";

  return <section className="panel stack">
    <h2>Оплата заказа</h2>
    <p>Вы оплатите полную стоимость изделия и доставки одним платежом через ЮKassa.</p>
    <dl className="order-facts" aria-label="Условия заказа">
      <div><dt>Изделие</dt><dd>{catalogItem ? catalogItem.productName : `${garmentLabel(order.garment)} с вышивкой по фото`}</dd></div>
      <div><dt>Количество</dt><dd>1 шт.</dd></div>
      <div><dt>Размер и цвет</dt><dd>{order.size} · {order.color}</dd></div>
      {catalogItem && <div><dt>Артикул</dt><dd>{catalogItem.sku}</dd></div>}
      {order.productionDays !== null && <div><dt>Подготовка изделия</dt><dd>{days(order.productionDays)}</dd></div>}
      {delivery.deliveryDays !== null && <div><dt>Перевозка после отправки</dt><dd>{days(delivery.deliveryDays)}</dd></div>}
      <div><dt>Передадим получателю не позднее</dt><dd><time dateTime={delivery.deliverBy}>{calendarDateLabel(delivery.deliverBy)}</time></dd></div>
      {!acceptance && <div><dt>Подтвердить до</dt><dd><time dateTime={delivery.offerValidThrough}>{calendarDateLabel(delivery.offerValidThrough)}</time> включительно, мск</dd></div>}
    </dl>
    {order.description && <p className="preline"><strong className="block">Пожелания к вышивке</strong>{order.description}</p>}
    <h3>Пункт выдачи СДЭК</h3>
    <DeliveryDetails delivery={delivery} />
    <OrderAmounts details={details} />
    {order.type === "CUSTOM_PHOTO" && <p className="muted">Эскиз появится после оплаты. Вышивку начнём после вашего согласования.</p>}
    {acceptance ? <OrderAcceptance acceptance={acceptance} /> : <p className="muted">
      Подтверждая заказ, вы принимаете указанные условия и <Link className="text-link" href={offerHref} target="_blank" rel="noopener noreferrer"
        title="Откроется в новой вкладке">публичную оферту</Link>.
    </p>}
    {pending && <p className="muted">Если вы уже оплатили заказ, проверьте статус платежа.</p>}
    <div className="actions">
      <button className="button solid" type="button" disabled={busy} onClick={onCheckout}>{action} <Icon name="external" /></button>
      {canSync && <button className="button outline" type="button" disabled={busy} onClick={onSync}>Проверить оплату</button>}
    </div>
  </section>;
}

function OrderDeliveryForm({ details, showQuote, task, editing, onEditingChange, onSave }: {
  details: OrderDetails; showQuote: boolean; task: ReturnType<typeof useTask>; editing: boolean;
  onEditingChange: (editing: boolean) => void; onSave: (form: FormData) => Promise<void>;
}) {
  const [error, setError] = useState<Error>();
  const heading = useRef<HTMLHeadingElement>(null);
  const editButton = useRef<HTMLButtonElement>(null);
  const shouldFocus = useRef(false);
  const { order, delivery } = details;
  const busy = task.busy;

  useEffect(() => {
    if (!shouldFocus.current) return;
    if (editing) heading.current?.focus();
    else editButton.current?.focus();
    shouldFocus.current = false;
  }, [editing]);

  const edit = () => {
    shouldFocus.current = true;
    setError(undefined);
    onEditingChange(true);
  };
  const close = () => {
    shouldFocus.current = true;
    setError(undefined);
    onEditingChange(false);
  };

  return <section className="panel stack">
    <h2 ref={heading} tabIndex={-1}>{showQuote ? "Оценка вышивки" : "Доставка"}</h2>
    {showQuote && <>
      <OrderAmounts details={details} showDelivery={false} />
      {order.productionDays !== null && <p>Подготовка изделия — {days(order.productionDays)}.</p>}
      <p className="muted">{delivery ? "Менеджер проверит выбранный пункт и рассчитает стоимость." : "Укажите пункт выдачи СДЭК, чтобы менеджер рассчитал доставку."}</p>
    </>}
    {editing ? <form key={delivery?.version ?? 0} className="stack" onSubmit={(event) => {
      event.preventDefault();
      const form = new FormData(event.currentTarget);
      void task.run(async () => {
        setError(undefined);
        try {
          await onSave(form);
          close();
        } catch (cause) {
          setError(cause instanceof Error ? cause : new Error("Не удалось сохранить доставку. Попробуйте ещё раз."));
        }
      });
    }}>
      <DeliveryFields initial={delivery} />
      <PrivacyLink />
      <Failure error={error} />
      <div className="actions">
        <button className="button solid" disabled={busy}>Сохранить доставку</button>
        <button className="button outline" type="button" disabled={busy} onClick={close}>Отмена</button>
      </div>
    </form> : <>
      {delivery && <DeliveryDetails delivery={delivery} />}
      <button ref={editButton} className={`button ${delivery ? "outline" : "solid"}`} type="button" disabled={busy} onClick={edit}>
        {delivery ? "Изменить" : "Указать пункт выдачи"}
      </button>
    </>}
  </section>;
}

function DraftSubmit({ details, path, onUpdate, task, deliveryEditing }: {
  details: OrderDetails; path: string; onUpdate: (value: OrderDetails) => void;
  task: ReturnType<typeof useTask>; deliveryEditing: boolean;
}) {
  const [uploading, setUploading] = useState(false);
  const [removing, setRemoving] = useState(false);
  const [deleteId, setDeleteId] = useState<string>();
  const [error, setError] = useState<Error>();
  const [photoStatus, setPhotoStatus] = useState("");
  const input = useRef<HTMLInputElement>(null);
  const section = useRef<HTMLElement>(null);
  const heading = useRef<HTMLHeadingElement>(null);
  const uploadButton = useRef<HTMLButtonElement>(null);
  const replacement = useRef<string | null>(null);
  const focusReplacement = useRef<string | null>(null);
  const hintId = useId();
  const isPhoto = details.order.type === "CUSTOM_PHOTO";
  const photos = details.sourceFileIds;
  const busy = task.busy;

  useEffect(() => {
    if (busy || focusReplacement.current === null) return;
    const button = section.current?.querySelector<HTMLButtonElement>(`button[data-photo-id="${focusReplacement.current}"]`);
    button?.focus({ preventScroll: true });
    focusReplacement.current = null;
  }, [busy, photos]);

  function focusUploadButton() {
    requestAnimationFrame(() => (uploadButton.current ?? heading.current)?.focus({ preventScroll: true }));
  }

  function choosePhoto(id: string | null) {
    replacement.current = id;
    input.current?.click();
  }

  function uploadPhoto(event: ChangeEvent<HTMLInputElement>) {
    const file = event.currentTarget.files?.[0];
    event.currentTarget.value = "";
    if (!file) return;
    const previous = replacement.current;
    const route = path + "/files?purpose=SOURCE" + (previous ? `&replace=${previous}` : "");
    void task.run(async () => {
      setError(undefined);
      setPhotoStatus("");
      setUploading(true);
      try {
        const result = await upload<{ id: string }>(route, file);
        const sourceFileIds = [...photos.filter((id) => id !== previous), result.id];
        if (previous) focusReplacement.current = result.id;
        else focusUploadButton();
        onUpdate({ ...details, sourceFileIds });
        setPhotoStatus(previous ? "Фото заменено." : "Фото загружено.");
      } catch (cause) {
        setError(cause instanceof Error ? cause : new Error("Не удалось загрузить фото. Попробуйте ещё раз."));
      } finally {
        setUploading(false);
      }
    });
  }

  function deletePhoto(id: string) {
    void task.run(async () => {
      setError(undefined);
      setPhotoStatus("");
      setRemoving(true);
      try {
        await write(filePath(details.order.id, id), "DELETE");
        onUpdate({ ...details, sourceFileIds: photos.filter((photo) => photo !== id) });
        setDeleteId(undefined);
        setPhotoStatus("Фото удалено.");
        focusUploadButton();
      } catch (cause) {
        setError(cause instanceof Error ? cause : new Error("Не удалось удалить фото. Попробуйте ещё раз."));
      } finally {
        setRemoving(false);
      }
    });
  }

  return <section ref={section} className="panel stack">
    <h2 ref={heading} tabIndex={-1}>{isPhoto ? "Фото и заявка" : "Отправка заявки"}</h2>
    {isPhoto && photos.length === 0 && <p>Добавьте хотя бы одно исходное фото. Выберите чёткий кадр с нужного ракурса.</p>}
    {!isPhoto && <p>Проверьте данные заказа. По заявке мы проверим наличие изделия и рассчитаем доставку.</p>}
    {isPhoto && <>
      {photos.length > 0 && <ul className={`order-files ${styles.photoGrid}`} role="list">
        {photos.map((id, index) => <li className={styles.photo} key={id}>
          <OrderPhoto orderId={details.order.id} id={id} number={index + 1} />
          <div className={styles.photoActions}>
            <button data-photo-id={id} className={`text-link ${styles.photoAction}`} type="button" aria-label={`Заменить фото ${index + 1}`}
              aria-describedby={hintId} disabled={busy} onClick={() => choosePhoto(id)}>Заменить</button>
            <button className={`text-link ${styles.photoAction}`} type="button" aria-label={`Удалить фото ${index + 1}`} disabled={busy} onClick={() => {
              setError(undefined);
              setDeleteId(id);
            }}>Удалить</button>
          </div>
        </li>)}
      </ul>}
      <p className="muted" id={hintId}>JPG или PNG, до 10 МиБ. {photoDataNotice} <PrivacyLink /></p>
      <input ref={input} type="file" accept={imageAccept} hidden disabled={busy} onChange={uploadPhoto} />
      {photos.length < sourcePhotoLimit ? <button ref={uploadButton} className="button outline" type="button" aria-describedby={hintId}
        disabled={busy} onClick={() => choosePhoto(null)}><Icon name="upload" /> {uploading ? "Загружаем фото…" : "Загрузить фото"}</button>
        : <p className="muted">Загружено 5 фото — это максимум для одного заказа.</p>}
      <p className="sr-only" role="status">{uploading ? "Загружаем фото…" : photoStatus}</p>
      {!deleteId && <Failure error={error} />}
      {deleteId && <Modal title="Удалить фото?" onClose={() => {
        if (!removing) {
          setDeleteId(undefined);
          setError(undefined);
        }
      }}>
        <div className="stack">
          <h2>Удалить фото?</h2>
          <p>Снимок будет удалён из заявки. Вы сможете загрузить другой.</p>
          <Failure error={error} />
          <div className="actions">
            <button className="button solid" type="button" disabled={removing} onClick={() => deletePhoto(deleteId)}>
              {removing ? "Удаляем…" : "Удалить"}
            </button>
            <button className="button outline" type="button" disabled={removing} onClick={() => {
              setDeleteId(undefined);
              setError(undefined);
            }}>Отмена</button>
          </div>
        </div>
      </Modal>}
    </>}
    <p className="muted">После расчёта вы сможете проверить условия и подтвердить заказ.</p>
    {deliveryEditing && <p className="muted">Сохраните данные доставки или отмените изменения, чтобы отправить заявку.</p>}
    <button className="button solid" type="button" disabled={busy || deliveryEditing || (isPhoto && photos.length === 0)}
      onClick={() => void task.run(async () => {
        setError(undefined);
        setPhotoStatus("");
        onUpdate(await write<OrderDetails>(path + "/submit", "POST"));
      })}>
      {isPhoto ? "Получить оценку" : "Отправить заявку"} <Icon name="arrow" />
    </button>
  </section>;
}
