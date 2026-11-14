import type { ReactNode } from "react";
import styles from "./legal-document.module.css";

type LegalDocumentProps = {
  title: string;
  revision: string;
  children: ReactNode;
};

export function LegalDocument({ title, revision, children }: LegalDocumentProps) {
  return <article className={`wrap ${styles.document}`} aria-labelledby="legal-title">
    <header className={styles.header}>
      <h1 id="legal-title">{title}</h1>
      <p className="muted">{revision}</p>
    </header>
    <div className={styles.content}>{children}</div>
  </article>;
}
