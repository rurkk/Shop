import { notFound } from "next/navigation";
import { CustomerOrder } from "@/components/customer-order";
import { isUuid } from "@/lib/format";

export const metadata = { title: "Мой заказ", robots: { index: false, follow: false } };

export default async function Page({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  if (!isUuid(id)) notFound();
  return <CustomerOrder key={id} id={id} />;
}
