"use client";

import { useId, useState } from "react";
import type { EmbroideryFrame, PhotoOrderOption } from "@/lib/contracts";
import { embroideryFrameLabel, garmentLabels, money } from "@/lib/format";
import styles from "./checkout.module.css";

const garmentSizes = ["XS", "S", "M", "L", "XL", "XXL"];
const colors = ["Чёрный", "Белый", "Молочный", "Серый", "Бордовый"];
const frameDescriptions: Record<EmbroideryFrame, string> = {
  SMALL: "Компактная вышивка, как логотип. Подходит для простых рисунков.",
  STANDARD: "Стандартная вышивка. Подходит для рисунка с читаемым госномером.",
  LARGE: "Больше мелких деталей и теней. Рекомендуем вышивать на спинке.",
};

export function PhotoOrderConfiguration({ options }: { options: PhotoOrderOption[] }) {
  const [garment, setGarment] = useState("");
  const [frame, setFrame] = useState<EmbroideryFrame>();
  const [threePhotoBackground, setThreePhotoBackground] = useState(false);
  const id = useId();
  const garmentOptions = options.filter((option) => option.garment === garment);
  const frames = garmentOptions.filter((option) => !option.threePhotoBackground);
  const base = frames.find((option) => option.frame === frame);
  const background = garmentOptions.find((option) => option.frame === frame && option.threePhotoBackground);
  const selected = threePhotoBackground ? background : base;
  const backgroundSurcharge = base?.priceKopecks != null && background?.priceKopecks != null
    ? background.priceKopecks - base.priceKopecks : null;

  return <>
    <label className="field">Изделие
      <select name="garment" value={garment} required onChange={(event) => setGarment(event.currentTarget.value)}>
        <option value="" disabled>Выберите изделие</option>
        {Object.entries(garmentLabels).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
      </select>
    </label>
    <div className="field-row">
      <label className="field">Размер одежды
        <select name="size" defaultValue="" required>
          <option value="" disabled>Выберите размер</option>
          {garmentSizes.map((size) => <option key={size}>{size}</option>)}
        </select>
      </label>
      <label className="field">Цвет
        <select name="color" defaultValue="" required>
          <option value="" disabled>Выберите цвет</option>
          {colors.map((color) => <option key={color}>{color}</option>)}
        </select>
      </label>
    </div>
    <fieldset className={`stack ${styles.configuration}`}>
      <legend>Размер вышивки</legend>
      {!garment && <p className="muted">Выберите изделие, чтобы увидеть варианты и цены.</p>}
      {frames.length > 0 && <div className={styles.options}>
        {frames.map((option) => {
          const title = `${id}-${option.frame}`;
          return <label className={styles.option} key={option.frame}>
            <input name="embroideryFrame" type="radio" value={option.frame} required checked={frame === option.frame}
              aria-labelledby={title} aria-describedby={`${title}-details`} onChange={() => {
                setFrame(option.frame);
                setThreePhotoBackground(false);
              }} />
            <span className={styles.optionText}>
              <strong id={title}>{embroideryFrameLabel(option.sizeMm)}</strong>
              <span id={`${title}-details`} className="muted">{frameDescriptions[option.frame]}</span>
            </span>
            {option.priceKopecks !== null && <strong className={styles.optionPrice}>{money(option.priceKopecks)}</strong>}
          </label>;
        })}
      </div>}
      {background && <div className={styles.addOn}>
        <label className="check-field">
          <input type="checkbox" name="threePhotoBackground" checked={threePhotoBackground}
            aria-labelledby={`${id}-background-label`} aria-describedby={`${id}-background`}
            onChange={(event) => setThreePhotoBackground(event.currentTarget.checked)} />
          <span id={`${id}-background-label`}>Фон из трёх кадров авто</span>
          {backgroundSurcharge !== null && <span className={styles.optionPrice}>+{money(backgroundSurcharge)}</span>}
        </label>
        <p id={`${id}-background`} className="muted">Для этой композиции нужны три фото. Их можно добавить в карточке после подтверждения email.</p>
      </div>}
      <div className={styles.addOn}>
        <label className="check-field"><input type="checkbox" name="licensePlate" aria-describedby={`${id}-plate`} /> Вышить госномер</label>
        <p id={`${id}-plate`} className="muted">Для вышивки авто. Бесплатно при полной оплате.</p>
      </div>
      <div aria-live="polite">
        {selected && <div className={styles.configPrice}>
          <dl className="order-facts"><div>
            <dt>Стоимость изделия</dt><dd>{selected.priceKopecks === null ? "Рассчитает менеджер" : money(selected.priceKopecks)}</dd>
          </div></dl>
          <p className="muted">Доставка рассчитывается отдельно.</p>
        </div>}
      </div>
    </fieldset>
  </>;
}
