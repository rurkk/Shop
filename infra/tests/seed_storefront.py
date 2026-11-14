#!/usr/bin/env python3
"""Publish visual fixtures through the staff API on a fresh local Shop stack."""
import os
from pathlib import Path
from urllib.parse import urlparse

from smoke import MAIL, SHOP, Browser, log_in_staff


FIXTURES = Path(__file__).parent / "fixtures"
STAFF = "fixtures@example.test"

PRODUCTS = [
    ("volya", "Худи «Воля»", "Конь в движении", "Худи", "Чёрный", "horse", 690000),
    ("severnaya-zvezda", "Футболка «Северная звезда»", "Розетка", "Футболка", "Молочный", "rosette", 340000),
    ("tihiy-les", "Свитшот «Тихий лес»", "Северный лес", "Свитшот", "Серый", "forest", 540000),
    ("zhar-ptitsa", "Худи «Жар-птица»", "Народный орнамент", "Худи", "Бордовый", "bird", 720000),
]


def main():
    if any(urlparse(url).hostname not in {"127.0.0.1", "localhost", "::1"} for url in (SHOP, MAIL)):
        raise SystemExit("Fixtures can only be installed on a loopback Shop and Mailpit.")
    staff = Browser()
    log_in_staff(staff, os.environ.get("SHOP_DEMO_STAFF_EMAIL", STAFF))
    storefront = staff.call("/api/staff/storefront")
    configured_fields = (
        "published", "heroImage", "heroTitle", "gallery", "photoOrderPriceHint", "photoOrderTimingHint", "deliveryPriceHint",
    )
    if any(storefront[field] for field in configured_fields):
        raise SystemExit("The storefront is already configured. Use a fresh local test stack.")

    catalogs = {
        item["slug"]: item["id"] for item in staff.call("/api/staff/catalogs")["items"]
        if item["slug"] in {"team-choice", "rus"}
    }
    if catalogs.keys() != {"team-choice", "rus"}:
        raise SystemExit("The seeded team-choice and rus catalogs are required.")
    for catalog in catalogs.values():
        if staff.call(f"/api/staff/catalogs/{catalog}")["products"]["items"]:
            raise SystemExit("The demo catalogs already contain products. Use a fresh local test stack.")

    def upload(name):
        filename = name + ".jpg"
        return staff.upload_image(
            "/api/staff/catalog-images", (FIXTURES / filename).read_bytes(),
            filename=filename, media_type="image/jpeg",
        )["id"]

    image_ids = {name: upload(name) for name in (
        "hero", "coupe-source", "coupe-detail", "horse", "horse-detail", "rosette", "rosette-detail",
        "forest", "forest-detail", "bird", "bird-detail",
    )}
    for index, (slug, title, design, garment, color, photo, price) in enumerate(PRODUCTS):
        form = {
            "slug": slug, "name": title, "design": design, "garmentType": garment,
            "description": "Демонстрационный дизайн для проверки локальной витрины.",
            "images": [{"id": image_ids[photo]}, {"id": image_ids[photo + "-detail"], "caption": "Вышивка крупным планом"}],
            "measurements": [],
        }
        product = staff.call("/api/staff/products", "POST", form, expected=201)
        route = f"/api/staff/products/{product['id']}"
        for size, availability, days in (
            ("M", "IN_STOCK", None), ("L", "MADE_TO_ORDER", 7), ("XL", "OUT_OF_STOCK", None),
        ):
            staff.call(route + "/variants", "POST", {
                "sku": f"DEMO-{index + 1}-{size}", "size": size, "color": color,
                "priceKopecks": price, "availability": availability, "productionDays": days,
            }, expected=201)
        measurements = [
            {"size": "M", "widthCm": 57.5, "lengthCm": 68, "sleeveLengthCm": 62},
            {"size": "L", "widthCm": 60, "lengthCm": 71, "sleeveLengthCm": 64},
            {"size": "XL", "widthCm": 63, "lengthCm": 74, "sleeveLengthCm": 65},
        ] if slug == "volya" else []
        staff.call(route, "PUT", dict(form, published=True, measurements=measurements))
        for catalog in catalogs.values():
            staff.call(f"/api/staff/catalogs/{catalog}/products/{product['id']}", "PUT",
                       {"displayOrder": index}, expected=204)

    staff.call("/api/staff/storefront", "PUT", {
        "version": storefront["version"], "published": True,
        "heroTitle": "Ваши истории, вышитые нитками",
        "heroImageId": image_ids["hero"],
        "photoOrderPriceHint": "Демо: от 4 100 ₽ за изделие с вышивкой",
        "photoOrderTimingHint": "Демо: изготовление от 7 дней",
        "deliveryPriceHint": "Демо: до ПВЗ в Москве от 350 ₽, 4 дня после отправки",
        "gallery": [
            {"embroideryImageId": image_ids["coupe-detail"], "sourceImageId": image_ids["coupe-source"],
             "caption": "Ретро-купе · пример по фото"},
            {"embroideryImageId": image_ids["horse-detail"], "caption": "Конь в движении"},
        ],
    })
    print("Published the local demo storefront with four products and two gallery examples.")


if __name__ == "__main__":
    main()
