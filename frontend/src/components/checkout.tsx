"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useLayoutEffect, useRef, useState } from "react";
import { read, upload, write } from "@/lib/api";
import type { OrderDetails, PhotoOrderOption, Variant } from "@/lib/contracts";
import { money } from "@/lib/format";
import { useTask } from "@/lib/hooks";
import { AccessCodeForm, ResendWait, useAccessCode } from "./access";
import { CheckoutPhoto } from "./checkout-photo";
import { DeliveryFields, deliveryFrom } from "./delivery-fields";
import { PhotoOrderConfiguration } from "./photo-order-configuration";
import { PrivacyLink } from "./privacy-link";
import { Failure, Icon, Pending } from "./ui";

type Selection = { catalogSlug: string; productName: string; variant: Variant };
type Draft = { id: string; requestKey: string; selectionId?: string; email: string; requestedAt?: number };

export function Checkout({ selection, photoOptions, active = true, onBusyChange }: (
  { selection: Selection; photoOptions?: never } | { selection?: never; photoOptions: PhotoOrderOption[] }
) & { active?: boolean; onBusyChange?: (busy: boolean) => void }) {
  const [email, setEmail] = useState("");
  const [draft, setDraft] = useState<Draft>();
  const [verifiedDraft, setVerifiedDraft] = useState<string>();
  const [file, setFile] = useState<File>();
  const lastUpload = useRef<File>(null);
  const [verifying, setVerifying] = useState(false);
  const confirmTitle = useRef<HTMLHeadingElement>(null);
  const emailInput = useRef<HTMLInputElement>(null);
  const router = useRouter();
  const request = useTask();
  const photo = useTask();
  const access = useAccessCode(email);
  const currentDraft = draft?.selectionId === selection?.variant.id ? draft : undefined;
  const orderPath = currentDraft ? `/orders/${currentDraft.id}` : undefined;
  const requestedAt = currentDraft?.requestedAt;
  const verify = requestedAt !== undefined && currentDraft?.email === access.address && verifiedDraft !== currentDraft.id;
  const verified = currentDraft && verifiedDraft === currentDraft.id;
  const busy = request.busy || verifying || photo.busy || Boolean(verified && !photo.error);

  useEffect(() => { if (active && verified) confirmTitle.current?.focus(); }, [active, verified]);
  useLayoutEffect(() => { onBusyChange?.(busy); }, [onBusyChange, busy]);

  function submit(data: FormData) {
    if (access.resendSeconds) return;
    void request.run(async () => {
      const payload = selection
        ? { email: access.address, catalogSlug: selection.catalogSlug, variantId: selection.variant.id, delivery: deliveryFrom(data) }
        : {
          email: access.address, garment: data.get("garment"), size: data.get("size"), color: data.get("color"),
          description: String(data.get("description")).trim(),
          embroidery: {
            frame: data.get("embroideryFrame"),
            threePhotoBackground: data.has("threePhotoBackground"),
            licensePlate: data.has("licensePlate"),
          },
        };
      const requestKey = JSON.stringify(payload);
      let nextDraft: Draft;
      if (currentDraft?.requestKey === requestKey) {
        nextDraft = currentDraft;
      } else {
        const result = await write<{ id: string }>(selection ? "/api/orders/catalog-drafts" : "/api/orders/drafts", "POST", payload);
        nextDraft = { id: result.id, requestKey, selectionId: selection?.variant.id, email: access.address };
        setDraft(nextDraft);
      }
      const requestedAt = await access.requestCode(`/api/orders/${nextDraft.id}/access`);
      setDraft({ ...nextDraft, requestedAt });
    });
  }

  function openWithPhoto(path: string, selected: File, retry: boolean) {
    void photo.run(async () => {
      const apiPath = "/api" + path;
      if (retry && (await read<OrderDetails>(apiPath)).sourceFileIds.length) {
        if (selected !== lastUpload.current) {
          throw new Error("В черновике уже есть фото. Откройте его, чтобы проверить или заменить снимок.");
        }
      } else {
        lastUpload.current = selected;
        await upload(apiPath + "/files?purpose=SOURCE", selected);
      }
      router.push(path);
    });
  }

  function editEmail() {
    if (currentDraft) setDraft({ ...currentDraft, requestedAt: undefined });
    requestAnimationFrame(() => emailInput.current?.focus());
  }

  return <div className="stack">
    <ol className="steps" aria-label="Шаги оформления">
      <li aria-current={!verify && !verified ? "step" : undefined}>{selection ? "Доставка" : "Фото и детали"}</li>
      <li aria-current={verify ? "step" : undefined}>Код из письма</li>
      <li aria-current={verified ? "step" : undefined}>{selection ? "Заявка" : "Оценка вышивки"}</li>
    </ol>
    {selection && <div className="checkout-selection"><strong>{selection.productName}</strong><span>{selection.variant.size} · {selection.variant.color}</span><strong>{money(selection.variant.priceKopecks)}</strong></div>}
    <div hidden={Boolean(verify || verified)}>
      <form className="stack" onSubmit={(event) => {
        event.preventDefault();
        submit(new FormData(event.currentTarget));
      }}>
        <fieldset className="stack" disabled={request.busy}>
          {photoOptions && <>
            <CheckoutPhoto file={file} onChange={setFile} />
            <PhotoOrderConfiguration options={photoOptions} />
            <label className="field">Пожелания к вышивке <span className="optional">необязательно</span>
              <textarea name="description" maxLength={2000} rows={3} placeholder="Что важно сохранить на фото? Где разместить вышивку?" />
            </label>
          </>}
          {selection && <DeliveryFields />}
          <label className="field">Email
            <input ref={emailInput} name="email" type="email" autoComplete="email" maxLength={254} required placeholder="name@example.com" value={email} onChange={(event) => setEmail(event.target.value)} />
          </label>
        </fieldset>
        <p className="muted">{selection ? "Менеджер подтвердит наличие и рассчитает доставку. Итог появится в карточке до оплаты." : "На этот адрес придут код доступа и обновления по заказу."} <PrivacyLink /></p>
        <Failure error={request.error} />
        <button className="button solid" disabled={request.busy || access.resendSeconds > 0}>{request.busy ? "Запрашиваем код…" : "Получить код на email"} <Icon name="arrow" /></button>
        {draft && !currentDraft && access.resendSeconds > 0 && <p className="muted">Для другого варианта нужен новый код.</p>}
        <ResendWait seconds={access.resendSeconds} />
      </form>
    </div>
    {verify && currentDraft && orderPath && <>
      <h3>Подтвердите email</h3>
      <AccessCodeForm key={currentDraft.id} path={`/api${orderPath}/access`} email={currentDraft.email} requestedAt={requestedAt}
        resendSeconds={access.resendSeconds} onResend={async () => {
          const requestedAt = await access.requestCode(`/api${orderPath}/access`);
          setDraft({ ...currentDraft, requestedAt });
        }}
        onEditEmail={editEmail} onBusyChange={setVerifying} active={active} onVerified={() => {
          setVerifiedDraft(currentDraft.id);
          if (file && !selection) openWithPhoto(orderPath, file, false);
          else router.push(orderPath);
        }} />
      <Link className="text-link" href={orderPath}>Ссылка на черновик <Icon name="link" /></Link>
      <p className="muted">Черновик хранится сутки.{file && " Выбранное фото загрузится после подтверждения email в этой вкладке."}</p>
    </>}
    {verified && orderPath && <>
      <h3 ref={confirmTitle} tabIndex={-1}>Email подтверждён</h3>
      {photo.busy && <Pending>Загружаем фото…</Pending>}
      {!photo.busy && !photo.error && <Pending>Открываем заказ…</Pending>}
      {photo.error && <>
        <Failure error={photo.error} />
        <CheckoutPhoto file={file} onChange={setFile} />
        <PrivacyLink />
        <button className="button solid" type="button" disabled={!file} onClick={() => {
          if (file) openWithPhoto(orderPath, file, true);
        }}>Повторить загрузку <Icon name="upload" /></button>
      </>}
      <Link className="text-link" href={orderPath}>Открыть черновик <Icon name="arrow" /></Link>
    </>}
  </div>;
}
