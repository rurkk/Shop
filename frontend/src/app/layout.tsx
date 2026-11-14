import type { Metadata } from "next";
import { SiteShell } from "@/components/site-shell";
import "./globals.css";

export const metadata: Metadata = {
  title: { default: "KNITSEW — вышивка на одежде", template: "%s · KNITSEW" },
  description: "Вышивка по вашему фото и готовые дизайны на худи, футболках и свитшотах.",
};

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  const gotham = process.env.SHOP_GOTHAM_PRO === "1";
  return <html lang="ru" className={gotham ? "shop-gotham" : undefined}>
    <head>{gotham && <link rel="stylesheet" href="/fonts/gotham.css" />}</head>
    <body><SiteShell>{children}</SiteShell></body>
  </html>;
}
