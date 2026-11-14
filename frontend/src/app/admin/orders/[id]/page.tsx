import { notFound } from "next/navigation";
import { AdminOrder } from "@/components/admin-order";
import { isUuid } from "@/lib/format";

export default async function Page({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  if (!isUuid(id)) notFound();
  return <AdminOrder key={id} id={id} />;
}
