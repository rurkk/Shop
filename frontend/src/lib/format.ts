import type { Availability, EmbroideryFrame, OrderEvent, OrderStatus, OrderType, ProviderStatus } from "./contracts";

const integer = new Intl.NumberFormat("ru-RU", { maximumFractionDigits: 0 });
const dateTime = new Intl.DateTimeFormat("ru-RU", { dateStyle: "medium", timeStyle: "short" });
const moscowDateTime = new Intl.DateTimeFormat("ru-RU", { dateStyle: "medium", timeStyle: "short", timeZone: "Europe/Moscow" });
const calendarDate = new Intl.DateTimeFormat("ru-RU", { dateStyle: "long", timeZone: "UTC" });
const daysRule = new Intl.PluralRules("ru-RU");
export function money(kopecks: number) {
  const cents = kopecks % 100;
  const rubles = integer.format((kopecks - cents) / 100);
  return `${rubles}${cents ? `,${String(cents).padStart(2, "0")}` : ""}\u00a0₽`;
}
export const dateLabel = (iso: string) => dateTime.format(new Date(iso));
export const moscowDateTimeLabel = (iso: string) => `${moscowDateTime.format(new Date(iso))} мск`;
export const calendarDateLabel = (iso: string) => calendarDate.format(new Date(`${iso}T00:00:00Z`));
export function days(count: number) {
  const rule = daysRule.select(count);
  return `${count} ${rule === "one" ? "день" : rule === "few" ? "дня" : "дней"}`;
}
export const garmentLabels: Record<string, string> = { HOODIE: "Худи", T_SHIRT: "Футболка", SWEATSHIRT: "Свитшот" };
export const garmentLabel = (garment: string) => garmentLabels[garment] ?? garment;
export const embroideryFrameSizeMm: Record<EmbroideryFrame, number> = { MINI: 80, SMALL: 120, STANDARD: 160, LARGE: 220 };
export function embroideryFrameLabel(frame: EmbroideryFrame, sizeMm = embroideryFrameSizeMm[frame]) {
  const size = `${sizeMm / 10} × ${sizeMm / 10} см`;
  return frame === "MINI" ? `Мини-вышивка · до ${size}` : size;
}
export const typeLabels: Record<OrderType, string> = { CUSTOM_PHOTO: "По фото", CATALOG: "Из коллекции" };
export const statusLabels: Record<OrderStatus, string> = {
  DRAFT: "Черновик", EXPIRED: "Срок черновика истёк", IN_REVIEW: "На рассмотрении",
  AWAITING_DELIVERY_QUOTE: "Расчёт доставки", AWAITING_PAYMENT: "Ожидает оплаты", PREPARING_SKETCH: "Готовим эскиз",
  SKETCH_REVIEW: "Эскиз на согласовании", CHANGES_REQUESTED: "Правки к эскизу", IN_PRODUCTION: "Вышиваем",
  PREPARING_ORDER: "Готовим изделие", READY_TO_SHIP: "Готов к отправке", SHIPPED: "Отправлен",
  REJECTED: "Заявка отклонена", CANCELLATION_REQUESTED: "Оформляем отмену", CANCELLED: "Отменён",
};
export const availabilityLabels: Record<Availability, string> = {
  IN_STOCK: "В наличии", MADE_TO_ORDER: "Под заказ", OUT_OF_STOCK: "Нет в наличии",
};
export const providerLabels: Record<ProviderStatus, string> = {
  CREATING: "Создаётся", PENDING: "В обработке", SUCCEEDED: "Подтверждён", CANCELED: "Отменён",
};
export const historyLabels: Record<OrderEvent["action"], string> = {
  QUOTED: "Оценили изделие", DELIVERY_QUOTED: "Рассчитали доставку", REJECTED: "Отклонили заявку",
  SHIPPED: "Отправили заказ", CANCELLATION_REQUESTED: "Запросили отмену", CANCELLED: "Отменили заказ",
  PAYMENT_CONFIRMED: "Подтвердили оплату", REFUND_REQUESTED: "Запросили возврат",
  REFUND_SUCCEEDED: "Подтвердили возврат", REFUND_CANCELED: "Возврат отменён",
};
export const uuidPattern = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
export const isUuid = (value: string) => new RegExp(`^${uuidPattern}$`).test(value);
export const slugPattern = "[a-z0-9]+(-[a-z0-9]+)*";
export const kopecksInput = (value: number) => `${Math.floor(value / 100)}.${String(value % 100).padStart(2, "0")}`;
export const rublesToKopecks = (rubles: string) => {
  const [whole, fraction = ""] = rubles.split(/[.,]/);
  const value = Number(whole) * 100 + Number(fraction.padEnd(2, "0"));
  if (!/^\d+(?:[.,]\d{1,2})?$/.test(rubles) || !Number.isSafeInteger(value)) {
    throw new Error("Укажите сумму в рублях с точностью до двух знаков после запятой.");
  }
  return value;
};
