import { notFound } from "next/navigation";
import { Store } from "@/components/store";
import { slugPattern } from "@/lib/format";
import { getStorefront } from "@/lib/storefront-server";

export default async function Page({ params }: { params: Promise<{ slug: string }> }) {
  const { slug } = await params;
  if (slug.length > 80 || !new RegExp(`^${slugPattern}$`).test(slug)) notFound();
  return <Store initialSlug={slug} content={await getStorefront()} />;
}
