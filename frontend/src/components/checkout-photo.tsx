"use client";

import { useEffect, useId, useRef, useState } from "react";
import { imageAccept, validateImage } from "@/lib/api";
import styles from "./checkout.module.css";
import { photoDataNotice } from "./privacy-link";
import { Failure, Icon } from "./ui";

export function CheckoutPhoto({ file, onChange, disabled = false }: {
  file?: File; onChange: (file: File | undefined) => void; disabled?: boolean;
}) {
  const input = useRef<HTMLInputElement>(null);
  const picker = useRef<HTMLButtonElement>(null);
  const hintId = useId();
  const [previewUrl, setPreviewUrl] = useState<string>();
  const [error, setError] = useState<Error>();

  useEffect(() => {
    if (!file) {
      setPreviewUrl(undefined);
      return;
    }
    const url = URL.createObjectURL(file);
    setPreviewUrl(url);
    return () => URL.revokeObjectURL(url);
  }, [file]);

  return <fieldset className="stack">
    <legend>Фото для вышивки</legend>
    {file && previewUrl && <img key={previewUrl} className={styles.preview} src={previewUrl} alt="Выбранное фото"
      onError={() => {
        onChange(undefined);
        setError(new Error("Не удалось открыть снимок. Выберите другое фото."));
      }} />}
    <input ref={input} type="file" accept={imageAccept} hidden disabled={disabled} onChange={(event) => {
      const selected = event.currentTarget.files?.[0];
      event.currentTarget.value = "";
      if (!selected) return;
      try {
        validateImage(selected);
        setError(undefined);
        onChange(selected);
      } catch (failure) {
        if (failure instanceof Error) setError(failure);
      }
    }} />
    <p className="muted" id={hintId}>Выберите чёткий снимок. JPG или PNG, до 10 МиБ. {photoDataNotice}</p>
    <div className="actions">
      <button ref={picker} type="button" className="button outline" aria-describedby={hintId} disabled={disabled} onClick={() => input.current?.click()}>
        <Icon name="photo" /> {file ? "Заменить фото" : "Выбрать фото"}
      </button>
      {file && <button type="button" className="text-link" disabled={disabled} onClick={() => {
        onChange(undefined);
        setError(undefined);
        picker.current?.focus();
      }}>Удалить фото</button>}
    </div>
    <Failure error={error} />
  </fieldset>;
}
