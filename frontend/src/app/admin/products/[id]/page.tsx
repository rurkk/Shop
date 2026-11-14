import { notFound } from "next/navigation";
import { AdminProduct } from "@/components/admin-product";
import { isUuid } from "@/lib/format";

export default async function Page({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  if (!isUuid(id)) notFound();
  return <AdminProduct key={id} id={id} />;
}
