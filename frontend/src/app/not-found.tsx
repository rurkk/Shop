import Link from "next/link";

export default function NotFound() {
  return <section className="wrap narrow page-block">
    <p className="eyebrow">404</p><h1>Страница не найдена</h1>
    <p className="lede">Проверьте адрес или вернитесь на витрину.</p>
    <Link className="button solid" href="/">На главную</Link>
  </section>;
}
