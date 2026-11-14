import "server-only";

import { connection } from "next/server";
import type { Storefront } from "./contracts";

export type StorefrontResult =
  | { state: "published"; storefront: Storefront }
  | { state: "unpublished" }
  | { state: "unavailable" };

export async function getStorefront(): Promise<StorefrontResult> {
  await connection();
  const origin = process.env.SHOP_BACKEND_ORIGIN;
  if (!origin) {
    console.error("Set SHOP_BACKEND_ORIGIN to render the storefront.");
    return { state: "unavailable" };
  }

  try {
    const response = await fetch(new URL("/api/storefront", origin), {
      headers: { Accept: "application/json" },
      cache: "no-store",
      redirect: "error",
      signal: AbortSignal.timeout(3000),
    });
    if (response.status === 404) return { state: "unpublished" };
    if (!response.ok) throw new Error(`Storefront API returned ${response.status}`);

    const storefront: Storefront = await response.json();
    if (!storefront.published || !storefront.heroTitle || !storefront.heroImage) {
      throw new Error("Published storefront requires a title and banner image");
    }
    return { state: "published", storefront };
  } catch (error) {
    console.error("Unable to load the storefront.", error instanceof Error ? error.message : error);
    return { state: "unavailable" };
  }
}
