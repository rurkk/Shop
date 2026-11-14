"use client";

import { useEffect, useRef, useState, type ReactNode } from "react";

const icons = {
  menu: <><path d="M3 6h18M3 12h18M3 18h18" /></>,
  close: <path d="m5 5 14 14M19 5 5 19" />,
  bag: <><path d="M5 7h14l1.5 14h-17z" /><path d="M9 9V6a3 3 0 0 1 6 0v3" /></>,
  arrow: <path d="M3 12h17m-6-6 6 6-6 6" />,
  back: <path d="M21 12H4m6 6-6-6 6-6" />,
  down: <path d="M12 3v17m-6-6 6 6 6-6" />,
  up: <path d="M12 21V4m-6 6 6-6 6 6" />,
  external: <path d="M5 19 19 5M8 5h11v11" />,
  upload: <path d="M12 16V3m-5 5 5-5 5 5M4 16v5h16v-5" />,
  plus: <path d="M12 4v16M4 12h16" />,
  check: <path d="m4 12 5 5L20 6" />,
  link: <><path d="m9 15 6-6m-2-2 2-2a3 3 0 0 1 4 4l-2 2M7 13l-2 2a3 3 0 0 0 4 4l2-2" /></>,
  photo: <><rect x="3" y="3" width="18" height="18" rx="1" /><circle cx="8" cy="8" r="1" /><path d="m3 17 5-5 4 4 4-5 5 6" /></>,
  thread: <path d="M12 2v20M2 12h20M5 5l14 14M19 5 5 19" />,
};

export function Icon({ name }: { name: keyof typeof icons }) {
  return <svg aria-hidden="true" viewBox="0 0 24 24" width="24" height="24" fill="none" stroke="currentColor" strokeWidth="1.65" strokeLinecap="round" strokeLinejoin="round">{icons[name]}</svg>;
}

export function Modal({ title, children, onClose, wide, closeDisabled }: {
  title: string; children: ReactNode; onClose: () => void; wide?: boolean; closeDisabled?: boolean;
}) {
  const dialog = useRef<HTMLDialogElement>(null);
  useEffect(() => {
    const element = dialog.current!;
    const trigger = document.activeElement;
    element.showModal();
    const previous = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    return () => {
      element.close();
      document.body.style.overflow = previous;
      if (trigger instanceof HTMLElement) trigger.focus({ preventScroll: true });
    };
  }, []);
  return (
    <dialog ref={dialog} aria-label={title} className={`modal ${wide ? "modal-wide" : ""}`}
      onCancel={(event) => { event.preventDefault(); if (!closeDisabled) onClose(); }}
      onClick={(event) => { if (!closeDisabled && event.target === event.currentTarget) onClose(); }}>
      <div className="modal-inner">
        <button type="button" className="icon-button modal-close" aria-label="Закрыть" disabled={closeDisabled} onClick={onClose}><Icon name="close" /></button>
        {children}
      </div>
    </dialog>
  );
}

export function Notice({ children, error = false }: { children: ReactNode; error?: boolean }) {
  return <div className={`notice ${error ? "notice-error" : ""}`} role={error ? "alert" : "status"}>{children}</div>;
}

export function Failure({ error, retry }: { error?: Error; retry?: () => void }) {
  if (!error) return null;
  return <Notice error>{error.message}{retry && <button type="button" className="text-link" onClick={retry}>Обновить</button>}</Notice>;
}

export function Pending({ children = "Загружаем…" }: { children?: ReactNode }) {
  return <p className="pending" role="status">{children}</p>;
}

export function Picture({ src, alt, className, eager = false }: { src: string; alt: string; className?: string; eager?: boolean }) {
  const [failed, setFailed] = useState(false);
  return failed
    ? <div className={`photo-error ${className ?? ""}`} role="img" aria-label={`${alt}: фото не загрузилось`}><Icon name="photo" /><span>Фото не загрузилось</span></div>
    : <img className={className} src={src} alt={alt} loading={eager ? "eager" : "lazy"} fetchPriority={eager ? "high" : undefined} onError={() => setFailed(true)} />;
}

export function Pagination({ page, hasMore, onPage }: { page: number; hasMore: boolean; onPage: (page: number) => void }) {
  if (page === 0 && !hasMore) return null;
  return <nav className="pager" aria-label="Страницы">
    <button className="button outline small" type="button" disabled={page === 0} onClick={() => onPage(page - 1)}>Назад</button>
    <span>Страница {page + 1}</span>
    <button className="button outline small" type="button" disabled={!hasMore} onClick={() => onPage(page + 1)}>Дальше</button>
  </nav>;
}
