import Link from "next/link";
import { Suspense } from "react";
import { Checkout } from "@/components/checkout";
import styles from "@/components/checkout.module.css";
import { Failure, Icon, Picture } from "@/components/ui";
import { getStorefront } from "@/lib/storefront-server";

export const metadata = { title: "Вышивка по фото" };

export default function Page() {
  return <section className={`wrap page-block checkout-page ${styles.photoPage}`}>
    <div className="checkout-intro">
      <Link href="/" className="text-link"><Icon name="back" /> На витрину</Link>
      <h1>Вышивка по фото</h1>
      <p className="lede">Выберите фото, чтобы получить оценку стоимости и срока вышивки.</p>
      <Suspense><PhotoOrderHints /></Suspense>
    </div>
    <div className={`panel checkout-panel ${styles.photoForm}`}><Checkout /></div>
    <div className={`${styles.example} stack`}>
      <Suspense><PhotoOrderExample /></Suspense>
      <p>После оплаты подготовим эскиз. Вышивать начнём, когда вы согласуете его в карточке заказа.</p>
    </div>
  </section>;
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

async function PhotoOrderHints() {
  const result = await getStorefront();
  if (result.state !== "published") return null;
  const { photoOrderPriceHint, photoOrderTimingHint } = result.storefront;
  if (!photoOrderPriceHint && !photoOrderTimingHint) return null;
  return <div className="muted">
    {photoOrderPriceHint && <p>{photoOrderPriceHint}</p>}
    {photoOrderTimingHint && <p>{photoOrderTimingHint}</p>}
  </div>;
}
