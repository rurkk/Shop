import Link from "next/link";
import { connection } from "next/server";
import { Suspense } from "react";
import { Checkout } from "@/components/checkout";
import styles from "@/components/checkout.module.css";
import { Failure, Icon, Pending, Picture } from "@/components/ui";
import type { PhotoOrderOption } from "@/lib/contracts";
import { getStorefront } from "@/lib/storefront-server";

export const metadata = { title: "Вышивка по фото" };

export default function Page() {
  return <section className={`wrap page-block checkout-page ${styles.photoPage}`}>
    <div className="checkout-intro">
      <Link href="/" className="text-link"><Icon name="back" /> На витрину</Link>
      <h1>Вышивка по фото</h1>
      <p className="lede">Выберите изделие, размер вышивки и фото для эскиза.</p>
      <Suspense><PhotoOrderTiming /></Suspense>
    </div>
    <div className={`panel checkout-panel ${styles.photoForm}`}>
      <Suspense fallback={<Pending>Загружаем варианты вышивки…</Pending>}><PhotoOrderForm /></Suspense>
    </div>
    <div className={`${styles.example} stack`}>
      <Suspense><PhotoOrderExample /></Suspense>
      <p>После оплаты подготовим эскиз. Вышивать начнём, когда вы согласуете его в карточке заказа.</p>
    </div>
  </section>;
}

async function PhotoOrderForm() {
  await connection();
  try {
    const origin = process.env.SHOP_BACKEND_ORIGIN;
    if (!origin) throw new Error("Set SHOP_BACKEND_ORIGIN to load photo order options.");
    const response = await fetch(new URL("/api/orders/photo-options", origin), {
      headers: { Accept: "application/json" },
      cache: "no-store",
      redirect: "error",
      signal: AbortSignal.timeout(3000),
    });
    if (!response.ok) throw new Error(`Photo options API returned ${response.status}`);
    const options: PhotoOrderOption[] = await response.json();
    if (!Array.isArray(options) || !options.length) throw new Error("Photo options API returned no options");
    return <Checkout photoOptions={options} />;
  } catch (error) {
    console.error("Unable to load photo order options.", error instanceof Error ? error.message : error);
    return <Failure error={new Error("Не удалось загрузить варианты вышивки. Обновите страницу.")} />;
  }
}

async function PhotoOrderExample() {
  const result = await getStorefront();
  if (result.state === "unavailable") return <Failure error={new Error("Не удалось загрузить пример работы.")} />;
  if (result.state !== "published") return null;
  const example = result.storefront.gallery.find((item) => item.source);
  if (!example?.source) return null;
  return <figure className="gallery-item">
    <div className="gallery-pair paired">
      <div><Picture src={example.source.url} alt="" /><span>Исходное фото</span></div>
      <div><Picture src={example.embroidery.url} alt="" /><span>Готовая вышивка</span></div>
    </div>
    {example.caption && <figcaption>{example.caption}</figcaption>}
  </figure>;
}

async function PhotoOrderTiming() {
  const result = await getStorefront();
  if (result.state !== "published") return null;
  const { photoOrderTimingHint } = result.storefront;
  if (!photoOrderTimingHint) return null;
  return <p className="muted">{photoOrderTimingHint}</p>;
}
