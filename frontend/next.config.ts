import { existsSync } from "node:fs";
import type { NextConfig } from "next";

if (process.env.SHOP_GOTHAM_PRO === "1") {
  for (const weight of ["regular", "bold"]) {
    const file = `public/fonts/gotham-pro-${weight}.woff2`;
    if (!existsSync(file)) throw new Error(`SHOP_GOTHAM_PRO=1 requires ${file}`);
  }
}

const config: NextConfig = {
  output: "standalone",
  poweredByHeader: false,
  devIndicators: false,
  async rewrites() {
    const origin = process.env.SHOP_BACKEND_ORIGIN;
    return process.env.NODE_ENV === "development" && origin
      ? [{ source: "/api/:path*", destination: new URL("/api/:path*", origin).href }]
      : [];
  },
  async headers() {
    return [{
      source: "/:path*",
      headers: [
        { key: "Content-Security-Policy", value: "frame-ancestors 'none'" },
        { key: "X-Frame-Options", value: "DENY" },
        { key: "Referrer-Policy", value: "same-origin" },
      ],
    }];
  },
};

export default config;
