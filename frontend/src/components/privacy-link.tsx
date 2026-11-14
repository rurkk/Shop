import Link from "next/link";

export const photoDataNotice = "Присылайте фото без чужих персональных данных. Не прикладывайте документы.";

export function PrivacyLink() {
  return <Link className="text-link" href="/privacy" prefetch={false} target="_blank" rel="noopener noreferrer"
    title="Откроется в новой вкладке">Политика обработки персональных данных</Link>;
}
