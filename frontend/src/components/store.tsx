"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useId, useRef, useState, type MouseEvent } from "react";
import type { Availability, Catalog, CatalogContents, Page, Product, Variant } from "@/lib/contracts";
import { availabilityLabels, days, money } from "@/lib/format";
import { useResource } from "@/lib/hooks";
import type { StorefrontResult } from "@/lib/storefront-server";
import { Checkout } from "./checkout";
import styles from "./product-options.module.css";
import { Failure, Icon, Modal, Notice, Pending, Picture } from "./ui";

const DEFAULT = "team-choice";

export function Store({ initialSlug, content }: { initialSlug: string | null; content: StorefrontResult }) {
  const router = useRouter();
  const [slug, setSlug] = useState(initialSlug ?? DEFAULT);
  const [page, setPage] = useState(0);
  const [extra, setExtra] = useState<CatalogContents["products"]["items"]>([]);
  const [product, setProduct] = useState<string>();
  const [catalogPage, setCatalogPage] = useState(0);
  const [extraCatalogs, setExtraCatalogs] = useState<Catalog[]>([]);
  const scrolledToCatalog = useRef<string | null>(null);
  const catalogs = useResource<Page<Catalog>>(`/api/catalogs?size=100&page=${catalogPage}`);
  const collection = useResource<CatalogContents>(`/api/catalogs/${encodeURIComponent(slug)}?page=${page}`);

  useEffect(() => {
    setSlug(initialSlug ?? DEFAULT);
    setPage(0);
    setExtra([]);
    setProduct(undefined);
    scrolledToCatalog.current = null;
  }, [initialSlug]);

  useEffect(() => {
    if (!initialSlug || slug !== initialSlug || scrolledToCatalog.current === initialSlug
      || catalogs.loading || collection.loading) return;
    document.getElementById("collections")?.scrollIntoView({ behavior: "instant" });
    scrolledToCatalog.current = initialSlug;
  }, [initialSlug, slug, catalogs.loading, collection.loading]);

  useEffect(() => {
    const onPop = () => {
      const route = window.location.pathname.match(/^\/catalog\/([^/]+)$/);
      setSlug(route?.[1] ?? DEFAULT);
      setPage(0);
      setExtra([]);
      setProduct(undefined);
    };
    window.addEventListener("popstate", onPop);
    return () => window.removeEventListener("popstate", onPop);
  }, []);

  function select(event: MouseEvent<HTMLAnchorElement>, item: Catalog) {
    if (event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
    event.preventDefault();
    if (slug === item.slug) return;
    window.history.pushState(null, "", item.slug === DEFAULT ? "/#collections" : `/catalog/${item.slug}#collections`);
    setSlug(item.slug);
    setPage(0);
    setExtra([]);
  }

  const storefront = content.state === "published" ? content.storefront : null;
  const products = [...extra, ...(collection.value?.products.items ?? [])];
  const choices = [...extraCatalogs, ...(catalogs.value?.items ?? [])];
  return <>
    <section className="hero" aria-labelledby="hero-title">
      {storefront?.heroImage && <Picture key={storefront.heroImage.id} src={storefront.heroImage.url} alt="" className="hero-photo" eager />}
      <div className="hero-shade" />
      <div className="wrap hero-inner">
        <div className="hero-copy">
          <p className="eyebrow hero-kicker"><Icon name="thread" /> Мастерская вышивки</p>
          <h1 id="hero-title">{content.state === "unavailable" ? "Баннер временно недоступен" : storefront?.heroTitle ?? "Вышивка на одежде"}</h1>
          {content.state === "unavailable" ? <button className="text-link" type="button" onClick={() => router.refresh()}>Обновить</button>
            : <p className="hero-description">Перенесём ваше фото на худи, футболку или свитшот.</p>}
          <Link className="button light hero-button" href="/order/photo">Вышить по моему фото <Icon name="arrow" /></Link>
          <a className="hero-jump" href="#collections">К коллекциям <Icon name="down" /></a>
        </div>
      </div>
    </section>

    <section className="wrap store-section" id="collections" aria-labelledby="collections-title">
      <div className="section-head"><h2 id="collections-title">Коллекции</h2>
        <nav className="pills" aria-label="Выбор коллекции">
          {choices.map((item) => <a className="pill" key={item.id} aria-current={item.slug === slug ? "page" : undefined}
            href={item.slug === DEFAULT ? "/#collections" : `/catalog/${item.slug}`} onClick={(event) => select(event, item)}>{item.name}</a>)}
          {catalogs.value?.hasMore && <button className="pill" type="button" onClick={() => {
            setExtraCatalogs(choices);
            setCatalogPage(catalogPage + 1);
          }}>Ещё коллекции</button>}
        </nav>
      </div>
      <Failure error={catalogs.error} retry={catalogs.reload} />
      {catalogs.loading && !choices.length && <Pending>Загружаем коллекции…</Pending>}
      <Failure error={collection.error} retry={collection.reload} />
      <div className="product-grid" aria-busy={collection.loading}>
        {products.map((item) => <button type="button" key={item.id} className="product-tile" onClick={() => setProduct(item.slug)}>
          {item.cover ? <Picture src={item.cover.url} alt="" className="product-cover" /> : <div className="product-cover photo-error">Нет фотографии</div>}
          <span className="product-name">{item.name}</span>
          <span className="product-price">{item.fromPriceKopecks !== null ? `от ${money(item.fromPriceKopecks)}` : "Нет в наличии"}</span>
        </button>)}
      </div>
      {collection.loading && <Pending>Загружаем товары…</Pending>}
      {collection.value && !products.length && <div className="empty"><p>В этой коллекции пока нет товаров.</p><Link href="/order/photo" className="text-link">Заказать вышивку по фото</Link></div>}
      {collection.value?.products.hasMore && <div className="more"><button type="button" className="button outline" onClick={() => {
        setExtra(products);
        setPage(page + 1);
      }}>Показать ещё <Icon name="down" /></button></div>}
    </section>

    <section className="gallery-section" id="gallery" aria-labelledby="gallery-title">
      <div className="wrap store-section">
        <div className="section-head gallery-head"><h2 id="gallery-title">Примеры работ</h2>
          <Link className="button outline" href="/order/photo">Вышить по моему фото <Icon name="arrow" /></Link>
        </div>
        {storefront && storefront.gallery.length > 0 && <div className="gallery-grid">
          {storefront.gallery.map((item, index) => <figure className="gallery-item" key={`${item.embroidery.id}-${index}`}>
            <div className={`gallery-pair ${item.source ? "paired" : ""}`}>
              {item.source && <div><Picture src={item.source.url} alt="Исходное фото" /><span>Исходное фото</span></div>}
              <div><Picture src={item.embroidery.url} alt={item.caption || "Готовая вышивка"} /><span>Готовая вышивка</span></div>
            </div>
            {item.caption && <figcaption>{item.caption}</figcaption>}
          </figure>)}
        </div>}
        {content.state === "unavailable" && <p className="empty">Примеры работ временно недоступны.</p>}
        {(content.state === "unpublished" || storefront?.gallery.length === 0) && <p className="empty">Примеры работ ещё не опубликованы.</p>}
      </div>
    </section>
    {product && <ProductModal key={`${slug}/${product}`} slug={slug} productSlug={product}
      deliveryPriceHint={storefront?.deliveryPriceHint ?? null} close={() => setProduct(undefined)} />}
  </>;
}

function ProductModal({ slug, productSlug, deliveryPriceHint, close }: {
  slug: string; productSlug: string; deliveryPriceHint: string | null; close: () => void;
}) {
  const resource = useResource<Product>(`/api/catalogs/${encodeURIComponent(slug)}/products/${encodeURIComponent(productSlug)}`);
  const [checkoutBusy, setCheckoutBusy] = useState(false);
  return <Modal title={resource.value?.name ?? "Карточка товара"} onClose={close} closeDisabled={checkoutBusy} wide>
    {resource.loading && <Pending>Загружаем товар…</Pending>}
    <Failure error={resource.error} retry={resource.reload} />
    {resource.value && <ProductOptions product={resource.value} slug={slug} deliveryPriceHint={deliveryPriceHint}
      checkoutBusy={checkoutBusy} onCheckoutBusy={setCheckoutBusy} />}
  </Modal>;
}

const sizeOrder = ["XXXS", "XXS", "XS", "S", "M", "L", "XL", "XXL", "XXXL", "XXXXL"];
const compareNames = new Intl.Collator("ru-RU", { numeric: true });
const formatCm = new Intl.NumberFormat("ru-RU", { maximumFractionDigits: 1 });
const optionKey = (name: string) => name.toLowerCase();

function optionNames(variants: Variant[], key: "size" | "color") {
  return [...new Map(variants.map((variant) => [optionKey(variant[key]), variant[key]])).values()];
}

function compareSizes(left: string, right: string) {
  const position = (size: string) => {
    const index = sizeOrder.indexOf(size.toUpperCase());
    return index === -1 ? sizeOrder.length : index;
  };
  return position(left) - position(right) || compareNames.compare(left, right);
}

function availabilityFor(variants: Variant[]): Availability {
  if (variants.some((variant) => variant.availability === "IN_STOCK")) return "IN_STOCK";
  if (variants.some((variant) => variant.availability === "MADE_TO_ORDER")) return "MADE_TO_ORDER";
  return "OUT_OF_STOCK";
}

function optionStatus(variants: Variant[], color: string) {
  const relevant = color ? variants.filter((variant) => optionKey(variant.color) === color) : variants;
  const status = availabilityFor(relevant);
  const otherColors = color && status === "OUT_OF_STOCK" && availabilityFor(variants) !== "OUT_OF_STOCK";
  return { status, label: otherColors ? "В других цветах" : availabilityLabels[status] };
}

function ProductOptions({ product, slug, deliveryPriceHint, checkoutBusy, onCheckoutBusy }: {
  product: Product; slug: string; deliveryPriceHint: string | null; checkoutBusy: boolean; onCheckoutBusy: (busy: boolean) => void;
}) {
  const [size, setSize] = useState("");
  const [chosenColor, setChosenColor] = useState("");
  const [checkout, setCheckout] = useState(false);
  const [checkoutVariant, setCheckoutVariant] = useState<Variant>();
  const detailTitle = useRef<HTMLHeadingElement>(null);
  const checkoutTitle = useRef<HTMLHeadingElement>(null);
  const optionsRef = useRef<HTMLFieldSetElement>(null);
  const optionId = useId();
  useEffect(() => {
    if (!checkoutVariant) return;
    const title = checkout ? checkoutTitle.current : detailTitle.current;
    title?.closest("dialog")?.scrollTo({ top: 0, behavior: "instant" });
    title?.focus({ preventScroll: true });
  }, [checkout, checkoutVariant]);
  const { variants, images, measurements, name, garmentType, design, description } = product;
  const sizes = optionNames(variants, "size").sort(compareSizes);
  const sizeVariants = size ? variants.filter((item) => optionKey(item.size) === size) : variants;
  const colors = optionNames(sizeVariants, "color");
  const availableColors = optionNames(sizeVariants.filter((item) => item.availability !== "OUT_OF_STOCK"), "color");
  const automaticColor = colors.length === 1 ? colors[0] : availableColors.length === 1 ? availableColors[0] : "";
  const color = chosenColor || optionKey(automaticColor);
  const matchingVariants = color ? sizeVariants.filter((item) => optionKey(item.color) === color) : sizeVariants;
  const variant = size && color ? matchingVariants[0] : undefined;
  const selectedVariant = variant?.availability !== "OUT_OF_STOCK" ? variant : undefined;
  const hasAvailableVariants = variants.some((item) => item.availability !== "OUT_OF_STOCK");
  return <>
    <div hidden={checkout}>
      <div className={styles.detail}>
        <div className={styles.heading}>
          <h2 ref={detailTitle} tabIndex={-1}>{name}</h2>
          <div aria-live="polite">
            <ProductPrice variants={matchingVariants} />
            {sizes.length > 0 && !hasAvailableVariants && <p className="price-large">Нет в наличии</p>}
          </div>
        </div>
        <ProductGallery name={name} photos={images} />
        <div className={styles.purchase}>
          {sizes.length === 0 ? <Notice error>У товара нет вариантов.</Notice> : <>
            <fieldset ref={optionsRef}>
              <legend>Размер</legend>
              <div className={styles.options}>{sizes.map((value, index) => {
                const key = optionKey(value);
                const variantsForSize = variants.filter((item) => optionKey(item.size) === key);
                const { status, label } = optionStatus(variantsForSize, chosenColor);
                const canOrderSize = availabilityFor(variantsForSize) !== "OUT_OF_STOCK";
                return <div key={key} className={styles.option} data-unavailable={status === "OUT_OF_STOCK"}>
                  <button className={styles.optionButton} type="button" aria-pressed={size === key}
                    aria-describedby={`${optionId}-size-${index}`} data-orderable={canOrderSize} onClick={() => {
                      setSize(key);
                      if (!variantsForSize.some((item) => optionKey(item.color) === chosenColor)) setChosenColor("");
                    }}>{value}</button>
                  <span className={styles.optionStatus} id={`${optionId}-size-${index}`}>{label}</span>
                </div>;
              })}</div>
              {measurements.length > 0 && <SizeGuide measurements={measurements} size={size} />}
            </fieldset>
            {colors.length === 1 ? <p className={styles.singleColor}><strong>Цвет</strong> {colors[0]}</p> : <fieldset>
              <legend>Цвет</legend>
              <div className={styles.options}>{colors.map((value, index) => {
                const key = optionKey(value);
                const status = availabilityFor(sizeVariants.filter((item) => optionKey(item.color) === key));
                return <div key={key} className={styles.option} data-unavailable={status === "OUT_OF_STOCK"}>
                  <button className={styles.optionButton} type="button" aria-pressed={color === key}
                    aria-describedby={`${optionId}-color-${index}`} onClick={() => setChosenColor(key)}>{value}</button>
                  <span className={styles.optionStatus} id={`${optionId}-color-${index}`}>{availabilityLabels[status]}</span>
                </div>;
              })}</div>
            </fieldset>}
            <div aria-live="polite">
              {variant && hasAvailableVariants && <p>{availabilityLabels[variant.availability]}{variant.productionDays !== null && ` · изготовление: ${days(variant.productionDays)}`}</p>}
              {!variant && size && !color && hasAvailableVariants && <p className="muted">Выберите цвет.</p>}
            </div>
          </>}
          {size || !hasAvailableVariants ? <button className={`button solid ${styles.checkoutButton}`} type="button" disabled={!selectedVariant} onClick={() => {
            if (!selectedVariant) return;
            setCheckoutVariant(selectedVariant);
            setCheckout(true);
          }}>Оформить заказ <Icon name="arrow" /></button> : <button className={`button solid ${styles.checkoutButton}`} type="button" onClick={() => {
            optionsRef.current?.querySelector<HTMLButtonElement>('button[data-orderable="true"]')?.focus();
          }}>Выбрать размер <Icon name="arrow" /></button>}
          <div className={styles.delivery}>
            <p>Доставка СДЭК до пункта выдачи.</p>
            {deliveryPriceHint && <p>{deliveryPriceHint}</p>}
            <p>Менеджер рассчитает доставку после заявки. Итог появится до оплаты.</p>
          </div>
        </div>
        <div className={styles.description}>
          <p className="eyebrow">{garmentType} / {design}</p>
          {description && <p className="preline">{description}</p>}
        </div>
      </div>
    </div>
    {checkoutVariant && <div hidden={!checkout}>
      <div className="checkout-modal">
        <button type="button" className="text-link" disabled={checkoutBusy} onClick={() => setCheckout(false)}><Icon name="back" /> К товару</button>
        <h2 ref={checkoutTitle} tabIndex={-1}>Оформление заказа</h2>
        <Checkout selection={{ catalogSlug: slug, productName: name, variant: checkoutVariant }} active={checkout} onBusyChange={onCheckoutBusy} />
      </div>
    </div>}
  </>;
}

function ProductGallery({ name, photos }: { name: string; photos: Product["images"] }) {
  const [active, setActive] = useState(0);
  const [enlarged, setEnlarged] = useState(false);
  const preview = useRef<HTMLDialogElement>(null);
  const photo = photos[active];
  if (!photo) return <div className={styles.gallery}><Notice error>У товара нет фотографий.</Notice></div>;

  const description = `${name} — фото ${active + 1}`;
  return <div className={styles.gallery}>
    <figure>
      <button className={styles.photoButton} type="button" aria-label={`Увеличить фото ${active + 1}`} onClick={() => {
        preview.current?.showModal();
        setEnlarged(true);
      }}>
        <Picture key={photo.id} src={photo.url} alt={description} className={styles.photo} eager />
      </button>
      {photo.caption && <figcaption className={`${styles.caption} muted`}>{photo.caption}</figcaption>}
    </figure>
    {photos.length > 1 && <div className={styles.thumbnails}>
      {photos.map((item, i) => <button type="button" key={item.id} aria-pressed={active === i} aria-label={`Показать фото ${i + 1}`} onClick={() => setActive(i)}>
        <Picture src={item.url} alt="" />
      </button>)}
    </div>}
    <dialog className={styles.preview} ref={preview} aria-label={description} onClose={() => setEnlarged(false)} onCancel={(event) => event.stopPropagation()}>
      <div className={styles.previewBody}>
        <button type="button" className={`icon-button ${styles.closePreview}`} aria-label="Закрыть фото" onClick={() => preview.current?.close()}><Icon name="close" /></button>
        {enlarged && <>
          <Picture key={photo.id} src={photo.url} alt={description} className={styles.previewImage} />
          {photo.caption && <p className={`${styles.caption} muted`}>{photo.caption}</p>}
        </>}
      </div>
    </dialog>
  </div>;
}

function ProductPrice({ variants }: { variants: Variant[] }) {
  const prices = variants.filter((item) => item.availability !== "OUT_OF_STOCK").map((item) => item.priceKopecks);
  if (prices.length === 0) return null;
  const lowest = prices.reduce((min, price) => Math.min(min, price));
  return <p className="price-large">{prices.some((price) => price !== lowest) && "от "}{money(lowest)}</p>;
}

function SizeGuide({ measurements, size }: { measurements: Product["measurements"]; size: string }) {
  const hasSleeve = measurements.some(({ sleeveLengthCm }) => sleeveLengthCm !== null);
  return <details className={styles.sizeGuide}>
    <summary>Таблица размеров</summary>
    <p className="muted">Сравните замеры с вещью, которая вам подходит. Измерьте ширину под проймами и длину от плеча до низа.{hasSleeve && " Длину рукава измерьте от плеча до манжеты."}</p>
    <table>
      <caption className="sr-only">Замеры изделия в сантиметрах</caption>
      <thead><tr><th scope="col">Размер</th><th scope="col">Ширина, см</th><th scope="col">Длина, см</th>{hasSleeve && <th scope="col">Рукав, см</th>}</tr></thead>
      <tbody>{measurements.map((row) => <tr key={row.size} data-selected={size === optionKey(row.size)}>
        <th scope="row">{row.size}</th>
        <td>{formatCm.format(row.widthCm)}</td>
        <td>{formatCm.format(row.lengthCm)}</td>
        {hasSleeve && <td>{row.sleeveLengthCm === null ? "—" : formatCm.format(row.sleeveLengthCm)}</td>}
      </tr>)}</tbody>
    </table>
  </details>;
}
