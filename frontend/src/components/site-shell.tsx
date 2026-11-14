"use client";

import Link from "next/link";
import { useState, type ReactNode } from "react";
import { Icon, Modal } from "./ui";

export function SiteShell({ children }: { children: ReactNode }) {
  const [menu, setMenu] = useState(false);
  return <>
    <a href="#main" className="skip-link">Перейти к содержимому</a>
    <header className="site-header">
      <button className="icon-button mobile-menu" type="button" aria-label="Открыть меню" onClick={() => setMenu(true)}><Icon name="menu" /></button>
      <Link href="/" className="wordmark" aria-label="KNITSEW — главная">KNITSEW</Link>
      <nav className="desktop-nav" aria-label="Основное меню">
        <Link href="/#collections">Коллекции</Link>
        <Link href="/order/photo">Вышивка по фото</Link>
        <Link href="/#gallery">Примеры работ</Link>
      </nav>
      <Link href="/orders" className="order-link" aria-label="Мой заказ"><Icon name="bag" /><span>Мой заказ</span></Link>
    </header>
    <main id="main">{children}</main>
    <footer className="site-footer">
      <div className="wrap footer-layout">
        <div className="footer-brand">
          <Link href="/" className="wordmark" aria-label="KNITSEW — главная">KNITSEW</Link>
          <p>Мастерская вышивки на одежде</p>
        </div>
        <nav aria-labelledby="footer-shop">
          <p id="footer-shop" className="eyebrow">Магазин</p>
          <ul>
            <li><Link href="/#collections">Коллекции</Link></li>
            <li><Link href="/order/photo">Вышивка по фото</Link></li>
            <li><Link href="/orders">Мой заказ</Link></li>
            <li><Link href="/admin">Вход для команды</Link></li>
          </ul>
        </nav>
        <nav className="footer-documents" aria-labelledby="footer-help">
          <p id="footer-help" className="eyebrow">Помощь</p>
          <ul>
            <li><Link href="/offer#returns">Доставка и возврат</Link></li>
            <li><Link href="/offer">Публичная оферта</Link></li>
            <li><Link href="/privacy">Политика обработки персональных данных</Link></li>
          </ul>
        </nav>
      </div>
    </footer>
    {menu && <Modal title="Меню KNITSEW" onClose={() => setMenu(false)}>
      <h2 className="wordmark">KNITSEW</h2>
      <nav className="mobile-links" aria-label="Основное меню">
        <Link onClick={() => setMenu(false)} href="/">Главная</Link>
        <Link onClick={() => setMenu(false)} href="/#collections">Коллекции</Link>
        <Link onClick={() => setMenu(false)} href="/order/photo">Вышивка по фото</Link>
        <Link onClick={() => setMenu(false)} href="/#gallery">Примеры работ</Link>
        <Link onClick={() => setMenu(false)} href="/orders">Мой заказ</Link>
      </nav>
    </Modal>}
  </>;
}
