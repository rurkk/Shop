import { notFound } from "next/navigation";
import { AdminCatalog } from "@/components/admin-catalog";
import { isUuid } from "@/lib/format";

export default async function Page({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  if (!isUuid(id)) notFound();
  return <AdminCatalog key={id} id={id} />;
}
