#!/usr/bin/env python3
"""Exercise the local stack through Nginx using the HTTP API."""

import base64
import hmac
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
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta
from pathlib import Path
from zoneinfo import ZoneInfo


SHOP = os.environ.get("SHOP_TEST_URL", "http://127.0.0.1:18080").rstrip("/")
MAIL = os.environ.get("SHOP_TEST_MAIL_URL", "http://127.0.0.1:18025").rstrip("/")
GATEWAY = os.environ.get("SHOP_TEST_GATEWAY_URL", "http://127.0.0.1:18081").rstrip("/")
STAFF = os.environ.get("SHOP_TEST_STAFF_EMAIL", "staff@example.test")
STAFF_ACCESS = "/api/staff/access"
OFFER_VERSION = "2026-11-14-draft"
OFFER_INPUT = {"offerVersion": OFFER_VERSION}


def open_url(opener, url, method="GET", data=None, headers=None, expected=200):
    allowed = expected if isinstance(expected, tuple) else (expected,)
    request = urllib.request.Request(url, data=data, headers=headers or {}, method=method)
    try:
        response = opener.open(request, timeout=12)
    except urllib.error.HTTPError as error:
        if error.code in allowed:
            return error
        with error:
            detail = error.read(800).decode(errors="replace")
        raise AssertionError(f"{method} {url}: expected {expected}, got {error.code}: {detail}") from error
    if response.status not in allowed:
        with response:
            raise AssertionError(f"{method} {url}: expected {expected}, got {response.status}")
    return response


class Browser:
    def __init__(self):
        self.cookies = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.cookies))
        self.csrf = {}

    def call(self, route, method="GET", value=None, expected=200, data=None, headers=None, csrf=True):
        with self.open(route, method, value, expected, data, headers, csrf) as response:
            content = response.read()
            return json.loads(content) if "json" in response.headers.get("Content-Type", "") else content

    def open(self, route, method="GET", value=None, expected=200, data=None, headers=None, csrf=True):
        request_headers = dict(headers or {})
        if csrf and method != "GET":
            request_headers.update(self.csrf)
        if value is not None:
            request_headers["Content-Type"] = "application/json"
            data = json.dumps(value).encode()
        return open_url(self.opener, SHOP + route, method, data, request_headers, expected)

    def renew_csrf(self):
        result = self.call("/api/csrf")
        self.csrf = {result["headerName"]: result["token"]}

    def session_id(self):
        return next(cookie.value for cookie in self.cookies if cookie.name == "JSESSIONID")

    def upload(self, order, purpose, image, expected=201):
        return self.upload_image(f"{order}/files?purpose={purpose}", image, expected)

    def upload_image(self, route, image, expected=201, filename="photo.png", media_type="image/png"):
        boundary = "smoke-" + uuid.uuid4().hex
        body = (
            f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{filename}"\r\n'
            f"Content-Type: {media_type}\r\n\r\n"
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


def request_code(browser, route, email):
    browser.renew_csrf()
    seen = mail_items(email, "Код доступа")
    browser.call(route + "/code", "POST", {"email": email}, expected=202)
    return re.search(r"\b[0-9]{6}\b", wait_for_mail(email, "Код доступа", seen)).group()


def log_in(browser, route, email, verify_wrong_code=False):
    code = request_code(browser, route, email)
    previous_session = browser.session_id()
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


def staff_totp_secret(email):
    configured = os.environ.get("SHOP_TEST_STAFF_TOTP_ACCOUNTS")
    if configured is None:
        example = Path(__file__).resolve().parents[2] / ".env.example"
        configured = next(line.split("=", 1)[1] for line in example.read_text().splitlines()
                          if line.startswith("SHOP_BOOTSTRAP_STAFF_TOTP_ACCOUNTS="))
    accounts = dict(entry.split("=", 1) for entry in configured.split(","))
    return base64.b32decode(accounts[email])


def totp_at(secret, step):
    digest = hmac.digest(secret, struct.pack(">Q", step), "sha1")
    offset = digest[-1] & 15
    value = struct.unpack_from(">I", digest, offset)[0] & 0x7FFFFFFF
    return f"{value % 1_000_000:06d}"


def verify_staff_factors(browser, credentials):
    verify = STAFF_ACCESS + "/verify"
    another = Browser()
    another.renew_csrf()
    sessions = (browser, another)
    previous_sessions = [session.session_id() for session in sessions]

    def verify_simultaneously(session):
        with session.open(verify, "POST", credentials, expected=(204, 401)) as response:
            return response.status

    with ThreadPoolExecutor(max_workers=2) as requests:
        outcomes = list(requests.map(verify_simultaneously, sessions))
    assert sorted(outcomes) == [204, 401], "Both factors must be consumed at most once across sessions"
    winner_index = outcomes.index(204)
    winner, loser = sessions[winner_index], sessions[outcomes.index(401)]
    assert winner.session_id() != previous_sessions[winner_index], "Staff login must rotate the session ID"
    loser.call("/api/staff/orders", expected=401)
    if winner is another:
        browser.opener, browser.cookies = another.opener, another.cookies


def log_in_staff(browser, email, check_controls=False, secret=None):
    if secret is None:
        secret = staff_totp_secret(email)
    browser.call("/api/staff/orders", expected=401)
    code = request_code(browser, STAFF_ACCESS, email)
    previous_session = browser.session_id()
    verify = STAFF_ACCESS + "/verify"
    email_factor = {"email": email, "code": code}
    if check_controls:
        browser.call(verify, "POST", email_factor, expected=400)
        browser.call(verify, "POST", dict(email_factor, totp=123456), expected=400)
        current = int(time.time()) // 30
        available = {totp_at(secret, step) for step in range(current - 1, current + 3)}
        wrong_totp = next(f"{candidate:06d}" for candidate in range(5) if f"{candidate:06d}" not in available)
        browser.call(verify, "POST", dict(email_factor, totp=wrong_totp), expected=401)
        browser.call("/api/staff/orders", expected=401)

        buyer = Browser()
        order = new_order(buyer, email)
        log_in(buyer, order + "/access", email)
        buyer.call("/api/staff/orders", expected=403)

    credentials = dict(email_factor, totp=totp_at(secret, int(time.time()) // 30))
    if check_controls:
        verify_staff_factors(browser, credentials)
    else:
        browser.call(verify, "POST", credentials, expected=204)
        assert browser.session_id() != previous_session, "Staff login must rotate the session ID"
    browser.renew_csrf()


def check_staff_accounts(admin, buyer, order, run):
    accounts = "/api/staff/accounts"
    session = "/api/staff/session"
    assert admin.call(session) == {"email": STAFF.lower(), "canManageStaff": True}
    Browser().call(accounts, expected=401)
    buyer.call(accounts, expected=403)
    buyer.call("/api/staff/sketch-settings", expected=403)
    admin.call(accounts, "POST", {"email": "invalid"}, expected=403, csrf=False)
    directory = admin.call(accounts)
    own = next(item for item in directory["items"] if item["email"] == directory["currentEmail"])
    assert own["canManageStaff"]
    own_path = f"{accounts}/{own['id']}"
    admin.call(own_path, "DELETE", expected=409)
    admin.call(own_path + "/reset-totp", "POST", expected=409)

    email = f"team-{run}@example.test"
    created = admin.call(accounts, "POST", {"email": email}, expected=201)
    account = created["account"]
    assert account["email"] == email and account["canManageStaff"] is False
    assert len(base64.b32decode(created["totpSecret"])) == 20
    path = f"{accounts}/{account['id']}"
    try:
        admin.call(accounts, "POST", {"email": email.upper()}, expected=409)
        assert account in admin.call(accounts)["items"]
        member = Browser()
        log_in_staff(member, email, secret=base64.b32decode(created["totpSecret"]))
        assert member.call(session) == {"email": email, "canManageStaff": False}
        member.call(order)
        member.call(accounts, expected=403)
        member.call("/api/staff/sketch-settings", expected=403)
        member.call("/api/staff/sketch-settings", "PUT", {}, expected=403)
        member.call(accounts, "POST", {"email": f"forbidden-{run}@example.test", "canManageStaff": True}, expected=403)

        reset = admin.call(path + "/reset-totp", "POST")
        assert reset["account"] == account and reset["totpSecret"] != created["totpSecret"]
        member.call(order, expected=401)
        member.call("/api/staff/orders", expected=401)
    finally:
        admin.call(path, "DELETE", expected=204)
    assert all(item["id"] != account["id"] for item in admin.call(accounts)["items"])

    manager_email = f"manager-{run}@example.test"
    manager = admin.call(accounts, "POST", {"email": manager_email, "canManageStaff": True}, expected=201)
    manager_path = f"{accounts}/{manager['account']['id']}"
    try:
        other_admin = Browser()
        log_in_staff(other_admin, manager_email, secret=base64.b32decode(manager["totpSecret"]))
        assert other_admin.call(session) == {"email": manager_email, "canManageStaff": True}
        assert other_admin.call(accounts)["currentEmail"] == manager_email
    finally:
        admin.call(manager_path, "DELETE", expected=204)
    other_admin.call(accounts, expected=401)
    print("Verified staff provisioning, permissions, TOTP reset, and session revocation", flush=True)


IMAGE_METADATA = b"Shop synthetic private metadata"


def png(metadata=False, red=255):
    def chunk(name, data):
        return struct.pack(">I", len(data)) + name + data + struct.pack(">I", zlib.crc32(name + data))

    return (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", struct.pack(">IIBBBBB", 1, 1, 8, 2, 0, 0, 0))
        + (chunk(b"tEXt", b"Comment\0" + IMAGE_METADATA) if metadata else b"")
        + chunk(b"IDAT", zlib.compress(bytes((0, red, 0, 0))))
        + chunk(b"IEND", b"")
    )


def assert_clean_png(image):
    assert image.startswith(b"\x89PNG\r\n\x1a\n")
    assert IMAGE_METADATA not in image
    position = 8
    chunks = []
    while position < len(image):
        size, name = struct.unpack_from(">I4s", image, position)
        data_end = position + 8 + size
        assert zlib.crc32(image[position + 4:data_end]) == struct.unpack_from(">I", image, data_end)[0]
        chunks.append(name)
        position = data_end + 4
    assert position == len(image)
    assert chunks[0] == b"IHDR" and b"IDAT" in chunks and chunks[-1] == b"IEND"
    assert not {b"tEXt", b"zTXt", b"iTXt", b"eXIf"}.intersection(chunks)


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


def photo_draft(email, garment="HOODIE", frame="SMALL", three_photos=False, plate=False):
    return {
        "email": email, "garment": garment, "size": "M", "color": "черный",
        "embroidery": {"frame": frame, "threePhotoBackground": three_photos, "licensePlate": plate},
    }


def new_order(browser, email, garment="HOODIE"):
    browser.renew_csrf()
    return "/api/orders/" + browser.call("/api/orders/drafts", "POST", photo_draft(email, garment), expected=201)["id"]


def check_photo_options(visitor):
    prices = {
        "HOODIE": (600000, 650000, 740000, 870000, 990000),
        "T_SHIRT": (500000, 550000, 640000, 770000, 890000),
        "SWEATSHIRT": (None, None, None, None, None),
    }
    variants = (("MINI", 80, False), ("SMALL", 120, False), ("STANDARD", 160, False), ("LARGE", 220, False), ("LARGE", 220, True))
    rows = visitor.call("/api/orders/photo-options")
    expected = {
        (garment, frame, size, three_photos): price
        for garment, garment_prices in prices.items()
        for (frame, size, three_photos), price in zip(variants, garment_prices)
    }
    assert len(rows) == len(expected)
    assert {
        (row["garment"], row["frame"], row["sizeMm"], row["threePhotoBackground"]): row["priceKopecks"]
        for row in rows
    } == expected


def status(snapshot, expected):
    assert snapshot["order"]["status"] == expected, snapshot["order"]["status"]


def pickup_contact():
    return {"recipientName": "Тестовый покупатель", "phone": "+79990000000",
            "city": "Москва", "pickupPointCode": "TEST-MSK"}


def delivery_quote(delivery, price):
    today = datetime.now(ZoneInfo("Europe/Moscow")).date()
    return {"deliveryVersion": delivery["version"], "pickupPointCode": "TEST-MSK",
            "pickupPointAddress": "Тестовая улица, 1", "priceKopecks": price, "deliveryDays": 4,
            "offerValidThrough": (today + timedelta(days=3)).isoformat(),
            "deliverBy": (today + timedelta(days=21)).isoformat()}


def gateway_command(command, **payload):
    with open_url(
        urllib.request.build_opener(), f"{GATEWAY}/test/{command}", "POST",
        json.dumps(payload).encode(), {"Content-Type": "application/json"},
    ) as response:
        return json.load(response)


def notify(event, provider_id, expected=200):
    Browser().call(
        "/api/payments/yookassa/webhook", "POST",
        {"type": "notification", "event": event, "object": {"id": provider_id, "status": "succeeded"}},
        expected=expected,
    )


def open_checkout(buyer, order):
    route = order + "/payment/checkout"
    checkout = buyer.call(route, "POST", OFFER_INPUT)
    assert checkout["checkoutStatus"] == "PENDING" and checkout["paidAt"] is None
    accepted = buyer.call(order)["acceptance"]
    assert accepted["offerVersion"] == OFFER_VERSION and accepted["acceptedAt"]
    assert buyer.call(route, "POST", OFFER_INPUT) == checkout
    assert buyer.call(order)["acceptance"] == accepted
    return checkout["checkoutUrl"].rsplit("/", 1)[-1]


def check_storefront(staff, buyer):
    visitor = Browser()
    route = "/api/storefront"
    staff_route = "/api/staff/storefront"
    visitor.call(route, expected=404)
    buyer.call(staff_route, expected=403)
    draft = staff.call(staff_route)
    assert not draft["published"] and draft["gallery"] == []
    staff.call(staff_route, "PUT", {"version": draft["version"], "published": True}, expected=400)
    image = png(metadata=True)
    banner = staff.upload_image("/api/staff/catalog-images", image)
    embroidery = staff.upload_image("/api/staff/catalog-images", image)
    source = staff.upload_image("/api/staff/catalog-images", image)
    form = {
        "version": draft["version"], "published": True, "heroTitle": " Вышивка по фото ", "heroImageId": banner["id"],
        "photoOrderPriceHint": " От 4 100 ₽ за изделие с вышивкой ",
        "photoOrderTimingHint": "Изготовление от 7 дней",
        "deliveryPriceHint": "До ПВЗ в Москве от 350 ₽",
        "gallery": [{"embroideryImageId": embroidery["id"], "sourceImageId": source["id"], "caption": " Пример "}],
    }
    unpublished = staff.call(staff_route, "PUT", dict(form, published=False))
    visitor.call(f"/api/catalog-images/{source['id']}", expected=404)
    staff.call(source["url"], "DELETE", expected=409)
    staff.call(staff_route, "PUT", form, expected=409)
    published = staff.call(staff_route, "PUT", dict(form, version=unpublished["version"]))
    public = visitor.call(route)
    assert public["version"] == published["version"] and public["heroTitle"] == "Вышивка по фото"
    assert public["photoOrderPriceHint"] == "От 4 100 ₽ за изделие с вышивкой"
    assert public["photoOrderTimingHint"] == form["photoOrderTimingHint"]
    assert public["deliveryPriceHint"] == form["deliveryPriceHint"]
    assert_clean_png(visitor.call(public["heroImage"]["url"]))
    example, = public["gallery"]
    assert example["caption"] == "Пример"
    assert_clean_png(visitor.call(example["source"]["url"]))
    assert_clean_png(visitor.call(example["embroidery"]["url"]))
    staff.call(staff_route, "PUT", {"version": published["version"]})
    visitor.call(route, expected=404)
    visitor.call(example["source"]["url"], expected=404)
    for upload in (banner, embroidery, source):
        staff.call(upload["url"], "DELETE", expected=204)
    print("Verified storefront publication, gallery visibility, and draft image protection", flush=True)


def check_cancellation(staff, buyer, order, image):
    staff_order = order.replace("/api/orders/", "/api/staff/orders/", 1)
    buyer.upload(order, "SOURCE", image)
    status(buyer.call(order + "/submit", "POST"), "IN_REVIEW")
    assert buyer.call(order)["order"]["itemKopecks"] is None
    staff.call(staff_order + "/quote", "POST", {"productionDays": 5}, expected=400)
    staff.call(staff_order + "/quote", "POST", {"itemKopecks": 150000, "productionDays": 5})
    contact = buyer.call(order + "/delivery", "PUT", pickup_contact())
    staff.call(staff_order + "/delivery-quote", "POST", delivery_quote(contact["delivery"], 30000))
    provider_id = open_checkout(buyer, order)

    cancellation = {"reason": "Тестовая отмена"}
    buyer.call(staff_order + "/cancel", "POST", cancellation, expected=403)
    status(staff.call(staff_order + "/cancel", "POST", cancellation), "CANCELLATION_REQUESTED")
    buyer.call(order + "/payment/checkout", "POST", OFFER_INPUT, expected=409)
    assert buyer.call(order)["payment"]["checkoutUrl"] is None
    staff.call(staff_order + "/refund", "POST", expected=409)

    gateway_command("payment", paymentId=provider_id, status="succeeded")
    notify("payment.succeeded", provider_id)
    status(buyer.call(order), "CANCELLATION_REQUESTED")
    assert buyer.call(order)["payment"]["paidAt"]
    assert staff.call(staff_order + "/refund", "POST")["refund"]["status"] == "PENDING"
    refund = gateway_command("refund", paymentId=provider_id, status="succeeded")
    notify("refund.succeeded", refund["id"])
    cancelled = buyer.call(order)
    status(cancelled, "CANCELLED")
    assert cancelled["order"]["cancellationReason"] == cancellation["reason"]
    assert cancelled["payment"]["refund"]["status"] == "SUCCEEDED"


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
    assert submitted["payment"] is None and submitted["sourceFileIds"] == [] and submitted["sketch"] is None
    assert staff.call(staff_order) == submitted

    page = staff.call("/api/staff/orders?size=1")
    assert page["items"][0]["id"] == order_id and page["hasMore"]
    assert staff.call("/api/staff/orders?size=1&page=1")["items"][0]["type"] == "CUSTOM_PHOTO"
    page = staff.call(f"/api/staff/orders?type=CATALOG&status=IN_REVIEW&search={email.upper()}")
    assert [item["id"] for item in page["items"]] == [order_id]
    assert staff.call(f"/api/staff/orders?type=CUSTOM_PHOTO&search={order_id}")["items"] == []
    staff.call("/api/staff/orders?size=101", expected=400)
    staff.call(staff_order + "/quote", "POST", {"itemKopecks": 10, "productionDays": 1},
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
    staff.call(quote_route, "POST", {key: value for key, value in quote.items() if key != "deliverBy"}, expected=400)
    staff.call(quote_route, "POST", dict(quote, deliverBy="2099-02-30"), expected=400)
    staff.call(quote_route, "POST", dict(quote, deliverBy=[2099, 12, 31]), expected=400)
    staff.call(quote_route, "POST", dict(quote, offerValidThrough="2000-01-01"), expected=400)
    staff.call(quote_route, "POST", dict(quote, deliverBy=quote["offerValidThrough"]), expected=400)
    priced = staff.call(quote_route, "POST", quote)
    status(priced, "AWAITING_PAYMENT")
    assert priced["delivery"]["method"] == "CDEK_PICKUP" and priced["delivery"]["deliveryDays"] == 4
    assert priced["delivery"]["offerValidThrough"] == quote["offerValidThrough"]
    assert priced["delivery"]["deliverBy"] == quote["deliverBy"]
    assert priced["totalKopecks"] == variant["priceKopecks"] + 30000
    assert priced["payment"]["amountKopecks"] == priced["totalKopecks"]
    assert priced["offerVersion"] == OFFER_VERSION and priced["acceptance"] is None
    assert buyer.call(order) == priced
    buyer.call(order + "/delivery", "PUT", pickup_contact(), expected=409)
    staff.call(quote_route, "POST", quote, expected=409)
    staff.call(staff_order + "/production-complete", "POST", expected=409)
    ship = {"trackingNumber": "TEST-CATALOG"}
    staff.call(staff_order + "/shipment", "POST", ship, expected=409)
    checkout_route = order + "/payment/checkout"
    other_buyer.call(checkout_route, "POST", OFFER_INPUT, expected=404)
    buyer.call(checkout_route, "POST", expected=400)
    buyer.call(checkout_route, "POST", {"offerVersion": "outdated"}, expected=409)
    assert buyer.call(order)["acceptance"] is None
    try:
        gateway_command("shop-mode", test=False)
        buyer.call(checkout_route, "POST", OFFER_INPUT, expected=503)
        unaccepted = buyer.call(order)
        assert unaccepted["acceptance"] is None and unaccepted["payment"]["checkoutStatus"] is None
        assert not mail_items(email, "Заказ KNITSEW подтверждён")
    finally:
        gateway_command("shop-mode", test=True)
    provider_id = open_checkout(buyer, order)
    accepted = buyer.call(order)["acceptance"]
    gateway_command("payment", paymentId=provider_id, status="canceled")
    notify("payment.canceled", provider_id)
    status(buyer.call(order), "AWAITING_PAYMENT")
    replacement_id = open_checkout(buyer, order)
    assert replacement_id != provider_id
    assert buyer.call(order)["acceptance"] == accepted
    gateway_command("payment", paymentId=replacement_id, status="succeeded")
    notify("payment.succeeded", replacement_id)
    notify("payment.succeeded", replacement_id)
    status(buyer.call(order), "PREPARING_ORDER")
    staff.call(staff_order + "/shipment", "POST", ship, expected=409)
    status(staff.call(staff_order + "/production-complete", "POST"), "READY_TO_SHIP")
    status(staff.call(staff_order + "/shipment", "POST", ship), "SHIPPED")
    assert buyer.call(order)["order"]["trackingNumber"] == ship["trackingNumber"]
    assert f"{SHOP}/orders/{order_id}" in wait_for_mail(email, "Условия заказа KNITSEW готовы")
    confirmation = wait_for_mail(email, "Заказ KNITSEW подтверждён")
    assert f"{SHOP}/orders/{order_id}" in confirmation
    assert f"{SHOP}/offer/{OFFER_VERSION}" in confirmation
    assert quote["pickupPointAddress"] in confirmation
    assert len(mail_items(email, "Заказ KNITSEW подтверждён")) == 1
    refund_route = staff_order + "/refund"
    buyer.call(refund_route, "POST", expected=403)
    try:
        gateway_command("shop-mode", test=False)
        staff.call(refund_route, "POST", expected=503)
        assert staff.call(staff_order)["payment"]["refund"] is None
    finally:
        gateway_command("shop-mode", test=True)
    requested = staff.call(refund_route, "POST")
    assert requested["refund"]["status"] == "PENDING"
    assert staff.call(refund_route, "POST") == requested
    provider_refund = gateway_command("refund", paymentId=replacement_id, status="succeeded")
    notify("refund.succeeded", provider_refund["id"])
    notify("refund.succeeded", provider_refund["id"])
    assert buyer.call(order)["payment"]["refund"]["status"] == "SUCCEEDED"
    status(buyer.call(order), "SHIPPED")
    events = staff.call(staff_order + "/history")["items"]
    assert [e["action"] for e in events] == [
        "REFUND_SUCCEEDED", "REFUND_REQUESTED", "SHIPPED", "PAYMENT_CONFIRMED", "DELIVERY_QUOTED",
    ]
    assert all(isinstance(e["createdAt"], str) and e["createdAt"] for e in events)
    assert events[1]["actorEmail"] == events[2]["actorEmail"] == events[4]["actorEmail"] == STAFF

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

    visitor_image = png(metadata=True)
    staff.upload_image(staff_images, b"not an image", expected=415)
    upload = staff.upload_image(staff_images, visitor_image)
    image_id = upload["id"]
    public_image = f"/api/catalog-images/{image_id}"
    staff_image = staff.call(upload["url"])
    assert_clean_png(staff_image)
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
        "garmentType": "Худи", "images": [{"id": image_id, "caption": "Вышивка крупным планом"}],
        "measurements": [],
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
    measurements = [{"size": "M", "widthCm": 57.5, "lengthCm": 68, "sleeveLengthCm": 62}]
    staff.call(product_route, "PUT", dict(published_product, measurements=measurements))
    staff.call(variant_route, "DELETE", expected=409)

    team_catalog = entries[0]["id"]
    team_placement = f"{staff_catalogs}/{team_catalog}/products/{product_id}"
    staff.call(team_placement, "PUT", {"displayOrder": 40}, expected=204)
    try:
        product_details = visitor.call(public_product)
        assert product_details["variants"] == [stock]
        assert product_details["measurements"] == measurements
        assert product_details["images"] == [{"id": image_id, "url": public_image, "caption": "Вышивка крупным планом"}]
        row = visitor.call(public_catalog)["products"]["items"][0]
        assert row["fromPriceKopecks"] == stock["priceKopecks"] and row["displayOrder"] == 5
        assert row["cover"]["url"] == public_image
        with open_url(visitor.opener, SHOP + public_image, headers={"If-None-Match": f'"{image_id}"'}) as response:
            assert response.read() == staff_image
            assert "no-cache" in response.headers["Cache-Control"]
            cache_headers = {"If-None-Match": response.headers["ETag"]}
            assert cache_headers["If-None-Match"] != f'"{image_id}"'
        with open_url(visitor.opener, SHOP + public_image, method="HEAD") as response:
            assert response.headers["ETag"] == cache_headers["If-None-Match"]
            assert int(response.headers["Content-Length"]) == len(staff_image)
            assert response.read() == b""
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
    staff.call(product_route, "PUT", dict(product, images=[]))
    staff.call(upload["url"], "DELETE", expected=204)
    staff.call(upload["url"], expected=404)
    print("Verified catalog checkout, payment retry, shipment, refund, and staff history", flush=True)


def save_sketch_settings(staff, route, selection, retained=(), files=(), expected=200):
    boundary = "settings-" + uuid.uuid4().hex
    settings = json.dumps({"selection": selection, "retainedReferenceIds": list(retained)}).encode()
    body = (
        f'--{boundary}\r\nContent-Disposition: form-data; name="settings"\r\n'
        "Content-Type: application/json\r\n\r\n"
    ).encode() + settings + b"\r\n"
    for name, media_type, data in files:
        body += (
            f'--{boundary}\r\nContent-Disposition: form-data; name="files"; filename="{name}"\r\n'
            f"Content-Type: {media_type}\r\n\r\n"
        ).encode() + data + b"\r\n"
    body += f"--{boundary}--\r\n".encode()
    return staff.call(route, "PUT", expected=expected, data=body,
                      headers={"Content-Type": "multipart/form-data; boundary=" + boundary})


def generate_sketch(staff, route, input):
    started = staff.call(route, "POST", input, expected=202)
    assert started["status"] == "QUEUED"
    deadline = time.monotonic() + 25
    while time.monotonic() < deadline:
        workspace = staff.call(route)
        job = workspace["latestJob"]
        assert job["id"] == started["id"]
        assert job["status"] != "FAILED", job["failure"]
        if job["status"] == "SUCCEEDED":
            return [draft for draft in workspace["drafts"] if draft["jobId"] == job["id"]]
        time.sleep(0.4)
    raise AssertionError("Sketch generation did not finish")


def check_mini_sketch(staff, email, image):
    buyer = Browser()
    buyer.renew_csrf()
    draft = photo_draft(email, frame="MINI")
    order_id = buyer.call("/api/orders/drafts", "POST", draft, expected=201)["id"]
    order = f"/api/orders/{order_id}"
    staff_order = f"/api/staff/orders/{order_id}"
    log_in(buyer, order + "/access", email)
    saved = buyer.call(order)["order"]
    assert saved["embroidery"] == draft["embroidery"] and saved["itemKopecks"] == 600000
    buyer.upload(order, "SOURCE", image)
    status(buyer.call(order + "/submit", "POST"), "IN_REVIEW")
    staff.call(staff_order + "/quote", "POST", {"productionDays": 7})
    contact = buyer.call(order + "/delivery", "PUT", pickup_contact())
    staff.call(staff_order + "/delivery-quote", "POST", delivery_quote(contact["delivery"], 30000))
    payment_id = open_checkout(buyer, order)
    gateway_command("payment", paymentId=payment_id, status="succeeded")
    assert buyer.call(order + "/payment/sync", "POST")["paidAt"]
    buyer.call(order + "/messages", "POST", {
        "clientMessageId": str(uuid.uuid4()), "body": "Перенесите рисунок в центр груди",
    })
    placement = {"side": "FRONT", "panelWidthMm": 360, "panelHeightMm": 325,
                 "embroideryWidthMm": 80, "embroideryHeightMm": 80, "position": {"xPercent": 50, "yPercent": 50}}
    route = staff_order + "/sketch-generation"
    assert staff.call(route)["panelPreviews"]["FRONT"]["defaultPosition"] == {"xPercent": 90, "yPercent": 22}
    buyer.call(route + "/template?side=FRONT", expected=403)
    assert staff.call(route + "/template?side=FRONT")[:2] == b"\xff\xd8"
    buyer.call(route, "POST", {"placement": placement}, expected=403)
    invalid = dict(placement, position={"xPercent": 101, "yPercent": 50})
    staff.call(route, "POST", {"maxVariants": 1, "placement": invalid}, expected=400)
    [variant] = generate_sketch(staff, route, {"maxVariants": 1, "placement": placement})
    assert staff.call(route)["latestJob"]["placement"] == placement
    sketch_path = order + "/files/" + variant["fileId"]
    buyer.call(sketch_path, expected=404)
    staff.call(staff_order + "/sketches", "POST", {"fileId": variant["fileId"]})
    assert buyer.call(sketch_path) == staff.call(sketch_path)


def main():
    buyer, staff, other = Browser(), Browser(), Browser()
    run = uuid.uuid4().hex[:12]
    buyer_email = f"buyer-{run}@example.test"
    other_email = f"other-{run}@example.test"
    image = png(metadata=True)

    buyer.call("/actuator/health/readiness", expected=404)
    check_photo_options(Browser())
    buyer.call("/api/orders/drafts", "POST", {}, expected=403, csrf=False)
    buyer.renew_csrf()
    draft = photo_draft(buyer_email, frame="LARGE", three_photos=True, plate=True)
    buyer.call("/api/orders/drafts", "POST", dict(draft, garment=0), expected=400)
    buyer.call("/api/orders/drafts", "POST", dict(draft, unexpected=True), expected=400)
    buyer.call("/api/orders/drafts", "POST", {key: value for key, value in draft.items() if key != "embroidery"}, expected=400)
    buyer.call("/api/orders/drafts", "POST", photo_draft(buyer_email, frame="STANDARD", three_photos=True), expected=400)
    order = "/api/orders/" + buyer.call("/api/orders/drafts", "POST", draft, expected=201)["id"]
    staff_order = order.replace("/api/orders/", "/api/staff/orders/", 1)
    buyer.call(order, expected=401)
    log_in(buyer, order + "/access", buyer_email, verify_wrong_code=True)
    created = buyer.call(order)["order"]
    assert created["embroidery"] == draft["embroidery"]
    assert created["itemKopecks"] == 990000
    other_order = new_order(other, other_email, garment="SWEATSHIRT")
    log_in(other, other_order + "/access", other_email)
    other.call(order, expected=404)
    buyer.call("/api/staff/orders", expected=403)
    buyer.call(order + "/submit", "POST", expected=409)
    buyer.upload(order, "SOURCE", b"this file contains plain text", expected=415)
    source = buyer.upload(order, "SOURCE", image)
    source_path = order + "/files/" + source["id"]
    assert buyer.call(source_path) == image
    other.call(source_path, expected=404)
    replace_path = f"{order}/files?purpose=SOURCE&replace={source['id']}"
    buyer.upload_image(replace_path, b"invalid replacement", expected=415)
    assert buyer.call(source_path) == image
    replacement = buyer.upload_image(replace_path, image)
    replacement_path = order + "/files/" + replacement["id"]
    buyer.call(source_path, expected=404)
    assert buyer.call(order)["sourceFileIds"] == [replacement["id"]]
    for _ in range(2):
        buyer.call(replacement_path, "DELETE", expected=204)
    buyer.call(order + "/submit", "POST", expected=409)
    last_photo = buyer.upload(order, "SOURCE", image)
    buyer.call(order + "/submit", "POST", expected=409)
    buyer.upload(order, "SOURCE", png(red=180))
    buyer.call(order + "/submit", "POST", expected=409)
    buyer.upload(order, "SOURCE", png(red=110))
    buyer.upload(order, "SOURCE", png(red=80), expected=409)
    replacement = buyer.upload_image(f"{order}/files?purpose=SOURCE&replace={last_photo['id']}", png(red=50))
    assert len(buyer.call(order)["sourceFileIds"]) == 3
    status(buyer.call(order + "/submit", "POST"), "IN_REVIEW")
    status(buyer.call(order + "/submit", "POST"), "IN_REVIEW")
    buyer.call(f"{order}/files/{replacement['id']}", "DELETE", expected=409)
    order_url = f"{SHOP}{order.removeprefix('/api')}"
    assert order_url in wait_for_mail(buyer_email, "Заявка получена")
    log_in_staff(staff, STAFF, check_controls=True)
    sketch_generation = staff_order + "/sketch-generation"
    placement = {"side": "FRONT", "panelWidthMm": 500, "panelHeightMm": 500,
                 "embroideryWidthMm": 220, "embroideryHeightMm": 220, "position": {"xPercent": 50, "yPercent": 50}}
    buyer.call(sketch_generation, expected=403)
    staff.call(sketch_generation, "POST", {"maxVariants": 1, "placement": placement}, expected=409)
    assert any(item["id"] == order.rsplit("/", 1)[-1] for item in staff.call("/api/staff/orders")["items"])
    check_staff_accounts(staff, buyer, order, run)
    check_catalogs(staff, buyer, run)
    check_storefront(staff, buyer)
    print("Verified order access, CSRF, and private S3 upload/download", flush=True)
    staff.call(staff_order + "/quote", "POST", {"itemKopecks": 990000.5, "productionDays": 7}, expected=400)
    staff.call(staff_order + "/quote", "POST", {"itemKopecks": 1000000, "productionDays": 7}, expected=400)
    quote = staff.call(
        staff_order + "/quote", "POST", {"productionDays": 7},
    )
    status(quote, "AWAITING_DELIVERY_QUOTE")
    assert quote["order"]["itemKopecks"] == 990000 and quote["order"]["embroidery"] == draft["embroidery"]
    assert quote["totalKopecks"] is None and quote["payment"] is None
    buyer.call(order + "/payment/checkout", "POST", OFFER_INPUT, expected=409)
    delivery_route = staff_order + "/delivery-quote"
    staff.call(delivery_route, "POST", delivery_quote({"version": 1}, 30000), expected=409)
    with_contact = buyer.call(order + "/delivery", "PUT", pickup_contact())
    priced = staff.call(delivery_route, "POST", delivery_quote(with_contact["delivery"], 30000))
    status(priced, "AWAITING_PAYMENT")
    assert priced["totalKopecks"] == priced["payment"]["amountKopecks"] == 1020000
    staff.upload(order, "SKETCH", image, expected=409)
    gateway_command("lose-payment-response")
    buyer.call(order + "/payment/checkout", "POST", OFFER_INPUT, expected=502)
    interrupted = buyer.call(order)
    assert interrupted["payment"]["checkoutStatus"] == "CREATING"
    assert interrupted["acceptance"]["offerVersion"] == OFFER_VERSION
    try:
        gateway_command("shop-mode", test=False)
        buyer.call(order + "/payment/sync", "POST", expected=503)
        assert buyer.call(order)["payment"]["checkoutStatus"] == "CREATING"
    finally:
        gateway_command("shop-mode", test=True)
    recovered = buyer.call(order + "/payment/sync", "POST")
    provider_id = open_checkout(buyer, order)
    assert recovered["checkoutUrl"].endswith(provider_id)
    assert buyer.call(order)["acceptance"] == interrupted["acceptance"]
    confirmation = wait_for_mail(buyer_email, "Заказ KNITSEW подтверждён")
    assert "22 × 22 см, фон из трёх кадров авто, вышивка госномера в подарок" in confirmation
    assert "Всего к оплате: 10200,00 ₽" in confirmation
    notify("payment.succeeded", provider_id)
    status(buyer.call(order), "AWAITING_PAYMENT")
    gateway_command("payment", paymentId=provider_id, status="succeeded", value="10199.99")
    notify("payment.succeeded", provider_id, expected=502)
    assert buyer.call(order)["payment"]["paidAt"] is None
    gateway_command("payment", paymentId=provider_id, value="10200.00")
    assert buyer.call(order + "/payment/sync", "POST")["paidAt"]
    status(buyer.call(order), "PREPARING_SKETCH")

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
    assert order_url in wait_for_mail(buyer_email, "Новое сообщение по заказу")
    assert len(mail_items(buyer_email, "Заявка получена")) == 1
    print("Verified live SSE, replay, idempotent messages, and order email", flush=True)

    buyer.call(order + "/messages", "POST", {
        "clientMessageId": str(uuid.uuid4()), "body": f"Для эскиза оставьте контур, связь: {buyer_email}",
    })
    workspace = staff.call(sketch_generation)
    assert workspace["available"] and workspace["latestJob"] is None and workspace["drafts"] == []
    assert set(workspace["panelEstimates"]) == {"FRONT", "BACK"}
    model_settings = "/api/staff/sketch-settings"
    original_models = staff.call(model_settings)["selection"]
    initial_models = dict(original_models, responseModel="gemini-2-5-flash-lite", imageModel="flash")
    save_sketch_settings(staff, model_settings, dict(initial_models, imageModel="unsupported"), expected=400)
    initial_models["systemPrompt"] = "Чёткие контуры и хлопковые нити."
    examples = (
        ("palette.png", "image/png", image),
        ("materials.txt", "text/plain", "Используйте два цвета.".encode()),
    )
    save_sketch_settings(staff, model_settings, initial_models, files=(("invalid.txt", "text/plain", b"\xff"),), expected=400)
    saved = save_sketch_settings(staff, model_settings, initial_models, files=examples)
    saved_models = saved["selection"]
    assert saved_models["systemPrompt"] == initial_models["systemPrompt"]
    assert [reference["name"] for reference in saved["references"]] == [entry[0] for entry in examples]
    retained = [reference["id"] for reference in saved["references"]]
    for ref_id, (_, _, data) in zip(retained, examples):
        path = f"{model_settings}/references/{ref_id}"
        assert staff.call(path) == data
        buyer.call(path, expected=403)
    save_sketch_settings(staff, model_settings, initial_models, expected=409)
    buyer.call(sketch_generation, "POST", {"maxVariants": 1, "placement": placement}, expected=403)
    staff.call(sketch_generation, "POST", {"feedback": "Без фона", "placement": placement}, expected=400)
    staff.call(sketch_generation, "POST", {"maxVariants": 4, "placement": placement}, expected=400)
    staff.call(sketch_generation, "POST", {"placement": dict(placement, embroideryWidthMm=221)}, expected=400)
    drafts = generate_sketch(staff, sketch_generation, {"maxVariants": 2, "placement": placement})
    assert len(drafts) == 2
    for draft in drafts:
        assert not draft["published"] and draft["previousFileId"] is None
        draft_path = order + "/files/" + draft["fileId"]
        assert staff.call(draft_path).startswith(b"\x89PNG\r\n\x1a\n")
        buyer.call(draft_path, expected=404)
    status(buyer.call(order), "PREPARING_SKETCH")

    sketch_file = staff.upload(order, "SKETCH", image)["id"]
    buyer.call(order + "/files/" + sketch_file, expected=404)
    snapshot = staff.call(staff_order + "/sketches", "POST", {"fileId": sketch_file})
    status(snapshot, "SKETCH_REVIEW")
    assert buyer.call(order + "/files/" + sketch_file) == image
    old_sketch = f"{order}/sketches/{snapshot['sketch']['id']}"
    status(buyer.call(old_sketch + "/request-changes", "POST"), "CHANGES_REQUESTED")
    edited_models = save_sketch_settings(staff, model_settings, dict(
        saved_models, responseModel="gemini-3-1-flash-lite", imageModel="standard",
    ), retained=retained)["selection"]
    assert staff.call(model_settings)["selection"] == edited_models
    [revision] = generate_sketch(staff, sketch_generation, {
        "previousFileId": drafts[1]["fileId"], "feedback": "Оставьте только контур", "placement": placement,
    })
    assert revision["previousFileId"] == drafts[1]["fileId"]
    restored = save_sketch_settings(staff, model_settings, dict(original_models, version=edited_models["version"]))
    assert restored["references"] == []
    staff.call(f"{model_settings}/references/{retained[0]}", expected=404)
    new_file = revision["fileId"]
    revised_path = order + "/files/" + new_file
    buyer.call(revised_path, expected=404)
    assert staff.call(revised_path) != staff.call(order + "/files/" + drafts[1]["fileId"])
    revised = staff.call(staff_order + "/sketches", "POST", {"fileId": new_file})["sketch"]
    assert buyer.call(revised_path) == staff.call(revised_path)
    assert next(draft for draft in staff.call(sketch_generation)["drafts"] if draft["fileId"] == new_file)["published"]
    for draft in drafts:
        buyer.call(order + "/files/" + draft["fileId"], expected=404)
    print("Verified private AI variants, revision from the selected image, and publication", flush=True)
    assert revised["version"] == 2
    buyer.call(old_sketch + "/approve", "POST", expected=409)
    status(buyer.call(f"{order}/sketches/{revised['id']}/approve", "POST"), "IN_PRODUCTION")
    finished = staff.call(staff_order + "/production-complete", "POST")
    status(finished, "READY_TO_SHIP")
    assert finished["payment"]["id"] == priced["payment"]["id"] and finished["payment"]["paidAt"]
    staff.call(staff_order + "/reject", "POST", {"reason": "Тест"}, expected=409)
    status(staff.call(staff_order + "/shipment", "POST", {"trackingNumber": "TEST123"}), "SHIPPED")
    assert buyer.call(order)["order"]["trackingNumber"] == "TEST123"
    buyer.call(staff_order + "/history", expected=403)
    buyer.call("/api/session/logout", "POST", expected=204)
    buyer.call(order, expected=401)
    print("Verified one advance payment with delivery, sketch approval, shipment, and logout")
    check_cancellation(staff, other, other_order, image)
    print("Verified cancellation and refund after payment on a previously issued link", flush=True)
    check_mini_sketch(staff, f"mini-{run}@example.test", image)
    print("Verified MINI pricing, chat placement, and staff publication", flush=True)


if __name__ == "__main__":
    main()
