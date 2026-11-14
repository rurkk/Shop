import { useId } from "react";
import type { DeliveryContact } from "@/lib/contracts";
import { Icon } from "./ui";

function normalizePhone(value: string) {
  let phone = value.replace(/[\s().\-\u2010-\u2015]/g, "");
  if (/^[78]\d{10}$/.test(phone)) phone = "+7" + phone.slice(1);
  if (/^9\d{9}$/.test(phone)) phone = "+7" + phone;
  if (!/^\+[1-9]\d{9,14}$/.test(phone)) {
    throw new Error("Проверьте номер телефона. Укажите код страны, например +7 (999) 123-45-67.");
  }
  return phone;
}

export function deliveryFrom(data: FormData, initial?: DeliveryContact | null): DeliveryContact {
  const value = (name: string) => String(data.get(name) ?? "").trim();
  const pickupPoint = value("pickupPoint");
  if (!pickupPoint) throw new Error("Укажите код или адрес пункта выдачи СДЭК.");
  const unchanged = initial && pickupPoint === (initial.pickupPointAddress ?? initial.pickupPointCode);
  const isCode = /^[\p{L}\p{N}][\p{L}\p{N}_-]{0,39}$/u.test(pickupPoint);
  return {
    recipientName: value("recipientName"), phone: normalizePhone(value("phone")), city: value("city"),
    pickupPointCode: unchanged ? initial.pickupPointCode : isCode ? pickupPoint : null,
    pickupPointAddress: unchanged ? initial.pickupPointAddress : isCode ? null : pickupPoint,
  };
}

export function DeliveryFields({ initial }: { initial?: DeliveryContact | null }) {
  const contactsId = useId();
  const hintId = useId();
  return <fieldset className="stack">
    <legend>Получатель и пункт выдачи</legend>
    <p className="muted" id={contactsId}>Оформляйте доставку на себя. Укажите своё имя, фамилию и телефон.</p>
    <div className="field-row">
      <label className="field">Имя и фамилия <input name="recipientName" autoComplete="name" defaultValue={initial?.recipientName} aria-describedby={contactsId} required maxLength={160} /></label>
      <label className="field">Телефон <input name="phone" type="tel" autoComplete="tel" defaultValue={initial?.phone} aria-describedby={contactsId} required maxLength={40} placeholder="+7 (999) 123-45-67" /></label>
    </div>
    <label className="field">Город <input name="city" autoComplete="address-level2" defaultValue={initial?.city} required maxLength={120} /></label>
    <label className="field">Код или адрес пункта выдачи СДЭК
      <input name="pickupPoint" defaultValue={initial?.pickupPointAddress ?? initial?.pickupPointCode ?? ""} aria-describedby={hintId} required maxLength={300} />
    </label>
    <p className="muted" id={hintId}>Например, MSK123 или ул. Лесная, 5. <a className="text-link" href="https://www.cdek.ru/ru/offices/" target="_blank" rel="noreferrer">Найти пункт выдачи <Icon name="external" /></a></p>
  </fieldset>;
}
