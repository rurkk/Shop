import { createHmac, randomUUID } from "node:crypto";
import { readFileSync } from "node:fs";
import path from "node:path";
import { parseEnv } from "node:util";
import { expect, test, type APIRequestContext, type Browser, type Locator, type Page } from "@playwright/test";

const mail = process.env.SHOP_TEST_MAIL_URL ?? "http://127.0.0.1:18025";
const gateway = process.env.SHOP_TEST_GATEWAY_URL ?? "http://127.0.0.1:18081";
const fixtures = path.resolve(process.cwd(), "../infra/tests/fixtures");
const image = (name: string) => path.join(fixtures, name + ".jpg");
const testRun = randomUUID().slice(0, 8);
const offerPath = "/offer/2026-11-14-draft";

function calendarDate(daysFromToday: number) {
  const today = new Intl.DateTimeFormat("sv-SE", { timeZone: "Europe/Moscow", dateStyle: "short" }).format(new Date());
  const date = new Date(`${today}T00:00:00Z`);
  date.setUTCDate(date.getUTCDate() + daysFromToday);
  return date.toISOString().slice(0, 10);
}

type MailList = { messages: { ID: string; Subject: string; To: { Address: string }[] }[] | null };
async function mailIds(request: APIRequestContext, email: string, subject = "Код доступа") {
  const response = await request.get(mail + "/api/v1/messages?limit=100");
  expect(response.ok()).toBeTruthy();
  const data: MailList = await response.json();
  return (data.messages ?? []).filter((item) => item.Subject === subject && item.To.some((person) => person.Address === email)).map(({ ID }) => ID);
}

function staffTotp(email: string) {
  const accounts = process.env.SHOP_TEST_STAFF_TOTP_ACCOUNTS ?? parseEnv(
    readFileSync(path.resolve(process.cwd(), "../.env.example"), "utf8"),
  ).SHOP_BOOTSTRAP_STAFF_TOTP_ACCOUNTS;
  const secret = accounts?.split(",").find((entry) => entry.startsWith(`${email}=`))?.slice(email.length + 1);
  if (!secret || !/^[A-Z2-7]{32}$/.test(secret)) throw new Error(`Missing test TOTP key for ${email}`);
  const alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
  const key = Buffer.alloc(20);
  let bits = 0;
  let pending = 0;
  let offset = 0;
  for (const symbol of secret) {
    pending = (pending << 5) | alphabet.indexOf(symbol);
    bits += 5;
    if (bits >= 8) {
      bits -= 8;
      key[offset++] = (pending >>> bits) & 0xff;
    }
  }
  const counter = Buffer.alloc(8);
  counter.writeBigUInt64BE(BigInt(Math.floor(Date.now() / 30_000)));
  const digest = createHmac("sha1", key).update(counter).digest();
  const truncated = digest.readUInt32BE(digest[digest.length - 1] & 15) & 0x7fffffff;
  return String(truncated % 1_000_000).padStart(6, "0");
}

async function verifyStaffCodes(page: Page, email: string) {
  const seen = await mailIds(page.request, email);
  await page.getByRole("button", { name: "Получить код" }).click();
  await confirmCode(page, email, seen, undefined, true);
}

async function confirmCode(page: Page, email: string, seen: string[], scenario?: "expired-session" | "wrong-code", staff = false) {
  let id: string | undefined;
  await expect.poll(async () => {
    id = (await mailIds(page.request, email)).find((value) => !seen.includes(value));
    return id;
  }, { timeout: 18_000 }).toBeTruthy();
  const response = await page.request.get(mail + "/api/v1/message/" + id);
  const message: { Text: string } = await response.json();
  const code = message.Text.match(/\b\d{6}\b/)?.[0];
  expect(code).toBeTruthy();
  const field = page.getByRole("textbox", { name: "Код из письма" });
  const confirm = page.getByRole("button", { name: staff ? "Подтвердить коды" : "Подтвердить код", exact: true });
  if (scenario === "expired-session") {
    await page.context().clearCookies({ name: "JSESSIONID" });
  }
  if (scenario === "wrong-code") {
    await field.fill(code === "000000" ? "111111" : "000000");
    await confirm.click();
    await expect(page.getByRole("alert").filter({ hasText: "Неверный или просроченный код." })).toBeVisible();
  }
  await field.fill(code!);
  if (staff) {
    await page.getByRole("textbox", { name: "Код из приложения-аутентификатора" }).fill(staffTotp(email));
  }
  await confirm.click();
}

async function staffPage(browser: Browser, baseURL: string, project: string) {
  const context = await browser.newContext({
    baseURL,
    viewport: project === "mobile" ? { width: 390, height: 844 } : { width: 1440, height: 1000 },
  });
  const page = await context.newPage();
  const email = project === "mobile" ? "ui-mobile-staff@example.test" : "ui-staff@example.test";
  const response = await page.goto("/admin");
  expect(response?.headers()["content-security-policy"]).toContain("frame-ancestors 'none'");
  expect(response?.headers()["x-frame-options"]).toBe("DENY");
  await page.getByRole("textbox", { name: "Email" }).fill(email);
  await verifyStaffCodes(page, email);
  await expect(page.getByRole("heading", { name: "Заказы", exact: true })).toBeVisible();
  return page;
}

async function fillDelivery(root: Locator | Page) {
  await root.getByRole("textbox", { name: "Имя и фамилия" }).fill("Тестовый Покупатель");
  await root.getByRole("textbox", { name: "Телефон" }).fill("+7 (999) 123-45-67");
  await root.getByRole("textbox", { name: "Город" }).fill("Москва");
  await root.getByRole("textbox", { name: /Код или адрес пункта выдачи/ }).fill("TEST-MSK");
}

async function openStaffOrder(staff: Page, email: string, type: "CATALOG" | "CUSTOM_PHOTO") {
  await staff.goto("/admin");
  await staff.getByRole("searchbox", { name: "Номер заказа или email" }).fill(email);
  await staff.getByRole("combobox", { name: "Тип", exact: true }).selectOption(type);
  await staff.getByRole("combobox", { name: "Статус", exact: true }).selectOption("IN_REVIEW");
  await staff.getByRole("button", { name: "Найти" }).click();
  await staff.getByRole("link", { name: email, exact: true }).click();
  await expect(staff.getByRole("heading", { name: "Действия по заказу" })).toBeVisible();
}

async function quoteDelivery(staff: Page) {
  const form = staff.locator("details").filter({ has: staff.getByText("Рассчитать доставку СДЭК", { exact: true }) });
  await form.locator("summary").click();
  await expect(form.getByRole("textbox", { name: "Код ПВЗ" })).toHaveValue("TEST-MSK");
  await form.getByRole("textbox", { name: "Адрес ПВЗ" }).fill("Тестовая улица, 1");
  await form.getByRole("textbox", { name: "Стоимость доставки, ₽" }).fill("350.50");
  await form.getByRole("spinbutton", { name: "Доставка после отправки, дни" }).fill("4");
  await form.getByLabel("Подтвердить до").fill(calendarDate(3));
  await form.getByLabel("Передать получателю не позднее").fill(calendarDate(21));
  await form.getByRole("button", { name: "Подтвердить доставку и выставить сумму" }).click();
  await expect(staff.getByText("Ожидает оплаты", { exact: true })).toBeVisible();
}

async function pay(page: Page, total: RegExp, next: string) {
  const orderURL = page.url();
  const confirm = page.getByRole("button", { name: "Подтвердить заказ и перейти к оплате", exact: true });
  const panel = page.locator("section.panel").filter({ has: confirm });
  await expect(page.getByText("Условия готовы", { exact: true })).toBeVisible();
  await expect(panel.getByText("Итого", { exact: true })).toBeVisible();
  await expect(panel.getByRole("definition").filter({ hasText: total })).toBeVisible();
  await expect(panel.getByText(/350,50\s*₽/)).toBeVisible();
  await expect(panel.locator(`a[href="${offerPath}"]`)).toBeVisible();
  const provider = "https://yoomoney.ru/test-payment/";
  await page.route(provider + "*", (route) => route.fulfill({ contentType: "text/html; charset=utf-8", body: "<h1>Тестовый платёж</h1>" }));
  const navigation = page.waitForURL((url) => url.href.startsWith(provider));
  await confirm.click();
  await navigation;
  const paymentId = page.url().slice(provider.length);
  const result = await page.request.post(gateway + "/test/payment", { data: { paymentId, status: "succeeded" } });
  expect(result.ok()).toBeTruthy();
  await page.goto(orderURL);
  await expect(page.getByText(next, { exact: true })).toBeVisible();
  await expect(page.locator(`main a[href="${offerPath}"]`)).toBeVisible();
}

async function ship(staff: Page, buyer: Page, tracking: string) {
  await staff.getByRole("button", { name: "Обновить заказ" }).click();
  await staff.getByRole("button", { name: "Отметить готовность" }).click();
  const form = staff.locator("details").filter({ has: staff.getByText("Отправка", { exact: true }) });
  await form.locator("summary").click();
  await form.getByRole("textbox", { name: "Трек-номер СДЭК" }).fill(tracking);
  await form.getByRole("button", { name: "Отметить отправку" }).click();
  await expect(staff.getByText("Отправили заказ", { exact: true })).toBeVisible();
  await buyer.getByRole("button", { name: "Обновить заказ" }).click();
  await expect(buyer.getByRole("heading", { name: "Отслеживание посылки" })).toBeVisible();
  await expect(buyer.getByRole("heading", { name: "Трек-номер СДЭК" })).toBeVisible();
  await expect(buyer.getByText(tracking, { exact: true })).toBeVisible();
}

async function assertNoOverflow(page: Page) {
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(page.viewportSize()!.width);
}

test("документы открываются без входа из подвала и формы заказа", async ({ page }) => {
  await page.goto("/order/photo");
  const email = page.getByRole("textbox", { name: "Email", exact: true });
  await email.fill("privacy-test@example.test");
  const popup = page.waitForEvent("popup");
  await page.locator('form a[href="/privacy"]').first().click();
  const privacy = await popup;
  await expect(privacy.getByRole("heading", { level: 1, name: "Политика обработки персональных данных" })).toBeVisible();
  await privacy.close();
  await expect(email).toHaveValue("privacy-test@example.test");
  const footer = page.getByRole("contentinfo");
  await footer.getByRole("link", { name: "Политика обработки персональных данных", exact: true }).click();
  await expect(page).toHaveURL(/\/privacy$/);
  await expect(page.getByRole("heading", { level: 1, name: "Политика обработки персональных данных" })).toBeVisible();
  await expect(page.locator('meta[name="robots"]')).toHaveAttribute("content", /noindex/);
  await assertNoOverflow(page);

  await footer.getByRole("link", { name: "Доставка и возврат", exact: true }).click();
  await expect(page).toHaveURL(/\/offer#returns$/);
  await expect(page.locator("#returns")).toBeVisible();
  await assertNoOverflow(page);

  const archived = await page.goto(offerPath);
  expect(archived?.status()).toBe(200);
  await expect(page.getByRole("heading", { level: 1, name: "Публичная оферта" })).toBeVisible();
});

test("витрина, переходы и возврат из карточки товара", async ({ page, isMobile }, info) => {
  const html = await (await page.request.get("/")).text();
  expect(html).toMatch(/<img[^>]*class="hero-photo"[^>]*src="\/api\/catalog-images\//);
  await page.goto("/");
  await expect(page.getByRole("heading", { name: "Ваши истории, вышитые нитками" })).toBeInViewport();
  const hero = page.locator(".hero");
  await expect(hero.getByRole("link", { name: "Вышить по моему фото" })).toBeInViewport({ ratio: 1 });
  await expect(hero.getByRole("link", { name: "К коллекциям" })).toBeInViewport({ ratio: 1 });
  await expect.poll(() => page.locator(".hero-photo").evaluate((node: HTMLImageElement) => node.complete && node.naturalWidth > 0)).toBeTruthy();
  await assertNoOverflow(page);
  if (process.env.SHOP_TEST_SCREENSHOTS) {
    await page.screenshot({ path: path.join(process.env.SHOP_TEST_SCREENSHOTS, info.project.name + ".png"), fullPage: true });
  }

  if (isMobile) {
    await page.getByRole("button", { name: "Открыть меню" }).click();
    await expect(page.getByRole("dialog")).toBeVisible();
    await page.getByRole("button", { name: "Закрыть" }).click();
  }
  await hero.getByRole("link", { name: "Вышить по моему фото" }).click();
  await expect(page.getByRole("heading", { name: "Вышивка по фото", exact: true })).toBeVisible();

  await page.goto("/");
  await page.getByRole("link", { name: "К коллекциям" }).click();
  const rus = page.getByRole("link", { name: "Русь", exact: true });
  await rus.click();
  await expect(page).toHaveURL(/\/catalog\/rus#collections$/);
  await expect(rus).toHaveAttribute("aria-current", "page");
  const tile = page.getByRole("button", { name: /Худи «Воля»/ });
  await tile.scrollIntoViewIfNeeded();
  const scroll = await page.evaluate(() => window.scrollY);
  await tile.click();
  const dialog = page.getByRole("dialog");
  await expect(dialog.getByRole("heading", { name: "Худи «Воля»" })).toBeVisible();
  await expect(dialog.getByText(/6\s*900\s*₽/)).toBeInViewport();
  await expect(dialog.getByText(/до ПВЗ в Москве от 350 ₽/)).toBeVisible();
  await dialog.locator("summary").filter({ hasText: "Таблица размеров" }).click();
  await expect(dialog.getByRole("table").getByRole("cell", { name: "57,5", exact: true })).toBeVisible();
  await dialog.getByRole("button", { name: "Показать фото 2" }).click();
  await expect(dialog.getByRole("img", { name: "Худи «Воля» — фото 2" })).toBeVisible();
  await expect(dialog.locator("figcaption")).toHaveText("Вышивка крупным планом");
  const zoom = dialog.getByRole("button", { name: "Увеличить фото 2" });
  await zoom.click();
  await expect(page.getByRole("dialog", { name: "Худи «Воля» — фото 2", exact: true })).toBeVisible();
  await page.keyboard.press("Escape");
  await expect(zoom).toBeFocused();
  await dialog.getByRole("button", { name: "XL", exact: true }).click();
  await expect(dialog.getByRole("button", { name: "XL", exact: true })).toHaveAccessibleDescription("Нет в наличии");
  await expect(dialog.getByRole("button", { name: "Оформить заказ" })).toBeDisabled();
  await dialog.getByRole("button", { name: "L", exact: true }).click();
  await expect(dialog.getByText("Под заказ · изготовление: 7 дней")).toBeVisible();
  await page.keyboard.press("Escape");
  await expect(dialog).toHaveCount(0);
  await expect(tile).toBeFocused();
  expect(await page.evaluate(() => window.scrollY)).toBeCloseTo(scroll, 0);
  await expect(rus).toHaveAttribute("aria-current", "page");
  await page.goBack();
  await expect(page.getByRole("link", { name: "Выбор команды" })).toHaveAttribute("aria-current", "page");

  await page.goto("/catalog/rus");
  await expect(page.getByRole("heading", { name: "Коллекции" })).toBeInViewport();
  await expect(page.getByRole("link", { name: "Русь", exact: true })).toHaveAttribute("aria-current", "page");
  await expect.poll(() => page.evaluate(() => document.querySelector("#collections")!.getBoundingClientRect().top)).toBeLessThan(55);
  await page.locator("#gallery").getByRole("link", { name: "Вышить по моему фото" }).click();
  await expect(page.getByRole("heading", { name: "Вышивка по фото", exact: true })).toBeVisible();
});

test("покупатель и менеджер проводят заказ от заявки до отправки", async ({ page, browser, baseURL }, info) => {
  const mobile = info.project.name === "mobile";
  const email = `${info.project.name}-${testRun}@example.test`;
  const emailField = page.getByRole("textbox", { name: "Email", exact: true });
  const requestCode = page.getByRole("button", { name: "Получить код на email", exact: true });
  if (mobile) {
    await page.goto("/order/photo");
    const chooser = page.waitForEvent("filechooser");
    await page.getByRole("button", { name: "Выбрать фото", exact: true }).click();
    await (await chooser).setFiles(image("coupe-source"));
    await expect(page.getByRole("img", { name: "Выбранное фото", exact: true })).toBeVisible();
    await expect(page.getByText(/от 4 100 ₽ за изделие с вышивкой/)).toBeVisible();
    await page.getByRole("combobox", { name: "Изделие" }).selectOption("HOODIE");
    await page.getByRole("combobox", { name: "Размер" }).selectOption("M");
    await page.getByRole("combobox", { name: "Цвет" }).selectOption("Чёрный");
    await page.getByRole("textbox", { name: /Пожелания/ }).fill("Вышивка на груди");
  } else {
    await page.goto("/catalog/rus");
    await page.getByRole("button", { name: /Худи «Воля»/ }).click();
    const dialog = page.getByRole("dialog");
    await dialog.getByRole("button", { name: "M", exact: true }).click();
    await dialog.getByRole("button", { name: "Оформить заказ" }).click();
    await fillDelivery(dialog);
    const typo = `typo-${testRun}@example.test`;
    await emailField.fill(typo);
    await requestCode.click();
    await expect(page.getByRole("textbox", { name: "Код из письма" })).toBeVisible();
    await dialog.getByRole("button", { name: "К товару" }).click();
    await dialog.getByRole("button", { name: "Оформить заказ" }).click();
    await dialog.getByRole("button", { name: "Изменить email" }).click();
    await expect(emailField).toHaveValue(typo);
    await expect(dialog.getByRole("textbox", { name: "Имя и фамилия" })).toHaveValue("Тестовый Покупатель");
    await expect(dialog.getByRole("textbox", { name: "Телефон" })).toHaveValue("+7 (999) 123-45-67");
    await expect(dialog.getByRole("textbox", { name: "Город" })).toHaveValue("Москва");
    await expect(dialog.getByRole("textbox", { name: /Код или адрес пункта выдачи/ })).toHaveValue("TEST-MSK");
  }
  await emailField.fill(email);
  const seen = await mailIds(page.request, email);
  await requestCode.click();
  await expect(page.getByRole("button", { name: /Отправить код ещё раз/ })).toBeDisabled();
  await confirmCode(page, email, seen, mobile ? "expired-session" : "wrong-code");
  await expect(page).toHaveURL(/\/orders\/[a-f0-9-]{36}$/);
  const orderURL = page.url();
  if (mobile) {
    const preview = page.getByRole("img", { name: "Исходное фото 1", exact: true });
    await expect(preview).toBeVisible();
    const original = await preview.getAttribute("src");
    const chooser = page.waitForEvent("filechooser");
    await page.getByRole("button", { name: "Заменить фото 1" }).click();
    await (await chooser).setFiles(image("coupe-detail"));
    await expect(preview).not.toHaveAttribute("src", original!);
    await page.reload();
    await expect(preview).toBeVisible();
    await expect(preview).not.toHaveAttribute("src", original!);
  } else {
    const editDelivery = page.getByRole("button", { name: "Изменить", exact: true });
    await expect(editDelivery).toBeVisible();
    await expect(page.getByRole("textbox", { name: "Имя и фамилия" })).toHaveCount(0);
    await editDelivery.click();
    await expect(page.getByRole("textbox", { name: "Телефон" })).toHaveValue("+79991234567");
    await expect(page.getByRole("button", { name: "Отправить заявку", exact: true })).toBeDisabled();
    await page.getByRole("button", { name: "Сохранить доставку" }).click();
    await expect(editDelivery).toBeVisible();
  }
  await page.getByRole("button", { name: mobile ? "Получить оценку" : "Отправить заявку", exact: true }).click();
  await expect(page.getByText("На рассмотрении", { exact: true })).toBeVisible();
  await assertNoOverflow(page);

  const staff = await staffPage(browser, baseURL!, info.project.name);
  try {
    if (mobile) {
      await staff.getByRole("link", { name: "Эскизы с ИИ", exact: true }).click();
      const responseModel = staff.getByLabel("Модель для разбора переписки");
      const imageModel = staff.getByLabel("Модель для генерации и правок");
      await responseModel.selectOption("gemini-3-1-flash-lite");
      await imageModel.selectOption("standard");
      const prompt = staff.getByLabel("Системный промпт");
      await prompt.fill("Подготовь эскиз с чёткими контурами.");
      const examples = staff.getByLabel("Добавить примеры");
      await examples.setInputFiles([{
        name: "coupe-detail.jpg", mimeType: "image/jpeg", buffer: readFileSync(image("coupe-detail")),
      }, {
        name: "materials.txt", mimeType: "text/plain", buffer: Buffer.from("Хлопковая нить, два цвета."),
      }]);
      const save = staff.getByRole("button", { name: "Сохранить настройки" });
      if (await save.isEnabled()) {
        await save.click();
        await expect(staff.getByText("Настройки эскизов сохранены.")).toBeVisible();
      }
      await staff.reload();
      await expect(responseModel).toHaveValue("gemini-3-1-flash-lite");
      await expect(imageModel).toHaveValue("standard");
      await expect(prompt).toHaveValue("Подготовь эскиз с чёткими контурами.");
      await expect(staff.getByRole("link", { name: "materials.txt" })).toBeVisible();
    }
    await openStaffOrder(staff, email, mobile ? "CUSTOM_PHOTO" : "CATALOG");
    if (mobile) {
      const quote = staff.locator("details").filter({ has: staff.getByText("Оценить изделие", { exact: true }) });
      await quote.locator("summary").click();
      await quote.getByRole("textbox", { name: "Цена изделия, ₽" }).fill("4100");
      await quote.getByRole("spinbutton", { name: "Срок изготовления, дни" }).fill("7");
      await quote.getByRole("button", { name: "Сохранить оценку" }).click();
      await expect(staff.getByText("Расчёт доставки", { exact: true })).toBeVisible();
      let quoteMail: string | undefined;
      await expect.poll(async () => {
        [quoteMail] = await mailIds(page.request, email, "Цена и срок заказа готовы");
        return quoteMail;
      }, { timeout: 35_000, message: "Mailpit should receive the queued quote email" }).toBeTruthy();
      const quoteMessage: { Text: string } = await (await page.request.get(mail + "/api/v1/message/" + quoteMail)).json();
      const quoteLink = quoteMessage.Text.split(/\r?\n/).find((line) => line.startsWith(baseURL + "/orders/"));
      expect(quoteLink).toBe(orderURL);
      await page.goto(quoteLink!);
      await expect(page.getByRole("heading", { name: "Оценка вышивки" })).toBeVisible();
      await expect(page.getByText(/4\s*100\s*₽/).first()).toBeInViewport();
      await page.getByRole("button", { name: "Указать пункт выдачи" }).click();
      await fillDelivery(page);
      await page.getByRole("button", { name: "Сохранить доставку" }).click();
      await expect(page.getByText("Менеджер проверит выбранный пункт и рассчитает стоимость.")).toBeVisible();
      await staff.getByRole("button", { name: "Обновить заказ" }).click();
    }
    await quoteDelivery(staff);
    await page.getByRole("button", { name: "Обновить заказ" }).click();
    await pay(page, mobile ? /4\s*450,50\s*₽/ : /7\s*250,50\s*₽/, mobile ? "Готовим эскиз" : "Готовим изделие");

    if (mobile) {
      await staff.getByRole("textbox", { name: "Сообщение" }).fill("Пример размещения вышивки");
      await staff.getByLabel(/Вложение/).setInputFiles(image("coupe-detail"));
      await staff.getByRole("button", { name: "Отправить сообщение" }).click();
      await expect(page.getByText("Пример размещения вышивки")).toBeVisible();
      const href = await page.getByRole("link", { name: "Открыть вложение" }).getAttribute("href");
      expect((await page.request.get(href!)).headers()["content-type"]).toBe("image/jpeg");
      await staff.getByRole("button", { name: "Обновить заказ" }).click();
      const sketch = staff.locator("details").filter({ has: staff.getByText("Загрузить свой эскиз", { exact: true }) });
      async function publish(name: string) {
        const summary = sketch.locator("summary");
        if (!await sketch.evaluate((node: HTMLDetailsElement) => node.open)) await summary.click();
        await sketch.getByLabel(/Файл эскиза/).setInputFiles(image(name));
        await sketch.getByRole("button", { name: "Опубликовать эскиз" }).click();
      }
      await publish("coupe-detail");
      await expect(staff.getByRole("heading", { name: "Эскиз · версия 1" })).toBeVisible();
      await page.getByRole("button", { name: "Обновить заказ" }).click();
      await page.getByRole("button", { name: "Запросить правки" }).click();
      await expect(page.getByText("Правки к эскизу", { exact: true })).toBeVisible();
      await staff.getByRole("button", { name: "Обновить заказ" }).click();
      const generation = staff.getByRole("region", { name: "Эскизы с ИИ" });
      await staff.getByRole("navigation", { name: "Разделы заказа" }).getByRole("link", { name: "Эскизы заказа" }).click();
      await expect(generation.getByRole("heading", { name: "Эскизы с ИИ" })).toBeInViewport();
      const variantLimit = generation.getByLabel("Максимум вариантов");
      const generate = generation.getByRole("button", { name: "Сгенерировать эскиз", exact: true });
      await variantLimit.selectOption("2");
      const narrow = staff.viewportSize()!;
      const mobileField = (await variantLimit.boundingBox())!;
      const mobileButton = (await generate.boundingBox())!;
      expect(mobileButton.y).toBeGreaterThanOrEqual(mobileField.y + mobileField.height);
      expect(mobileButton.width).toBeCloseTo(mobileField.width, 0);
      await staff.setViewportSize({ width: 1440, height: 1000 });
      const desktopField = (await variantLimit.boundingBox())!;
      const desktopButton = (await generate.boundingBox())!;
      expect(desktopButton.y).toBeCloseTo(desktopField.y, 0);
      expect(desktopButton.height).toBeCloseTo(desktopField.height, 0);
      expect(desktopButton.x).toBeGreaterThan(desktopField.x);
      await staff.setViewportSize(narrow);
      await generate.click();
      const drafts = generation.getByRole("article");
      await expect(drafts).toHaveCount(2);
      await expect(drafts.nth(1).getByRole("img")).toBeVisible();
      await drafts.nth(1).getByRole("button", { name: "Внести правки" }).click();
      const feedback = drafts.nth(1).getByRole("textbox", { name: "Замечания к эскизу" });
      await expect(feedback).toBeFocused();
      await expect(drafts.nth(1).getByLabel("Максимум вариантов")).toHaveValue("1");
      await feedback.fill("Оставьте только контур");
      await drafts.nth(1).getByRole("button", { name: "Сгенерировать с правками" }).click();
      await expect(drafts).toHaveCount(3);
      await drafts.first().getByRole("button", { name: "Опубликовать покупателю" }).click();
      const publication = staff.getByRole("dialog", { name: "Публикация эскиза" });
      await expect(publication.getByRole("img")).toBeVisible();
      await publication.getByRole("button", { name: "Подтвердить публикацию" }).click();
      await expect(staff.getByRole("heading", { name: "Эскиз · версия 2" })).toBeVisible();
      await page.getByRole("button", { name: "Обновить заказ" }).click();
      await expect(page.getByRole("heading", { name: "Эскиз · версия 2" })).toBeVisible();
      await page.getByRole("button", { name: "Согласовать эскиз" }).click();
      await expect(page.getByText("Вышиваем", { exact: true })).toBeVisible();
    }

    await ship(staff, page, mobile ? "TEST-SHIP-PHOTO" : "TEST-SHIP-CATALOG");
    await assertNoOverflow(staff);
    await page.getByRole("button", { name: "Выйти из заказа" }).click();
    await expect(page.getByRole("heading", { name: "Открыть заказ" })).toBeVisible();
    expect((await page.request.get("/api" + new URL(orderURL).pathname)).status()).toBe(401);

    await checkStaffDirectory(staff, mobile ? "mobile" : "desktop");
    if (!mobile) {
      const storefrontNav = staff.getByRole("navigation", { name: "Меню команды" });
      await storefrontNav.getByRole("link", { name: "Витрина" }).click();
      const field = staff.getByRole("textbox", { name: "Заголовок баннера" });
      await expect(field).toHaveValue("Ваши истории, вышитые нитками");
      await field.fill("Вышивка по вашему фото");
      await staff.getByRole("button", { name: "Сохранить витрину" }).click();
      await expect(staff.getByText("Настройки витрины сохранены.")).toBeVisible();
      try {
        await page.goto("/");
        await expect(page.getByRole("heading", { name: "Вышивка по вашему фото" })).toBeVisible();
      } finally {
        await field.fill("Ваши истории, вышитые нитками");
        await staff.getByRole("button", { name: "Сохранить витрину" }).click();
        await expect(staff.getByText("Настройки витрины сохранены.")).toBeVisible();
      }
      await publishCollection(staff, page);
    }
  } finally {
    await staff.context().close();
  }
});

async function checkStaffDirectory(staff: Page, project: string) {
  await staff.getByRole("link", { name: "Сотрудники", exact: true }).click();
  const email = `ui-new-${project}-${testRun}@example.test`;
  await staff.getByRole("textbox", { name: "Рабочий email" }).fill(email);
  await staff.getByRole("button", { name: "Создать учётную запись" }).click();
  const key = staff.getByRole("textbox", { name: "Ключ TOTP" });
  await expect(key).toHaveValue(/^[A-Z2-7]{32}$/);
  await staff.getByRole("button", { name: "Закрыть ключ" }).click();
  const account = staff.getByRole("listitem").filter({ hasText: email });
  await expect(account.getByText("Сотрудник", { exact: true })).toBeVisible();
  await assertNoOverflow(staff);
  const revoke = account.getByRole("button", { name: "Отозвать доступ" });
  await revoke.click();
  const confirmation = staff.getByRole("dialog", { name: "Отозвать доступ?" });
  await confirmation.getByRole("button", { name: "Отмена" }).click();
  await expect(account).toBeVisible();
  await revoke.click();
  await confirmation.getByRole("button", { name: "Отозвать", exact: true }).click();
  await expect(staff.getByRole("status").filter({ hasText: email })).toBeVisible();
  await expect(account).toHaveCount(0);
}

async function publishCollection(staff: Page, visitor: Page) {
  const nav = staff.getByRole("navigation", { name: "Меню команды" });
  const productName = `Тестовый свитшот ${testRun}`;
  const catalogName = `Проверка коллекции ${testRun}`;
  const catalogSlug = `browser-collection-${testRun}`;
  await nav.getByRole("link", { name: "Товары", exact: true }).click();
  await staff.getByText("Создать товар", { exact: true }).click();
  await staff.getByRole("textbox", { name: "Название" }).fill(productName);
  await staff.getByRole("textbox", { name: "Адрес (slug)" }).fill(`browser-product-${testRun}`);
  await staff.getByRole("textbox", { name: "Тип изделия" }).fill("Свитшот");
  await staff.getByRole("textbox", { name: "Дизайн" }).fill("Две ели");
  await staff.getByRole("button", { name: "Добавить фото" }).click();
  const choose = staff.waitForEvent("filechooser");
  await staff.getByRole("button", { name: "Загрузить JPG или PNG" }).click();
  await (await choose).setFiles(image("forest"));
  await expect(staff.getByRole("textbox", { name: /Обложка товара/ })).not.toHaveValue("");
  await staff.getByRole("button", { name: "Создать черновик товара" }).click();
  await expect(staff.getByRole("heading", { name: productName })).toBeVisible();
  await staff.getByText("Добавить вариант", { exact: true }).click();
  await staff.getByRole("textbox", { name: "Артикул (SKU)" }).fill(`BROWSER-${testRun.toUpperCase()}-M`);
  await staff.getByRole("textbox", { name: "Размер" }).fill("M");
  await staff.getByRole("textbox", { name: "Цвет" }).fill("Серый");
  await staff.getByRole("textbox", { name: "Цена, ₽" }).fill("4900");
  await staff.getByRole("button", { name: "Создать вариант" }).click();
  await expect(staff.getByText("Вариант добавлен.")).toBeVisible();
  await staff.getByRole("checkbox", { name: "Опубликовать товар" }).check();
  await staff.getByRole("button", { name: "Сохранить товар" }).click();
  await expect(staff.getByText("Карточка товара сохранена.")).toBeVisible();

  await nav.getByRole("link", { name: "Коллекции", exact: true }).click();
  await staff.locator("summary").filter({ hasText: /^Создать коллекцию$/ }).click();
  await staff.getByRole("textbox", { name: "Название" }).fill(catalogName);
  await staff.getByRole("textbox", { name: "Адрес (slug)" }).fill(catalogSlug);
  await staff.getByRole("button", { name: "Создать коллекцию" }).click();
  await expect(staff.getByRole("heading", { name: catalogName })).toBeVisible();
  const product = staff.getByRole("combobox", { name: "Товар", exact: true });
  await product.selectOption({ label: `${productName} (browser-product-${testRun})` });
  await staff.getByRole("button", { name: "Добавить в коллекцию" }).click();
  await expect(staff.getByRole("link", { name: productName })).toBeVisible();
  await visitor.goto(`/catalog/${catalogSlug}`);
  await expect(visitor.getByText("Коллекция не найдена или не опубликована.")).toBeVisible();

  await staff.getByRole("checkbox", { name: "Опубликовать коллекцию" }).check();
  await staff.getByRole("button", { name: "Сохранить коллекцию" }).click();
  await expect(staff.getByText("Коллекция сохранена.")).toBeVisible();
  try {
    await visitor.goto("/");
    await visitor.getByRole("link", { name: catalogName }).click();
    await expect(visitor.getByRole("button", { name: new RegExp(productName) })).toBeVisible();
    await visitor.goto(`/catalog/${catalogSlug}`);
    await expect(visitor.getByRole("link", { name: catalogName })).toHaveAttribute("aria-current", "page");
    await expect(visitor.getByRole("button", { name: new RegExp(productName) })).toBeVisible();
  } finally {
    await staff.getByRole("checkbox", { name: "Опубликовать коллекцию" }).uncheck();
    await staff.getByRole("button", { name: "Сохранить коллекцию" }).click();
    await expect(staff.getByText("Коллекция сохранена.")).toBeVisible();
  }
}
