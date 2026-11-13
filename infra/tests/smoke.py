#!/usr/bin/env python3
"""Exercise the local stack through Nginx using the HTTP API."""

import http.cookiejar
import json
import os
import re
import struct
import time
import urllib.error
import urllib.request
import uuid
import zlib


SHOP = os.environ.get("SHOP_TEST_URL", "http://127.0.0.1:18080").rstrip("/")
MAIL = os.environ.get("SHOP_TEST_MAIL_URL", "http://127.0.0.1:18025").rstrip("/")
STAFF = os.environ.get("SHOP_TEST_STAFF_EMAIL", "staff@example.test")


def open_url(opener, url, method="GET", data=None, headers=None, expected=200):
    request = urllib.request.Request(url, data=data, headers=headers or {}, method=method)
    try:
        response = opener.open(request, timeout=12)
    except urllib.error.HTTPError as error:
        if error.code == expected:
            return error
        with error:
            detail = error.read(800).decode(errors="replace")
        raise AssertionError(f"{method} {url}: expected {expected}, got {error.code}: {detail}") from error
    if response.status != expected:
        with response:
            raise AssertionError(f"{method} {url}: expected {expected}, got {response.status}")
    return response


class Browser:
    def __init__(self):
        self.cookies = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.cookies))
        self.csrf = {}

    def call(self, route, method="GET", value=None, expected=200, data=None, headers=None, csrf=True):
        request_headers = dict(headers or {})
        if csrf and method != "GET":
            request_headers.update(self.csrf)
        if value is not None:
            request_headers["Content-Type"] = "application/json"
            data = json.dumps(value).encode()
        with open_url(self.opener, SHOP + route, method, data, request_headers, expected) as response:
            content = response.read()
            return json.loads(content) if "json" in response.headers.get("Content-Type", "") else content

    def renew_csrf(self):
        result = self.call("/api/csrf")
        self.csrf = {result["headerName"]: result["token"]}

    def session_id(self):
        return next(cookie.value for cookie in self.cookies if cookie.name == "JSESSIONID")

    def upload(self, order, purpose, image, expected=201):
        return self.upload_image(f"{order}/files?purpose={purpose}", image, expected)

    def upload_image(self, route, image, expected=201):
        boundary = "smoke-" + uuid.uuid4().hex
        body = (
            f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="photo.png"\r\n'
            "Content-Type: image/png\r\n\r\n"
        ).encode() + image + f"\r\n--{boundary}--\r\n".encode()
        return self.call(
            route, "POST", expected=expected, data=body,
            headers={"Content-Type": "multipart/form-data; boundary=" + boundary},
        )

    def stream(self, order, after=0, last_event_id=None):
        headers = {"Accept": "text/event-stream"}
        if last_event_id is not None:
            headers["Last-Event-ID"] = str(last_event_id)
        response = open_url(self.opener, SHOP + f"{order}/messages/stream?after={after}", headers=headers)
        assert response.headers.get_content_type() == "text/event-stream"
        return response


def mail_items(email, subject):
    with urllib.request.urlopen(MAIL + "/api/v1/messages?limit=100", timeout=5) as response:
        messages = json.load(response)["messages"] or []
    return [
        message["ID"] for message in messages
        if message["Subject"] == subject and any(address["Address"] == email for address in message["To"])
    ]


def wait_for_mail(email, subject, seen=()):
    deadline = time.monotonic() + 25
    while time.monotonic() < deadline:
        ids = [message_id for message_id in mail_items(email, subject) if message_id not in seen]
        if ids:
            with urllib.request.urlopen(MAIL + "/api/v1/message/" + ids[0], timeout=5) as response:
                return json.load(response)["Text"]
        time.sleep(0.25)
    raise AssertionError(f"Mailpit did not receive {subject!r} for {email}")


def log_in(browser, route, email, verify_wrong_code=False):
    browser.renew_csrf()
    previous_session = browser.session_id()
    seen = mail_items(email, "Код доступа")
    browser.call(route + "/code", "POST", {"email": email}, expected=202)
    code = re.search(r"\b[0-9]{6}\b", wait_for_mail(email, "Код доступа", seen)).group()
    if verify_wrong_code:
        wrong_code = "000000" if code != "000000" else "000001"
        browser.call(route + "/verify", "POST", {"email": email, "code": wrong_code}, expected=401)
    browser.call(route + "/verify", "POST", {"email": email, "code": code}, expected=204)
    assert browser.session_id() != previous_session, "Login must rotate the session ID"
    browser.renew_csrf()
    if verify_wrong_code:
        unauthenticated = Browser()
        unauthenticated.renew_csrf()
        unauthenticated.call(route + "/verify", "POST", {"email": email, "code": code}, expected=401)


def png():
    def chunk(name, data):
        return struct.pack(">I", len(data)) + name + data + struct.pack(">I", zlib.crc32(name + data))

    return (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", struct.pack(">IIBBBBB", 1, 1, 8, 2, 0, 0, 0))
        + chunk(b"IDAT", zlib.compress(b"\x00\xff\x00\x00"))
        + chunk(b"IEND", b"")
    )


def read_event(stream, name):
    event = {}
    while True:
        line = stream.readline()
        if not line:
            raise AssertionError(f"SSE ended before {name}")
        line = line.decode("utf-8").rstrip("\r\n")
        if not line:
            if event.get("event") == name:
                return event
            event.clear()
        elif not line.startswith(":"):
            field, _, value = line.partition(":")
            event[field] = value.lstrip(" ")


def new_order(browser, email):
    browser.renew_csrf()
    form = {"email": email, "garment": "HOODIE", "size": "M", "color": "черный"}
    return "/api/orders/" + browser.call("/api/orders/drafts", "POST", form, expected=201)["id"]


def status(snapshot, expected):
    assert snapshot["order"]["status"] == expected, snapshot["order"]["status"]


def pickup_contact():
    return {"recipientName": "Тестовый покупатель", "phone": "+79990000000",
            "city": "Москва", "pickupPointCode": "TEST-MSK"}


def delivery_quote(delivery, price):
    return {"deliveryVersion": delivery["version"], "pickupPointCode": "TEST-MSK",
            "pickupPointAddress": "Тестовая улица, 1", "priceKopecks": price, "deliveryDays": 4}


def check_catalog_checkout(staff, other_buyer, run, slug, variant_route, variant):
    buyer = Browser()
    buyer.renew_csrf()
    email = f"catalog-{run}@example.test"
    route = "/api/orders/catalog-drafts"
    form = {"email": email, "catalogSlug": slug, "variantId": variant["id"], "delivery": pickup_contact()}
    contact = dict(form["delivery"], pickupPointCode=None)
    buyer.call(route, "POST", dict(form, delivery=contact), expected=400)
    editable_variant = {key: value for key, value in variant.items() if key != "id"}
    staff.call(variant_route, "PUT", dict(editable_variant, availability="OUT_OF_STOCK"))
    buyer.call(route, "POST", form, expected=409)
    staff.call(variant_route, "PUT", editable_variant)

    order_id = buyer.call(route, "POST", form, expected=201)["id"]
    order = f"/api/orders/{order_id}"
    staff_order = f"/api/staff/orders/{order_id}"
    buyer.call(order, expected=401)
    other_buyer.call(order, expected=404)
    other_buyer.call(order + "/delivery", "PUT", pickup_contact(), expected=404)
    log_in(buyer, order + "/access", email)
    buyer.upload(order, "SOURCE", png(), expected=409)
    assert staff.call(f"/api/staff/orders?search={order_id}")["items"] == []
    staff.call(variant_route, "PUT", dict(editable_variant, priceKopecks=499000, sku=variant["sku"] + "-NEW"))
    submitted = buyer.call(order + "/submit", "POST")
    status(submitted, "IN_REVIEW")
    assert submitted["order"]["type"] == "CATALOG"
    assert submitted["order"]["garment"] == "Худи" and submitted["order"]["size"] == variant["size"]
    assert submitted["order"]["itemKopecks"] == variant["priceKopecks"] and submitted["totalKopecks"] is None
    assert submitted["catalogItem"]["sku"] == variant["sku"] and submitted["catalogItem"]["catalogSlug"] == slug
    assert submitted["payments"] == [] and submitted["sourceFileIds"] == [] and submitted["sketch"] is None
    assert staff.call(staff_order) == submitted

    page = staff.call("/api/staff/orders?size=1")
    assert page["items"][0]["id"] == order_id and page["hasMore"]
    assert staff.call("/api/staff/orders?size=1&page=1")["items"][0]["type"] == "CUSTOM_PHOTO"
    page = staff.call(f"/api/staff/orders?type=CATALOG&status=IN_REVIEW&search={email.upper()}")
    assert [item["id"] for item in page["items"]] == [order_id]
    assert staff.call(f"/api/staff/orders?type=CUSTOM_PHOTO&search={order_id}")["items"] == []
    staff.call("/api/staff/orders?size=101", expected=400)
    staff.call(staff_order + "/quote", "POST", {"itemKopecks": 10, "productionDays": 1, "estimatedDeliveryDays": 3},
               expected=409)
    staff.call(staff_order + "/production-complete", "POST", expected=409)
    buyer.call(order + "/messages", "POST", {"clientMessageId": str(uuid.uuid4()), "body": "Уточнение по доставке"})
    assert len(staff.call(order + "/messages")["messages"]) == 1

    stale = delivery_quote(submitted["delivery"], 30000)
    address = dict(contact, pickupPointAddress="Тестовая улица, 1")
    changed = buyer.call(order + "/delivery", "PUT", address)
    assert buyer.call(order + "/delivery", "PUT", address) == changed
    quote_route = staff_order + "/delivery-quote"
    staff.call(quote_route, "POST", stale, expected=409)
    quote = delivery_quote(changed["delivery"], 30000)
    staff.call(quote_route, "POST", {key: value for key, value in quote.items() if key != "priceKopecks"}, expected=400)
    priced = staff.call(quote_route, "POST", quote)
    status(priced, "AWAITING_PAYMENT")
    assert priced["delivery"]["method"] == "CDEK_PICKUP" and priced["delivery"]["deliveryDays"] == 4
    assert priced["totalKopecks"] == variant["priceKopecks"] + 30000
    payment, = priced["payments"]
    assert payment["installment"] == "FULL" and payment["amountKopecks"] == priced["totalKopecks"]
    assert buyer.call(order) == priced
    buyer.call(order + "/delivery", "PUT", pickup_contact(), expected=409)
    staff.call(quote_route, "POST", quote, expected=409)
    staff.call(staff_order + "/production-complete", "POST", expected=409)
    ship = {"trackingNumber": "TEST-CATALOG"}
    staff.call(staff_order + "/shipment", "POST", ship, expected=409)
    pay_route = f"{staff_order}/test-payments/{payment['id']}/confirm"
    status(staff.call(pay_route, "POST"), "PREPARING_ORDER")
    status(staff.call(pay_route, "POST"), "PREPARING_ORDER")
    staff.call(staff_order + "/shipment", "POST", ship, expected=409)
    status(staff.call(staff_order + "/production-complete", "POST"), "READY_TO_SHIP")
    status(staff.call(staff_order + "/shipment", "POST", ship), "SHIPPED")
    assert buyer.call(order)["order"]["trackingNumber"] == ship["trackingNumber"]
    assert order_id in wait_for_mail(email, "Заказ готов к оплате")

    staff.call(variant_route, "PUT", editable_variant)
    return form


def check_catalogs(staff, buyer, run):
    visitor = Browser()
    catalogs = "/api/catalogs"
    staff_catalogs = "/api/staff/catalogs"
    staff_products = "/api/staff/products"
    staff_images = "/api/staff/catalog-images"

    entries = visitor.call(catalogs)["items"]
    assert entries[0]["slug"] == "team-choice" and entries[0]["kind"] == "SELECTION"
    rus = visitor.call(catalogs + "/rus")
    assert rus["catalog"]["name"] == "Русь" and rus["products"]["items"] == []
    visitor.call(catalogs + "?size=101", expected=400)
    visitor.call(staff_catalogs, expected=401)
    buyer.call(staff_catalogs, expected=403)
    staff.call(staff_catalogs, "POST", {}, expected=403, csrf=False)

    visitor_image = png()
    staff.upload_image(staff_images, b"not an image", expected=415)
    upload = staff.upload_image(staff_images, visitor_image)
    image_id = upload["id"]
    public_image = f"/api/catalog-images/{image_id}"
    assert staff.call(upload["url"]) == visitor_image
    visitor.call(public_image, expected=404)
    buyer.call(upload["url"], expected=403)

    slug = f"test-catalog-{run}"
    form = {"slug": slug, "name": "Тестовая витрина", "kind": "COLLECTION", "displayOrder": 20}
    draft_catalog = staff.call(staff_catalogs, "POST", form, expected=201)
    staff.call(staff_catalogs, "POST", form, expected=409)
    public_catalog = f"{catalogs}/{slug}"
    catalog = f"{staff_catalogs}/{draft_catalog['id']}"
    visitor.call(public_catalog, expected=404)
    assert any(item["id"] == draft_catalog["id"] for item in staff.call(staff_catalogs)["items"])
    assert staff.call(catalog)["products"]["items"] == []

    product_slug = f"test-hoodie-{run}"
    product = {
        "slug": product_slug, "name": "Тестовое худи", "design": "Тестовый дизайн",
        "garmentType": "Худи", "imageIds": [image_id],
    }
    draft = staff.call(staff_products, "POST", product, expected=201)
    product_id = draft["id"]
    product_route = f"{staff_products}/{product_id}"
    public_product = f"{public_catalog}/products/{product_slug}"
    published_product = dict(product, published=True)
    staff.call(product_route, "PUT", published_product, expected=409)
    staff.call(f"{catalog}/products/{product_id}", "PUT", {"displayOrder": 5}, expected=204)
    staff.call(catalog, "PUT", dict(form, published=True))
    assert visitor.call(public_catalog)["products"]["items"] == []
    visitor.call(public_product, expected=404)
    visitor.call(public_image, expected=404)

    variant = {
        "sku": f"TEST-{run.upper()}-M", "size": "M", "color": "Чёрный",
        "priceKopecks": 299000, "availability": "MADE_TO_ORDER", "productionDays": 7,
    }
    variants_route = product_route + "/variants"
    staff.call(variants_route, "POST", dict(variant, productionDays=None), expected=400)
    staff.call(variants_route, "POST", dict(variant, priceKopecks=299000.5), expected=400)
    made = staff.call(variants_route, "POST", variant, expected=201)
    staff.call(variants_route, "POST", dict(variant, size="L"), expected=409)
    staff.call(variants_route, "POST", dict(variant, sku=variant["sku"] + "-B", size="m"), expected=409)
    variant_route = f"{variants_route}/{made['id']}"
    stock = staff.call(variant_route, "PUT", dict(variant, availability="IN_STOCK", productionDays=None))
    assert stock["id"] == made["id"] and stock["availability"] == "IN_STOCK"
    staff.call(product_route, "PUT", published_product)
    staff.call(variant_route, "DELETE", expected=409)

    team_catalog = entries[0]["id"]
    team_placement = f"{staff_catalogs}/{team_catalog}/products/{product_id}"
    staff.call(team_placement, "PUT", {"displayOrder": 40}, expected=204)
    try:
        assert visitor.call(public_product)["variants"] == [stock]
        row = visitor.call(public_catalog)["products"]["items"][0]
        assert row["fromPriceKopecks"] == stock["priceKopecks"] and row["displayOrder"] == 5
        assert row["cover"]["url"] == public_image
        with open_url(visitor.opener, SHOP + public_image) as response:
            assert response.read() == visitor_image
            assert "no-cache" in response.headers["Cache-Control"]
            cache_headers = {"If-None-Match": response.headers["ETag"]}
        visitor.call(public_image, headers=cache_headers, expected=304)
        team = visitor.call(catalogs + "/team-choice")["products"]["items"]
        assert any(item["id"] == product_id and item["displayOrder"] == 40 for item in team)
        second_page = visitor.call(catalogs + "?size=1&page=1")
        assert second_page["items"][0]["slug"] == "rus" and second_page["hasMore"]
        staff.call(upload["url"], "DELETE", expected=409)

        checkout = check_catalog_checkout(staff, buyer, run, slug, variant_route, stock)
        staff.call(catalog, "PUT", form)
        visitor.call(public_product, expected=404)
        visitor.renew_csrf()
        visitor.call("/api/orders/catalog-drafts", "POST", checkout, expected=404)
        assert visitor.call(f"{catalogs}/team-choice/products/{product_slug}")["id"] == product_id
    finally:
        staff.call(team_placement, "DELETE", expected=204)
        staff.call(catalog, "PUT", form)

    visitor.call(public_image, headers=cache_headers, expected=404)
    staff.call(product_route, "PUT", dict(product, imageIds=[]))
    staff.call(upload["url"], "DELETE", expected=204)
    staff.call(upload["url"], expected=404)
    print("Verified catalog management, checkout, shared order queue, single payment, and shipment", flush=True)


def main():
    buyer, staff, other = Browser(), Browser(), Browser()
    run = uuid.uuid4().hex[:12]
    buyer_email = f"buyer-{run}@example.test"
    other_email = f"other-{run}@example.test"
    image = png()

    buyer.call("/actuator/health/readiness", expected=404)
    buyer.call("/api/orders/drafts", "POST", {}, expected=403, csrf=False)
    order = new_order(buyer, buyer_email)
    staff_order = order.replace("/api/orders/", "/api/staff/orders/", 1)
    buyer.call(order, expected=401)
    log_in(buyer, order + "/access", buyer_email, verify_wrong_code=True)
    other_order = new_order(other, other_email)
    log_in(other, other_order + "/access", other_email)
    other.call(order, expected=404)
    buyer.call("/api/staff/orders", expected=403)
    buyer.call(order + "/submit", "POST", expected=409)
    buyer.upload(order, "SOURCE", b"this file contains plain text", expected=415)
    source = buyer.upload(order, "SOURCE", image)
    assert buyer.call(order + "/files/" + source["id"]) == image
    other.call(order + "/files/" + source["id"], expected=404)
    status(buyer.call(order + "/submit", "POST"), "IN_REVIEW")
    log_in(staff, "/api/staff/access", STAFF)
    assert any(item["id"] == order.rsplit("/", 1)[-1] for item in staff.call("/api/staff/orders")["items"])
    check_catalogs(staff, buyer, run)
    other.upload(other_order, "SOURCE", image)
    status(other.call(other_order + "/submit", "POST"), "IN_REVIEW")
    rejected = staff.call(other_order.replace("/api/orders/", "/api/staff/orders/") + "/reject",
                          "POST", {"reason": "Тестовый отказ"})
    status(rejected, "REJECTED")
    assert other.call(other_order)["order"]["rejectionReason"] == "Тестовый отказ"

    print("Verified order access, CSRF, and private S3 upload/download", flush=True)
    quote = staff.call(
        staff_order + "/quote", "POST", {"itemKopecks": 175001, "productionDays": 7, "estimatedDeliveryDays": 5},
    )
    status(quote, "AWAITING_DEPOSIT")
    assert quote["order"]["estimatedDeliveryDays"] == 5 and quote["totalKopecks"] is None
    deposit = quote["payments"][0]
    assert deposit["amountKopecks"] == 87501
    deposit_route = f"{staff_order}/test-payments/{deposit['id']}/confirm"
    buyer.call(deposit_route, "POST", expected=403)
    status(staff.call(deposit_route, "POST"), "PREPARING_SKETCH")
    status(staff.call(deposit_route, "POST"), "PREPARING_SKETCH")

    attachment = staff.upload(order, "CHAT", image)
    attachment_path = f"{order}/files/{attachment['id']}"
    buyer.call(attachment_path, expected=404)
    message = {"clientMessageId": str(uuid.uuid4()), "body": "Здравствуйте", "attachmentId": attachment["id"]}
    with buyer.stream(order) as stream:
        sent = staff.call(order + "/messages", "POST", message)
        assert staff.call(order + "/messages", "POST", message) == sent
        delivered = read_event(stream, "message.created")
        assert int(delivered["id"]) == sent["sequence"]
        assert json.loads(delivered["data"]) == sent
    assert buyer.call(attachment_path) == image
    reply = buyer.call(
        order + "/messages", "POST", {"clientMessageId": str(uuid.uuid4()), "body": "Спасибо"},
    )
    with buyer.stream(order, last_event_id=sent["sequence"]) as stream:
        assert json.loads(read_event(stream, "message.created")["data"]) == reply
    history = buyer.call(order + "/messages")
    assert history["messages"] == [sent, reply]
    assert buyer.call(f"{order}/messages?before={reply['sequence']}")["messages"] == [sent]
    assert history["lastSequence"] == reply["sequence"]
    assert "Откройте карточку заказа" in wait_for_mail(buyer_email, "Новое сообщение по заказу")
    print("Verified live SSE, replay, idempotent messages, and order email", flush=True)

    sketch_file = staff.upload(order, "SKETCH", image)["id"]
    buyer.call(order + "/files/" + sketch_file, expected=404)
    snapshot = staff.call(staff_order + "/sketches", "POST", {"fileId": sketch_file})
    status(snapshot, "SKETCH_REVIEW")
    assert buyer.call(order + "/files/" + sketch_file) == image
    old_sketch = f"{order}/sketches/{snapshot['sketch']['id']}"
    status(buyer.call(old_sketch + "/request-changes", "POST"), "CHANGES_REQUESTED")
    new_file = staff.upload(order, "SKETCH", image)["id"]
    revised = staff.call(staff_order + "/sketches", "POST", {"fileId": new_file})["sketch"]
    assert revised["version"] == 2
    buyer.call(old_sketch + "/approve", "POST", expected=409)
    status(buyer.call(f"{order}/sketches/{revised['id']}/approve", "POST"), "IN_PRODUCTION")
    delivery_route = staff_order + "/delivery-quote"
    staff.call(delivery_route, "POST", delivery_quote({"version": 1}, 30000), expected=409)
    finished = staff.call(staff_order + "/production-complete", "POST")
    status(finished, "AWAITING_DELIVERY_QUOTE")
    assert len(finished["payments"]) == 1
    staff.call(delivery_route, "POST", delivery_quote({"version": 1}, 30000), expected=409)
    with_contact = buyer.call(order + "/delivery", "PUT", pickup_contact())
    priced = staff.call(delivery_route, "POST", delivery_quote(with_contact["delivery"], 30000))
    status(priced, "AWAITING_BALANCE")
    assert priced["totalKopecks"] == 205001
    balance = priced["payments"][1]
    assert balance["installment"] == "BALANCE" and balance["amountKopecks"] == 117500
    staff.call(staff_order + "/reject", "POST", {"reason": "Тест"}, expected=409)
    staff.call(staff_order + "/shipment", "POST", {"trackingNumber": "TEST123"}, expected=409)
    status(staff.call(f"{staff_order}/test-payments/{balance['id']}/confirm", "POST"), "READY_TO_SHIP")
    status(staff.call(staff_order + "/shipment", "POST", {"trackingNumber": "TEST123"}), "SHIPPED")
    assert buyer.call(order)["order"]["trackingNumber"] == "TEST123"
    buyer.call("/api/session/logout", "POST", expected=204)
    buyer.call(order, expected=401)
    print("Verified sketch approval, two test installments with delivery, shipment, and logout")


if __name__ == "__main__":
    main()
