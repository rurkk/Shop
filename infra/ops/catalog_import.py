#!/usr/bin/env python3
"""Import a local KNITSEW workbook and photo pack through the staff API."""

import argparse
import fcntl
import getpass
import hashlib
import http.client
import http.cookiejar
import json
import math
import os
import re
import sys
import tempfile
import urllib.error
import urllib.parse
import urllib.request
import uuid
import warnings
from contextlib import contextmanager
from dataclasses import dataclass
from decimal import Decimal
from pathlib import Path
from zipfile import BadZipFile

from openpyxl import load_workbook
from openpyxl.utils.exceptions import InvalidFileException
from PIL import Image


PRODUCTS = "/api/staff/products"
CATALOGS = "/api/staff/catalogs"
IMAGES = "/api/staff/catalog-images"
STOREFRONT = "/api/staff/storefront"
PRODUCT_SHEET = "Товары"
GROUP_SHEET = "Группы вариантов"
GALLERY_SHEET = "Галерея"
MEASUREMENT_SHEET = "Замеры"
PRODUCT_HEADERS = (
    "Название", "Импорт", "Публикация", "Группа вариантов", "Тип изделия", "Дизайн", "Папка фото",
    "Slug", "Описание для покупателя", "Порядок", "Коллекция", "Заметка по фото", "Источник Instagram",
)
GROUP_HEADERS = ("Код группы", "Цвет", "Размеры", "Цена, ₽", "Наличие", "Срок, дней")
GALLERY_HEADERS = ("Добавить", "Подпись", "Фото вышивки", "Исходник")
MEASUREMENT_HEADERS = ("Slug товара", "Размер", "Ширина, см", "Длина, см", "Рукав, см")
SLUG = re.compile(r"[a-z0-9]+(?:-[a-z0-9]+)*")
PHOTO_NAME = re.compile(r"(\d{2})-[a-z0-9-]+\.(?:jpe?g|png)")
SKU_LIMIT = 64
PRICE_LIMIT = 9_007_199_254_740_991
AVAILABILITY = {"В наличии": "IN_STOCK", "Под заказ": "MADE_TO_ORDER", "Нет в наличии": "OUT_OF_STOCK"}
IMAGE_FORMATS = {"JPEG": "image/jpeg", "PNG": "image/png"}
IMAGE_EXTENSIONS = {".jpg": "JPEG", ".jpeg": "JPEG", ".png": "PNG"}


class ImportFailure(Exception):
    pass


@dataclass(frozen=True)
class Photo:
    path: Path
    relative: str
    digest: str
    media_type: str


@dataclass
class Product:
    form: dict
    photos: list[Photo]
    variants: list[dict]
    catalog: str
    order: int
    publish: bool

    @property
    def slug(self):
        return self.form["slug"]

    def with_images(self, image_ids):
        images = []
        for index, image_id in enumerate(image_ids):
            caption = (None, "Вышивка крупным планом", "Дополнительный ракурс")[min(index, 2)]
            images.append({"id": image_id, "caption": caption})
        return dict(self.form, images=images)


@dataclass(frozen=True)
class Example:
    embroidery: Photo
    source: Photo | None
    caption: str | None


def string(value, label, limit, optional=False):
    if value is None:
        value = ""
    if not isinstance(value, str):
        raise ImportFailure(f"{label}: ожидается текст")
    value = value.strip()
    if not value and not optional:
        raise ImportFailure(f"{label}: поле пустое")
    if len(value.encode("utf-16-le")) // 2 > limit:
        raise ImportFailure(f"{label}: допустимо до {limit} символов")
    return value


def choice(value, label):
    if value not in ("Да", "Нет"):
        raise ImportFailure(f"{label}: выберите «Да» или «Нет»")
    return value == "Да"


def integer(value, label, minimum):
    if type(value) not in (int, float) or not minimum <= value <= 2_147_483_647 or int(value) != value:
        raise ImportFailure(f"{label}: ожидается целое число от {minimum}")
    return int(value)


def price_kopecks(value):
    if type(value) in (int, float):
        amount = Decimal(str(value)) * 100
        if amount.is_finite() and 1 <= amount <= PRICE_LIMIT and amount == int(amount):
            return int(amount)
    raise ImportFailure("Цена, ₽: укажите положительное число с точностью до копейки")


def centimeters(value, label):
    if type(value) in (int, float) and 0.1 <= value <= 999.9 and round(value, 1) == value:
        return value
    raise ImportFailure(f"{label}: укажите от 0,1 до 999,9 см с точностью до 0,1 см")


def slug(value, label):
    value = string(value, label, 80)
    if not SLUG.fullmatch(value):
        raise ImportFailure(f"{label}: используйте строчные латинские буквы, цифры и дефис")
    return value


def checked_path(root, value, label):
    relative = Path(string(value, label, 512))
    path = (root / relative).resolve()
    if relative.is_absolute() or ".." in relative.parts or not path.is_relative_to(root) or path == root:
        raise ImportFailure(f"{label}: укажите путь внутри папки photos")
    return path


def photo(root, path):
    relative = path.relative_to(root).as_posix()
    if not path.is_file():
        raise ImportFailure(f"Фото отсутствует: {relative}")
    if not 0 < path.stat().st_size <= 10 * 1024 * 1024:
        raise ImportFailure(f"Недопустимый размер файла: {relative}, предел 10 МиБ")
    try:
        with Image.open(path) as image:
            image_format = image.format
            if image_format not in IMAGE_FORMATS or image_format != IMAGE_EXTENSIONS.get(path.suffix):
                raise ImportFailure(f"Нужен JPEG или PNG с соответствующим расширением: {relative}")
            width, height = image.size
            if max(width, height) > 6000 or width * height > 20_000_000:
                raise ImportFailure(f"Размер изображения превышает предел Shop: {relative}")
            image.verify()
        with Image.open(path) as image:
            image.load()
    except (OSError, SyntaxError, Image.DecompressionBombError) as error:
        raise ImportFailure(f"Изображение не читается: {relative}") from error
    return Photo(path, relative, hashlib.sha256(path.read_bytes()).hexdigest(), IMAGE_FORMATS[image_format])


def product_photos(root, folder):
    folder = checked_path(root, folder, "Папка фото")
    if not folder.is_dir():
        raise ImportFailure(f"Папка фото отсутствует: {folder}")
    photos = []
    numbers = set()
    for path in sorted(folder.iterdir()):
        if path.name.startswith("."):
            continue
        match = PHOTO_NAME.fullmatch(path.name)
        if not match or not path.is_file() or path.is_symlink():
            raise ImportFailure(f"Ожидается файл с номером и расширением JPEG или PNG: {path.name}")
        if match[1] in numbers:
            raise ImportFailure(f"Повторяется номер фото {match[1]} в {folder.name}")
        numbers.add(match[1])
        photos.append(photo(root, path))
    stems = [item.path.stem for item in photos]
    if not 2 <= len(photos) <= 12 or stems[:2] != ["01-cover", "02-detail"]:
        raise ImportFailure(f"В {folder.name} нужны обложка 01-cover и крупный план 02-detail. Максимум 12 фото")
    if len({item.digest for item in photos}) != len(photos):
        raise ImportFailure(f"В {folder.name} повторяется фотография")
    return photos


def rows(book, sheet_name, headers):
    if sheet_name not in book.sheetnames:
        raise ImportFailure(f"В таблице отсутствует лист «{sheet_name}»")
    sheet = book[sheet_name]
    header = next(sheet.iter_rows(min_row=5, max_row=5, max_col=len(headers), values_only=True), ())
    if header != headers:
        raise ImportFailure(f"Лист «{sheet_name}»: заголовки должны совпадать с шаблоном")
    for index, cells in enumerate(sheet.iter_rows(min_row=6, max_col=len(headers)), start=6):
        values = tuple(cell.value for cell in cells)
        if all(value is None for value in values):
            continue
        if any(cell.data_type in ("f", "e") for cell in cells):
            raise ImportFailure(f"Лист «{sheet_name}», строка {index}: укажите значения вместо формул и ошибок Excel")
        yield index, values


def variant_groups(book, problems):
    groups = {}
    pairs = {}
    for row, (group, color, sizes, price, availability, days) in rows(book, GROUP_SHEET, GROUP_HEADERS):
        try:
            group = string(group, "Код группы", 80)
            color = string(color, "Цвет", 80)
            if type(sizes) in (int, float) and math.isfinite(sizes) and sizes == int(sizes):
                sizes = str(int(sizes))
            sizes = [string(size, "Размер", 32) for size in string(sizes, "Размеры", 1500).split(",")]
            kopecks = price_kopecks(price)
            if availability not in AVAILABILITY:
                raise ImportFailure("Выберите статус наличия")
            status = AVAILABILITY[availability]
            if status == "MADE_TO_ORDER":
                days = integer(days, "Срок, дней", 1)
            elif days is not None:
                raise ImportFailure("Срок указывается только для статуса «Под заказ»")
            keys = {(size.lower(), color.lower()) for size in sizes}
            seen = pairs.setdefault(group, set())
            if len(keys) != len(sizes) or keys & seen:
                raise ImportFailure(f"В группе «{group}» повторяется сочетание размера и цвета")
            seen.update(keys)
            groups.setdefault(group, []).extend(
                dict(size=size, color=color, priceKopecks=kopecks, availability=status, productionDays=days)
                for size in sizes
            )
        except ImportFailure as error:
            problems.append(f"{GROUP_SHEET}, строка {row}: {error}")
    return groups


def variant_payload(product_slug, variant):
    identity = json.dumps([variant["size"].lower(), variant["color"].lower()], ensure_ascii=False)
    suffix = hashlib.sha256(identity.encode()).hexdigest()[:12].upper()
    return dict(variant, sku=f"{product_slug[:SKU_LIMIT - 13].upper()}-{suffix}")


def product_measurements(book, problems):
    measurements = {}
    for row, (key, size, width, length, sleeve) in rows(book, MEASUREMENT_SHEET, MEASUREMENT_HEADERS):
        try:
            key = slug(key, "Slug товара")
            size = string(size, "Размер", 32)
            sizes = measurements.setdefault(key, {})
            if size.lower() in sizes:
                raise ImportFailure(f"Для {key} уже указаны замеры размера {size}")
            if len(sizes) >= 32:
                raise ImportFailure(f"Для {key} допустимо до 32 строк замеров")
            sizes[size.lower()] = dict(
                size=size, widthCm=centimeters(width, "Ширина"), lengthCm=centimeters(length, "Длина"),
                sleeveLengthCm=centimeters(sleeve, "Рукав") if sleeve is not None else None,
            )
        except ImportFailure as error:
            problems.append(f"{MEASUREMENT_SHEET}, строка {row}: {error}")
    return measurements


def parse_products(book, root, problems, operation):
    groups = variant_groups(book, problems)
    measurements = product_measurements(book, problems)
    products = []
    seen_slugs = set()
    seen_orders = set()
    seen_skus = set()
    known_slugs = set()
    for row, values in rows(book, PRODUCT_SHEET, PRODUCT_HEADERS):
        name, include, publish, group, garment, design, folder, key, description, order, catalog, *_ = values
        try:
            include = choice(include, "Импорт")
            publish = choice(publish, "Публикация")
            if publish and not include:
                raise ImportFailure("Для публикации включите карточку в импорт")
            if isinstance(key, str):
                known_slugs.add(key.strip())
            if not include or (operation == "publish" and not publish):
                continue
            key = slug(key, "Slug")
            if key in seen_slugs:
                raise ImportFailure(f"Повторяется slug {key}")
            seen_slugs.add(key)
            form = dict(
                slug=key, name=string(name, "Название", 160), garmentType=string(garment, "Тип изделия", 80),
                design=string(design, "Дизайн", 160),
                description=string(description, "Описание", 4000, optional=not publish),
                measurements=list(measurements.get(key, {}).values()), published=False,
            )
            group = string(group, "Группа вариантов", 80, optional=True)
            if group and group not in groups:
                raise ImportFailure(f"Группа «{group}» отсутствует на листе «{GROUP_SHEET}»")
            variants = [variant_payload(key, variant) for variant in groups.get(group, [])]
            if publish and not variants:
                raise ImportFailure("Для публикации укажите хотя бы один вариант")
            skus = {variant["sku"] for variant in variants}
            if len(skus) != len(variants) or skus & seen_skus:
                raise ImportFailure("В пакете повторяется SKU")
            seen_skus.update(skus)
            catalog = slug(catalog, "Коллекция")
            order = integer(order, "Порядок", 0)
            if (catalog, order) in seen_orders:
                raise ImportFailure(f"Повторяется место {order} в коллекции {catalog}")
            seen_orders.add((catalog, order))
            photos = product_photos(root, folder)
            products.append(Product(form, photos, variants, catalog, order, publish))
        except ImportFailure as error:
            problems.append(f"{PRODUCT_SHEET}, строка {row}: {error}")
    if unknown := measurements.keys() - known_slugs:
        problems.append(f"{MEASUREMENT_SHEET}: в товарах отсутствуют slug {', '.join(sorted(unknown))}")
    return products


def parse_gallery(book, root, problems):
    examples = []
    digests = set()
    for row, (include, caption, embroidery, source) in rows(book, GALLERY_SHEET, GALLERY_HEADERS):
        try:
            if not choice(include, "Добавить"):
                continue
            caption = string(caption, "Подпись", 160, optional=True) or None
            embroidery = photo(root, checked_path(root, embroidery, "Фото вышивки"))
            source = photo(root, checked_path(root, source, "Исходник")) if source else None
            if embroidery.digest in digests:
                raise ImportFailure("Фото вышивки уже выбрано в другой строке")
            digests.add(embroidery.digest)
            examples.append(Example(embroidery, source, caption))
        except ImportFailure as error:
            problems.append(f"{GALLERY_SHEET}, строка {row}: {error}")
    if len(examples) > 24:
        problems.append("Галерея: Shop принимает до 24 примеров вместе с существующими")
    return examples


def read_inputs(args):
    root = args.photos.resolve(strict=True)
    if not root.is_dir():
        raise ImportFailure(f"Укажите распакованную папку photos: {root}")
    problems = []
    products, examples = [], []
    book = load_workbook(args.workbook, read_only=True)
    try:
        if args.operation != "gallery":
            products = parse_products(book, root, problems, args.operation)
        if args.operation in ("check", "gallery"):
            examples = parse_gallery(book, root, problems)
    finally:
        book.close()
    if problems:
        raise ImportFailure("\n".join(problems))
    return products, examples


def origin(value):
    url = urllib.parse.urlsplit(value)
    if (not url.hostname or url.username is not None or url.password is not None
            or url.path not in ("", "/") or url.query or url.fragment or any(char.isspace() for char in value)):
        raise ImportFailure("Укажите адрес Shop целиком, без пути, параметров и учётных данных")
    if url.scheme != "https" and not (url.scheme == "http" and url.hostname in ("127.0.0.1", "localhost", "::1")):
        raise ImportFailure("Для удалённого Shop нужен HTTPS. HTTP допустим для локального стенда или SSH-туннеля")
    port = url.port
    if port == (443 if url.scheme == "https" else 80):
        port = None
    host = url.hostname if ":" not in url.hostname else f"[{url.hostname}]"
    return f"{url.scheme}://{host}" + (f":{port}" if port else "")


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, response, code, message, headers, new_url):
        return None


class Shop:
    def __init__(self, base):
        self.base = base
        self.csrf = {}
        cookies = urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar())
        self.opener = urllib.request.build_opener(NoRedirect(), cookies)

    def call(self, route, method="GET", value=None, *, expected=200, data=None, content_type=None):
        headers = {"Accept": "application/json"}
        if method != "GET":
            headers.update(self.csrf)
        if value is not None:
            data = json.dumps(value, ensure_ascii=False).encode()
            content_type = "application/json"
        if content_type:
            headers["Content-Type"] = content_type
        request = urllib.request.Request(self.base + route, data=data, headers=headers, method=method)
        try:
            with self.opener.open(request, timeout=30) as response:
                if response.status != expected:
                    raise ImportFailure(f"{method} {route}: неожиданный HTTP {response.status}")
                payload = response.read()
                if expected in (202, 204):
                    return None
                if response.headers.get_content_type() != "application/json":
                    raise ImportFailure(f"{method} {route}: сервер вернул ответ другого формата")
                return json.loads(payload)
        except urllib.error.HTTPError as error:
            with error:
                try:
                    detail = json.loads(error.read(2048)).get("detail", "")
                except (ValueError, AttributeError):
                    detail = ""
            detail = " ".join(str(detail).split())[:240]
            if error.code in (401, 403):
                detail = detail or "Проверьте учётную запись и одноразовые коды. Для продолжения запустите команду снова"
            if 300 <= error.code < 400:
                detail = "Укажите конечный адрес API без перенаправлений"
            raise ImportFailure(f"{method} {route}: HTTP {error.code}. {detail}".rstrip()) from None
        except (OSError, http.client.HTTPException) as error:
            raise ImportFailure(f"{method} {route}: ошибка соединения ({error}). Проверьте адрес и сеть, затем повторите запуск") from None
        except ValueError as error:
            raise ImportFailure(f"{method} {route}: некорректный ответ API") from error

    def csrf_token(self):
        token = self.call("/api/csrf")
        self.csrf = {token["headerName"]: token["token"]}

    def login(self, email):
        email = email or input("Рабочий email сотрудника: ").strip()
        if not email:
            raise ImportFailure("Укажите рабочий email сотрудника")
        self.csrf_token()
        self.call("/api/staff/access/code", "POST", {"email": email}, expected=202)
        # Never fall back to an echoed prompt for one-time credentials.
        with warnings.catch_warnings():
            warnings.simplefilter("error", getpass.GetPassWarning)
            code = getpass.getpass("Код из письма: ").strip()
            totp = getpass.getpass("Код приложения-аутентификатора: ").strip()
        if not all(re.fullmatch(r"[0-9]{6}", factor) for factor in (code, totp)):
            raise ImportFailure("Оба одноразовых кода должны состоять из шести цифр")
        self.call("/api/staff/access/verify", "POST", {"email": email, "code": code, "totp": totp}, expected=204)
        self.csrf_token()

    def pages(self, route, field=None):
        page = 0
        while True:
            result = self.call(f"{route}?page={page}&size=100")
            result = result[field] if field else result
            yield from result["items"]
            if not result["hasMore"]:
                return
            page += 1

    def upload(self, item):
        data = item.path.read_bytes()
        if hashlib.sha256(data).hexdigest() != item.digest:
            raise ImportFailure(f"Фото изменилось после проверки: {item.relative}")
        boundary = "knitsew-" + uuid.uuid4().hex
        filename = "photo.jpg" if item.media_type == "image/jpeg" else "photo.png"
        head = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{filename}"\r\n'
                f"Content-Type: {item.media_type}\r\n\r\n").encode()
        body = head + data + f"\r\n--{boundary}--\r\n".encode()
        return self.call(IMAGES, "POST", expected=201, data=body, content_type=f"multipart/form-data; boundary={boundary}")["id"]


class Journal:
    def __init__(self, path, base):
        self.path = path
        self.data = json.loads(path.read_text()) if path.exists() else {"origin": base, "images": {}, "products": {}}
        if self.data.get("origin") != base:
            raise ImportFailure(f"Журнал относится к другому адресу. Для {base} укажите другой файл --state")
        self.images = self.data["images"]
        self.products = self.data["products"]

    def save(self):
        with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", dir=self.path.parent, delete=False) as target:
            temporary = Path(target.name)
            try:
                json.dump(self.data, target, indent=2, ensure_ascii=False)
                target.write("\n")
                target.flush()
                os.fsync(target.fileno())
                os.replace(temporary, self.path)
            finally:
                temporary.unlink(missing_ok=True)

    def image_id(self, api, item):
        if item.digest not in self.images:
            try:
                self.images[item.digest] = api.upload(item)
            except ImportFailure as error:
                raise ImportFailure(f"{item.relative}: {error}") from error
            self.save()
            print(f"  Загружено фото {item.relative}", flush=True)
        return self.images[item.digest]


@contextmanager
def locked_journal(path, base):
    path = path.resolve()
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.with_name(path.name + ".lock").open("a") as lock:
        try:
            fcntl.flock(lock.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise ImportFailure(f"Этот журнал уже используется: {path}") from None
        yield Journal(path, base)


def product_body(current):
    body = {key: current[key] for key in ("slug", "name", "design", "garmentType", "description", "measurements", "published")}
    body["images"] = [{key: item[key] for key in ("id", "caption")} for item in current["images"]]
    return body


def matches_create(api_product, product, journal):
    image_ids = [journal.images.get(item.digest) for item in product.photos]
    current = product_body(api_product)
    return None not in image_ids and current == product.with_images(image_ids)


def verify_owned(api, journal, product, current):
    saved_id = journal.products.get(product.slug)
    if not current:
        if saved_id:
            raise ImportFailure(f"{product.slug}: карточка из журнала отсутствует на сервере")
        return None
    if saved_id == current["id"]:
        return current
    if not saved_id:
        detail = api.call(f"{PRODUCTS}/{current['id']}")
        if matches_create(detail, product, journal):
            journal.products[product.slug] = detail["id"]
            journal.save()
            return current
    raise ImportFailure(f"{product.slug}: slug уже занят другой карточкой. Проверьте товар и журнал")


def same_slot(variant):
    return variant["size"].lower(), variant["color"].lower()


def apply_variants(api, route, current, wanted):
    slots = {same_slot(item): item for item in current}
    for variant in wanted:
        saved = slots.get(same_slot(variant))
        if saved is None:
            api.call(route, "POST", variant, expected=201)
        elif any(saved[key] != value for key, value in variant.items()):
            if saved["sku"] != variant["sku"]:
                raise ImportFailure(f"{variant['size']} / {variant['color']}: уже есть вариант с другим SKU")
            api.call(f"{route}/{saved['id']}", "PUT", variant)


def import_products(api, journal, products, listings, catalogs, memberships):
    work = []
    for product in products:
        existing = verify_owned(api, journal, product, listings.get(product.slug))
        if existing and existing["published"]:
            print(f"Пропущен опубликованный товар {product.slug}", flush=True)
            continue
        work.append((product, existing, catalogs[product.catalog]["id"]))

    planned_orders = {(catalog_id, product.slug): product.order for product, _, catalog_id in work}
    for product, _, catalog_id in work:
        for member in memberships[catalog_id].values():
            if member["slug"] == product.slug or member["displayOrder"] != product.order:
                continue
            if planned_orders.get((catalog_id, member["slug"]), member["displayOrder"]) == product.order:
                raise ImportFailure(f"{product.slug}: порядок {product.order} в {product.catalog} занят товаром {member['slug']}")

    for product, existing, catalog_id in work:
        try:
            print(f"Импорт {product.slug}", flush=True)
            form = product.with_images([journal.image_id(api, item) for item in product.photos])
            if existing is None:
                current = api.call(PRODUCTS, "POST", form, expected=201)
                journal.products[product.slug] = current["id"]
                journal.save()
            else:
                route = f"{PRODUCTS}/{existing['id']}"
                current = api.call(route)
                if current["published"]:
                    raise ImportFailure("Карточку опубликовали во время импорта")
                if not form["measurements"]:
                    form["measurements"] = current["measurements"]
                if product_body(current) != form:
                    current = api.call(route, "PUT", form)
            product_id = current["id"]
            apply_variants(api, f"{PRODUCTS}/{product_id}/variants", current["variants"], product.variants)
            placement = memberships[catalog_id].get(product.slug)
            if placement is None or placement["displayOrder"] != product.order:
                api.call(f"{CATALOGS}/{catalog_id}/products/{product_id}", "PUT",
                         {"displayOrder": product.order}, expected=204)
            print(f"  Черновик {api.base}/admin/products/{product_id}", flush=True)
        except ImportFailure as error:
            raise ImportFailure(f"{product.slug}: {error}") from error


def catalog_products(api, catalog):
    return {item["slug"]: item for item in api.pages(f"{CATALOGS}/{catalog['id']}", "products")}


def publish_products(api, journal, products, catalogs):
    memberships = {key: catalog_products(api, catalogs[key]) for key in sorted({item.catalog for item in products})}
    work = []
    for product in products:
        product_id = journal.products.get(product.slug)
        placement = memberships[product.catalog].get(product.slug)
        if not product_id or not placement or placement["id"] != product_id:
            raise ImportFailure(f"{product.slug}: в коллекции нет карточки из журнала импорта")
        if placement["displayOrder"] != product.order:
            raise ImportFailure(f"{product.slug}: порядок на сервере отличается. Повторите import")
        route = f"{PRODUCTS}/{product_id}"
        detail = api.call(route)
        saved = product_body(detail)
        expected = product.with_images([journal.images.get(item.digest) for item in product.photos])
        expected["published"] = saved["published"]
        if not expected["measurements"]:
            expected["measurements"] = saved["measurements"]
        variants = [{key: value for key, value in row.items() if key != "id"} for row in detail["variants"]]
        if saved != expected or sorted(variants, key=lambda row: row["sku"]) != sorted(product.variants, key=lambda row: row["sku"]):
            raise ImportFailure(f"{product.slug}: карточка или варианты отличаются от таблицы. Сверьте данные в админке")
        work.append((product, route, detail))
    for product, route, detail in work:
        if not detail["published"]:
            if api.call(route) != detail:
                raise ImportFailure(f"{product.slug}: карточка изменилась во время проверки")
            api.call(route, "PUT", dict(product_body(detail), published=True))
        api.call(f"/api/catalogs/{product.catalog}/products/{product.slug}")
        print(f"Товар {product.slug} доступен на {api.base}/catalog/{product.catalog}", flush=True)


def storefront_form(current):
    hero = current["heroImage"]
    form = {key: current[key] for key in (
        "version", "published", "heroTitle", "photoOrderPriceHint", "photoOrderTimingHint", "deliveryPriceHint",
    )}
    form["heroImageId"] = hero["id"] if hero else None
    form["gallery"] = [dict(embroideryImageId=item["embroidery"]["id"],
                            sourceImageId=item["source"]["id"] if item["source"] else None,
                            caption=item["caption"]) for item in current["gallery"]]
    return form


def gallery_additions(form, examples, lookup):
    existing = {item["embroideryImageId"]: item for item in form["gallery"]}
    additions = []
    for example in examples:
        item = dict(embroideryImageId=lookup(example.embroidery),
                    sourceImageId=lookup(example.source) if example.source else None, caption=example.caption)
        saved = existing.get(item["embroideryImageId"])
        if not saved:
            additions.append(item)
        elif item != saved:
            raise ImportFailure(f"Пример {example.embroidery.relative} уже есть в галерее с другими данными")
    return additions


def import_gallery(api, journal, examples):
    form = storefront_form(api.call(STOREFRONT))
    planned = gallery_additions(form, examples, lambda item: journal.images.get(item.digest, item.digest))
    if len(form["gallery"]) + len(planned) > 24:
        raise ImportFailure("Вместе с текущими примерами получится больше 24 работ. Уменьшите список в таблице")
    if planned:
        for example in examples:
            journal.image_id(api, example.embroidery)
            if example.source:
                journal.image_id(api, example.source)
        form["gallery"].extend(gallery_additions(form, examples, lambda item: journal.images[item.digest]))
        api.call(STOREFRONT, "PUT", form)
    print(f"Добавлено примеров: {len(planned)}. Всего в галерее: {len(form['gallery'])}", flush=True)


def arguments():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("operation", choices=("check", "import", "publish", "gallery"))
    parser.add_argument("--workbook", type=Path, required=True, help="путь к заполненному XLSX")
    parser.add_argument("--photos", type=Path, required=True, help="путь к папке photos распакованного пакета")
    parser.add_argument("--url", help="HTTPS-адрес Shop")
    parser.add_argument("--email", help="рабочий email сотрудника")
    parser.add_argument("--state", type=Path, help="путь к журналу импорта для этого сервера")
    return parser.parse_args()


def run(args):
    products, examples = read_inputs(args)
    to_publish = [item for item in products if item.publish]
    if args.operation == "check":
        print(f"Проверено: карточек {len(products)}, к публикации {len(to_publish)}, "
              f"{sum(len(item.photos) for item in products)} фото, "
              f"{sum(len(item.variants) for item in products)} вариантов, {len(examples)} примеров галереи")
        for item in products:
            if not item.variants:
                print(f"  {item.slug}: можно создать черновик без вариантов")
        return
    if not (products or examples):
        raise ImportFailure("В таблице нет строк, отмеченных для этой команды")
    if not args.url or not args.state:
        raise ImportFailure("Для записи укажите адрес --url и журнал --state")
    base = origin(args.url)
    if args.state.resolve() == args.workbook.resolve() or args.state.resolve().is_relative_to(args.photos.resolve()):
        raise ImportFailure("Сохраните журнал отдельно от таблицы и папки photos")
    print(f"Адрес Shop {base}", flush=True)
    with locked_journal(args.state, base) as journal:
        api = Shop(base)
        api.login(args.email)
        if args.operation == "gallery":
            import_gallery(api, journal, examples)
            return
        catalogs = {item["slug"]: item for item in api.pages(CATALOGS)}
        targets = {item.catalog for item in products}
        if missing := targets - catalogs.keys():
            raise ImportFailure(f"Коллекции отсутствуют на сервере: {', '.join(sorted(missing))}")
        unpublished = {item.catalog for item in to_publish if not catalogs[item.catalog]["published"]}
        if unpublished:
            raise ImportFailure(f"Сначала опубликуйте коллекции: {', '.join(sorted(unpublished))}")
        if args.operation == "import":
            memberships = {catalogs[key]["id"]: catalog_products(api, catalogs[key]) for key in sorted(targets)}
            listings = {item["slug"]: item for item in api.pages(PRODUCTS)}
            import_products(api, journal, products, listings, catalogs, memberships)
        if to_publish:
            publish_products(api, journal, to_publish, catalogs)


def main():
    try:
        run(arguments())
    except (ImportFailure, OSError, ValueError, BadZipFile, InvalidFileException, getpass.GetPassWarning, KeyError, EOFError) as error:
        print(f"Команда остановлена. {error}", file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        print("\nИмпорт прерван. Для продолжения используйте тот же журнал", file=sys.stderr)
        return 130
    return 0


if __name__ == "__main__":
    sys.exit(main())
