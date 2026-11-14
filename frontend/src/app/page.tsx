import { Store } from "@/components/store";
import { getStorefront } from "@/lib/storefront-server";

export default async function Page() {
  return <Store initialSlug={null} content={await getStorefront()} />;
}
