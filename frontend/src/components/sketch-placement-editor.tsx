"use client";

import { useRef, type KeyboardEvent, type PointerEvent } from "react";
import { Picture } from "./ui";
import styles from "./admin-order.module.css";

export type SketchPosition = { xPercent: number; yPercent: number };
type Rectangle = { x: number; y: number; width: number; height: number };
export type SketchPanelPreview = { area: Rectangle; defaultPosition: SketchPosition };

type Measurements = {
  panelWidthMm: number; panelHeightMm: number; embroideryWidthMm: number; embroideryHeightMm: number;
};

const canvasSize = 1024;
const limit = (value: number) => Math.max(0, Math.min(100, Math.round(value)));

export function SketchPlacementEditor({ src, panel, position, measurements, onChange }: {
  src: string; panel: SketchPanelPreview; position: SketchPosition; measurements: Measurements;
  onChange: (position: SketchPosition) => void;
}) {
  const canvas = useRef<HTMLDivElement>(null);
  const drag = useRef<{ x: number; y: number; position: SketchPosition; scaleX: number; scaleY: number }>(null);
  const { area, defaultPosition } = panel;
  const { panelWidthMm, panelHeightMm, embroideryWidthMm, embroideryHeightMm } = measurements;
  const width = Math.round(area.width * embroideryWidthMm / panelWidthMm);
  const height = Math.round(area.height * embroideryHeightMm / panelHeightMm);
  const fits = width >= 2 && width <= area.width && height >= 2 && height <= area.height &&
    embroideryWidthMm <= panelWidthMm && embroideryHeightMm <= panelHeightMm;
  const travelX = area.width - width;
  const travelY = area.height - height;
  const left = area.x + Math.floor(travelX * position.xPercent / 100);
  const top = area.y + Math.floor(travelY * position.yPercent / 100);
  const moved = position.xPercent !== defaultPosition.xPercent || position.yPercent !== defaultPosition.yPercent;

  function pointerDown(event: PointerEvent<HTMLButtonElement>) {
    if (event.button !== 0 || !canvas.current) return;
    const bounds = canvas.current.getBoundingClientRect();
    event.currentTarget.setPointerCapture(event.pointerId);
    drag.current = {
      x: event.clientX, y: event.clientY, position,
      scaleX: travelX > 0 ? 100 * canvasSize / (bounds.width * travelX) : 0,
      scaleY: travelY > 0 ? 100 * canvasSize / (bounds.height * travelY) : 0,
    };
  }

  function pointerMove(event: PointerEvent<HTMLButtonElement>) {
    const start = drag.current;
    if (!start || !event.currentTarget.hasPointerCapture(event.pointerId)) return;
    onChange({
      xPercent: limit(start.position.xPercent + (event.clientX - start.x) * start.scaleX),
      yPercent: limit(start.position.yPercent + (event.clientY - start.y) * start.scaleY),
    });
  }

  function keyDown(event: KeyboardEvent<HTMLButtonElement>) {
    const step = event.shiftKey ? 10 : 2;
    let { xPercent, yPercent } = position;
    switch (event.key) {
      case "ArrowLeft": xPercent -= step; break;
      case "ArrowRight": xPercent += step; break;
      case "ArrowUp": yPercent -= step; break;
      case "ArrowDown": yPercent += step; break;
      default: return;
    }
    event.preventDefault();
    onChange({ xPercent: limit(xPercent), yPercent: limit(yPercent) });
  }

  const percent = (pixels: number) => `${100 * pixels / canvasSize}%`;
  return <div className={styles.placementEditor}>
    <div ref={canvas} className={styles.placementCanvas}>
      <Picture src={src} alt="Заготовка выбранной стороны изделия" className={styles.placementImage} />
      <div aria-hidden="true" className={styles.placementPanel} style={{
        left: percent(area.x), top: percent(area.y), width: percent(area.width), height: percent(area.height),
      }} />
      {fits && <button type="button" className={styles.placementFrame} aria-label="Рамка вышивки"
        aria-description="Перетащите рамку или сдвиньте её стрелками"
        style={{ left: percent(left), top: percent(top), width: percent(width), height: percent(height) }}
        onPointerDown={pointerDown} onPointerMove={pointerMove} onLostPointerCapture={() => { drag.current = null; }}
        onKeyDown={keyDown} />}
    </div>
    <p className="muted">{fits ? "Перетащите рамку или сдвиньте её стрелками." : "Укажите замеры панели и вышивки, чтобы увидеть рамку."}</p>
    {fits && moved && <button type="button" className="text-link" onClick={() => onChange(defaultPosition)}>Вернуть исходное положение</button>}
  </div>;
}
