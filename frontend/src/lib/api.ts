type Csrf = { headerName: string; token: string };

const details: Record<string, string> = {
  "Invalid request": "Проверьте заполненные поля.",
  "Invalid or expired code": "Неверный или просроченный код.",
  "Invalid or expired access code": "Неверный или просроченный код.",
  "The sketch version has changed": "Команда обновила эскиз. Обновите заказ, чтобы увидеть новую версию.",
  "The storefront has changed": "Витрину уже изменили. Загрузите актуальную версию и повторите правки.",
  "Delivery details are missing or have changed": "Данные доставки изменились. Обновите заказ и проверьте пункт выдачи.",
  "Order not found": "Заказ не найден.",
  "Catalog not found": "Коллекция не найдена или не опубликована.",
  "Product not found": "Товар больше не доступен в этой коллекции.",
  "Product variant is out of stock": "Этого варианта больше нет в наличии.",
  "Value already exists": "Такое значение уже используется.",
  "Staff email already exists": "Сотрудник с таким email уже есть.",
  "Use a working staff email address": "Укажите рабочий email.",
  "Staff management access is required": "Управление доступом доступно администраторам.",
  "Staff account not found": "Учётная запись сотрудника не найдена. Обновите список.",
  "You cannot change your own staff access": "Изменить ваш доступ может другой администратор.",
  "Upload a source photo before submitting": "Загрузите хотя бы одно фото.",
  "Upload a valid JPEG or PNG image": "Файл должен быть изображением JPG или PNG.",
  "Image dimensions exceed the upload limit": "Изображение должно быть не больше 6000 × 6000 и 20 мегапикселей.",
  "Attachment limit reached": "Достигнут лимит вложений для этого заказа.",
  "Source photo not found": "Исходное фото больше недоступно. Обновите заказ.",
  "The source photo is already being replaced": "Фото уже заменяется. Дождитесь загрузки и обновите заказ.",
  "The source photo changed before replacement": "Исходное фото изменилось. Обновите заказ и выберите файл ещё раз.",
  "Upload expired": "Загрузка прервалась. Выберите файл ещё раз.",
  "Add an image and a variant before publishing the product": "Для публикации добавьте фотографию и вариант товара.",
  "Product images must be distinct": "Каждое фото можно добавить в карточку товара один раз.",
  "Product measurements must be distinct by size": "Укажите одну строку замеров для каждого размера.",
  "Unpublish the product before removing its last variant": "Снимите товар с публикации перед удалением последнего варианта.",
  "Catalog image not found": "Изображение не найдено в медиатеке.",
  "Offer validity date is in the past": "Дата окончания предложения уже прошла. Укажите новую дату.",
  "Delivery deadline must be after the offer validity date": "Срок передачи заказа должен быть позже даты окончания предложения.",
  "Offer version has changed": "Редакция оферты изменилась. Проверьте условия перед подтверждением заказа.",
  "Offer has expired": "Срок действия предложения истёк. Напишите нам в переписке по заказу.",
  "Offer terms are incomplete": "Условия заказа ещё не готовы. Напишите нам в переписке по заказу.",
  "YooKassa test checkout is not enabled": "Оплата сейчас недоступна. Обратитесь к команде в переписке.",
  "Add a title and banner image before publishing the storefront": "Перед публикацией добавьте заголовок и фото баннера.",
  "Action is unavailable at the current order stage": "Действие недоступно на текущем этапе. Обновите заказ.",
};

export class ApiError extends Error {
  constructor(public status: number, public detail?: string) {
    const byStatus: Record<number, string> = {
      401: "Войдите по коду из письма.",
      403: "Нет доступа к действию. Проверьте, под какой учётной записью вы вошли.",
      404: "Данные не найдены.",
      409: "Состояние изменилось. Обновите данные перед повторной попыткой.",
      413: "Файл превышает 10 МиБ.",
      429: "Слишком много запросов. Подождите минуту и попробуйте снова.",
    };
    super((detail && details[detail]) || (status >= 500 ? "Сервис временно недоступен. Попробуйте позже." : detail || byStatus[status]) || "Не удалось выполнить запрос.");
    this.name = "ApiError";
  }
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers);
  headers.set("Accept", "application/json");
  let response: Response;
  try {
    response = await fetch(path, { ...init, headers, cache: "no-store", credentials: "same-origin" });
  } catch (error) {
    if (error instanceof DOMException && error.name === "AbortError") throw error;
    throw new Error("Нет связи с сервером. Проверьте подключение и попробуйте снова.");
  }
  if (!response.ok) {
    const detail = response.headers.get("content-type")?.includes("json")
      ? (await response.json() as { detail?: string }).detail : undefined;
    throw new ApiError(response.status, detail);
  }
  if (response.status === 202 || response.status === 204) return undefined as T;
  if (!response.headers.get("content-type")?.includes("json")) throw new Error("Сервер вернул неожиданный ответ.");
  return response.json() as Promise<T>;
}

export function read<T>(path: string, signal?: AbortSignal) {
  return request<T>(path, { signal });
}

export async function write<T = void>(path: string, method: "POST" | "PUT" | "DELETE", body?: object) {
  const { headerName, token } = await read<Csrf>("/api/csrf");
  const headers = new Headers({ [headerName]: token });
  if (body && !(body instanceof FormData)) headers.set("Content-Type", "application/json");
  return request<T>(path, {
    method, headers, body: body instanceof FormData ? body : body && JSON.stringify(body),
  });
}

export function validateImage(file: File) {
  if (!["image/jpeg", "image/png"].includes(file.type)) throw new Error("Выберите файл JPG или PNG.");
  if (file.size > 10 * 1024 * 1024) throw new Error("Размер файла не должен превышать 10 МиБ.");
}

export function upload<T>(path: string, file: File) {
  validateImage(file);
  const body = new FormData();
  body.append("file", file);
  return write<T>(path, "POST", body);
}

export const imageAccept = "image/jpeg,image/png";
export const filePath = (id: string, fileId: string) => `/api/orders/${id}/files/${fileId}`;
