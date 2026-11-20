export type Page<T> = { items: T[]; page: number; size: number; hasMore: boolean };
export type ImageRef = { id: string; url: string };
export type Storefront = {
  version: number;
  published: boolean;
  heroTitle: string | null;
  heroImage: ImageRef | null;
  photoOrderPriceHint: string | null;
  photoOrderTimingHint: string | null;
  deliveryPriceHint: string | null;
  gallery: { embroidery: ImageRef; source: ImageRef | null; caption: string | null }[];
};
export type CatalogKind = "COLLECTION" | "SELECTION";
export type Catalog = {
  id: string; slug: string; name: string; description: string; kind: CatalogKind;
  cover: ImageRef | null; displayOrder: number; published: boolean;
};
export type Availability = "IN_STOCK" | "MADE_TO_ORDER" | "OUT_OF_STOCK";
export type Variant = {
  id: string; sku: string; size: string; color: string; priceKopecks: number;
  availability: Availability; productionDays: number | null;
};
export type ProductSummary = {
  id: string; slug: string; name: string; garmentType: string; published: boolean;
  cover: ImageRef | null; fromPriceKopecks: number | null; displayOrder: number | null;
};
export type ProductMeasurements = {
  size: string; widthCm: number; lengthCm: number; sleeveLengthCm: number | null;
};
export type Product = {
  id: string; slug: string; name: string; design: string; garmentType: string; description: string;
  published: boolean; images: (ImageRef & { caption: string | null })[]; variants: Variant[];
  measurements: ProductMeasurements[];
};
export type CatalogContents = { catalog: Catalog; products: Page<ProductSummary> };
export type EmbroideryFrame = "SMALL" | "STANDARD" | "LARGE";
export type EmbroideryConfiguration = {
  frame: EmbroideryFrame; threePhotoBackground: boolean; licensePlate: boolean;
};
export type PhotoOrderOption = {
  garment: "HOODIE" | "T_SHIRT" | "SWEATSHIRT"; frame: EmbroideryFrame; sizeMm: number;
  threePhotoBackground: boolean; priceKopecks: number | null;
};
export type OrderType = "CUSTOM_PHOTO" | "CATALOG";
export type OrderStatus =
  | "DRAFT" | "EXPIRED" | "IN_REVIEW" | "AWAITING_DELIVERY_QUOTE" | "AWAITING_PAYMENT"
  | "PREPARING_SKETCH" | "SKETCH_REVIEW" | "CHANGES_REQUESTED" | "IN_PRODUCTION"
  | "PREPARING_ORDER" | "READY_TO_SHIP" | "SHIPPED" | "REJECTED"
  | "CANCELLATION_REQUESTED" | "CANCELLED";
export type Order = {
  id: string; type: OrderType; status: OrderStatus; email: string; garment: string; size: string;
  color: string; description: string | null; itemKopecks: number | null; productionDays: number | null;
  embroidery: EmbroideryConfiguration | null;
  trackingNumber: string | null; rejectionReason: string | null; cancellationReason: string | null;
  createdAt: string;
};
export type DeliveryContact = {
  recipientName: string; phone: string; city: string;
  pickupPointCode: string | null; pickupPointAddress: string | null;
};
export type Delivery = DeliveryContact & {
  method: "CDEK_PICKUP"; version: number; priceKopecks: number | null; deliveryDays: number | null;
  offerValidThrough: string | null; deliverBy: string | null;
};
export type ProviderStatus = "CREATING" | "PENDING" | "SUCCEEDED" | "CANCELED";
export type Payment = {
  id: string; amountKopecks: number; paidAt: string | null;
  checkoutStatus: ProviderStatus | null; checkoutUrl: string | null;
  refund: { id: string; status: ProviderStatus; updatedAt: string } | null;
};
export type OrderDetails = {
  order: Order;
  catalogItem: { catalogSlug: string; productSlug: string; productName: string; variantId: string; sku: string; availability: Availability } | null;
  delivery: Delivery | null; sourceFileIds: string[];
  sketch: { id: string; fileId: string; version: number; approvedAt: string | null } | null;
  payment: Payment | null; totalKopecks: number | null;
  offerVersion: string;
  acceptance: { offerVersion: string; acceptedAt: string } | null;
};
export type Message = {
  id: string; sequence: number; clientMessageId: string; senderRole: "STAFF" | "CUSTOMER";
  body: string; attachmentId: string | null; createdAt: string;
};
export type MessageHistory = { messages: Message[]; lastSequence: number; hasMore: boolean };
export type OrderEvent = {
  id: number; action: "QUOTED" | "DELIVERY_QUOTED" | "REJECTED" | "SHIPPED" | "CANCELLATION_REQUESTED"
    | "CANCELLED" | "PAYMENT_CONFIRMED" | "REFUND_REQUESTED" | "REFUND_SUCCEEDED" | "REFUND_CANCELED";
  actorEmail: string | null; details: Record<string, string | number>; createdAt: string;
};
