#!/usr/bin/env python3
"""Publish and update the KNITSEW storefront from a workbook and photo pack."""

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
from contextlib import closing, contextmanager
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
CATALOG_SHEET = "Коллекции"
STOREFRONT_SHEET = "Витрина"
PRODUCT_HEADERS = (
    "Название", "Импорт", "Публикация", "Группа вариантов", "Тип изделия", "Дизайн", "Папка фото",
    "Slug", "Описание для покупателя", "Порядок", "Коллекция", "Заметка по фото", "Источник Instagram",
)
GROUP_HEADERS = ("Код группы", "Цвет", "Размеры", "Цена, ₽", "Наличие", "Срок, дней", "SKU")
CATALOG_HEADERS = ("Slug", "Название", "Тип", "Порядок", "Публикация", "Описание", "Фото обложки")
STOREFRONT_HEADERS = (
    "Публикация", "Заголовок", "Фото баннера", "Цена вышивки по фото", "Срок вышивки по фото", "Доставка из коллекции",
)
GALLERY_HEADERS = ("Добавить", "Подпись", "Фото вышивки", "Исходник")
MEASUREMENT_HEADERS = ("Slug товара", "Размер", "Ширина, см", "Длина, см", "Рукав, см")
SLUG = re.compile(r"[a-z0-9]+(?:-[a-z0-9]+)*")
SKU = re.compile(r"[A-Z0-9][A-Z0-9_-]*")
PHOTO_NAME = re.compile(r"(\d{2})-[a-z0-9-]+\.(?:jpe?g|png)")
SKU_LIMIT = 64
PRICE_LIMIT = 9_007_199_254_740_991
AVAILABILITY = {"В наличии": "IN_STOCK", "Под заказ": "MADE_TO_ORDER", "Нет в наличии": "OUT_OF_STOCK"}
CATALOG_KINDS = {"Коллекция": "COLLECTION", "Подборка": "SELECTION"}
IMAGE_FORMATS = {"JPEG": "image/jpeg", "PNG": "image/png"}
IMAGE_EXTENSIONS = {".jpg": "JPEG", ".jpeg": "JPEG", ".png": "PNG"}
PHOTO_CAPTIONS = {
    "studio": "Студийная визуализация на основе фото изделия",
    "cover": None,
    "original": "Фото изделия",
    "detail": "Вышивка крупным планом",
    "alternate": "Дополнительный ракурс",
}


class ImportFailure(Exception):
    pass


class ShopHttpError(ImportFailure):
    def __init__(self, method, route, status, detail):
        super().__init__(f"{method} {route}: HTTP {status}. {detail}".rstrip())
        self.status = status


@dataclass(frozen=True)
class Photo:
    path: Path
    relative: str
    digest: str
    media_type: str

    @property
    def role(self):
        return self.path.stem.split("-", 1)[1]


@dataclass(frozen=True)
class Product:
    form: dict
    photos: list[Photo]
    variants: list[dict]
    catalogs: tuple[str, ...]
    order: int
    publish: bool

    @property
    def slug(self):
        return self.form["slug"]

    def with_images(self, image_ids):
        images = [{"id": image_id, "caption": PHOTO_CAPTIONS[item.role]}
                  for item, image_id in zip(self.photos, image_ids, strict=True)]
        return dict(self.form, images=images)


@dataclass(frozen=True)
class Catalog:
    form: dict
    cover: Photo | None


@dataclass(frozen=True)
class Storefront:
    form: dict
    banner: Photo | None


@dataclass(frozen=True)
class Example:
    embroidery: Photo
    source: Photo | None
    caption: str | None


@dataclass(frozen=True)
class ImportPackage:
    products: list[Product]
    catalogs: dict[str, Catalog]
    storefront: Storefront
    gallery: list[Example]


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
    first = ["01-studio", "02-original", "03-detail"] if stems[:1] == ["01-studio"] else ["01-cover", "02-detail"]
    if stems[:len(first)] != first or len(photos) > 12 or any(item.role != "alternate" for item in photos[len(first):]):
        raise ImportFailure(f"В {folder.name} нужны 01-studio, 02-original, 03-detail или 01-cover, 02-detail. "
                            "Прочие фото обозначьте alternate. Максимум 12 фото")
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
    for row, (group, color, sizes, price, availability, days, sku) in rows(book, GROUP_SHEET, GROUP_HEADERS):
        try:
            group = string(group, "Код группы", 80)
            color = string(color, "Цвет", 80)
            if type(sizes) in (int, float) and math.isfinite(sizes) and sizes == int(sizes):
                sizes = str(int(sizes))
            sizes = [string(size, "Размер", 32) for size in string(sizes, "Размеры", 1500).split(",")]
            sku = string(sku, "SKU", SKU_LIMIT, optional=True)
            if sku and (len(sizes) != 1 or not SKU.fullmatch(sku)):
                raise ImportFailure("Явный SKU задаётся для одного размера. Допустимы A–Z, цифры, дефис и подчёркивание")
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
                dict(size=size, color=color, priceKopecks=kopecks, availability=status, productionDays=days, sku=sku)
                for size in sizes
            )
        except ImportFailure as error:
            problems.append(f"{GROUP_SHEET}, строка {row}: {error}")
    return groups


def variant_payload(product_slug, variant):
    if variant["sku"]:
        return variant
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


def parse_products(book, root, problems):
    groups = variant_groups(book, problems)
    measurements = product_measurements(book, problems)
    products = []
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
            key = slug(key, "Slug")
            if key in known_slugs:
                raise ImportFailure(f"Повторяется slug {key}")
            known_slugs.add(key)
            if not include:
                continue
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
            catalogs = tuple(slug(value, "Коллекция") for value in
                             string(catalog, "Коллекция", 1500).split(","))
            if len(set(catalogs)) != len(catalogs):
                raise ImportFailure("Коллекция указана несколько раз")
            order = integer(order, "Порядок", 0)
            for catalog in catalogs:
                if (catalog, order) in seen_orders:
                    raise ImportFailure(f"Повторяется место {order} в коллекции {catalog}")
                seen_orders.add((catalog, order))
            photos = product_photos(root, folder)
            products.append(Product(form, photos, variants, catalogs, order, publish))
        except ImportFailure as error:
            problems.append(f"{PRODUCT_SHEET}, строка {row}: {error}")
    if unknown := measurements.keys() - known_slugs:
        problems.append(f"{MEASUREMENT_SHEET}: в товарах отсутствуют slug {', '.join(sorted(unknown))}")
    return products


def parse_catalogs(book, root, problems):
    catalogs = {}
    for row, (key, name, kind, order, published, description, cover) in rows(book, CATALOG_SHEET, CATALOG_HEADERS):
        try:
            key = slug(key, "Slug")
            if key in catalogs:
                raise ImportFailure(f"Повторяется slug {key}")
            if kind not in CATALOG_KINDS:
                raise ImportFailure("Тип: выберите «Коллекция» или «Подборка»")
            form = dict(
                slug=key, name=string(name, "Название", 160), kind=CATALOG_KINDS[kind],
                displayOrder=integer(order, "Порядок", 0), published=choice(published, "Публикация"),
                description=string(description, "Описание", 4000, optional=True),
            )
            image = photo(root, checked_path(root, cover, "Фото обложки")) if cover else None
            catalogs[key] = Catalog(form, image)
        except ImportFailure as error:
            problems.append(f"{CATALOG_SHEET}, строка {row}: {error}")
    return catalogs


def parse_storefront(book, root, problems):
    settings = list(rows(book, STOREFRONT_SHEET, STOREFRONT_HEADERS))
    if len(settings) != 1:
        problems.append(f"Лист «{STOREFRONT_SHEET}»: заполните одну строку настроек")
        return None
    row, (published, title, banner, price_hint, timing_hint, delivery_hint) = settings[0]
    try:
        published = choice(published, "Публикация")
        if published and not banner:
            raise ImportFailure("Для публикации укажите фото баннера")
        form = dict(
            published=published, heroTitle=string(title, "Заголовок", 120, optional=not published) or None,
            photoOrderPriceHint=string(price_hint, "Цена вышивки по фото", 160, optional=True) or None,
            photoOrderTimingHint=string(timing_hint, "Срок вышивки по фото", 160, optional=True) or None,
            deliveryPriceHint=string(delivery_hint, "Доставка из коллекции", 160, optional=True) or None,
        )
        image = photo(root, checked_path(root, banner, "Фото баннера")) if banner else None
        return Storefront(form, image)
    except ImportFailure as error:
        problems.append(f"{STOREFRONT_SHEET}, строка {row}: {error}")
        return None


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
        problems.append("Галерея: допустимо до 24 выбранных примеров")
    return examples


def read_inputs(args):
    root = args.photos.resolve(strict=True)
    if not root.is_dir():
        raise ImportFailure(f"Укажите распакованную папку photos: {root}")
    problems = []
    with closing(load_workbook(args.workbook, read_only=True)) as book:
        products = parse_products(book, root, problems)
        catalogs = parse_catalogs(book, root, problems)
        storefront = parse_storefront(book, root, problems)
        gallery = parse_gallery(book, root, problems)
    for product in products:
        for key in product.catalogs:
            if key not in catalogs:
                problems.append(f"{product.slug}: коллекция {key} отсутствует на листе «{CATALOG_SHEET}»")
    if problems:
        raise ImportFailure("\n".join(problems))
    if storefront is None:
        raise ImportFailure("В пакете отсутствуют настройки витрины")
    return ImportPackage(products, catalogs, storefront, gallery)


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

    def call(self, route, method="GET", value=None, *, expected=200, data=None, content_type=None, image=False):
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
                if expected in (202, 204):
                    return None
                if image:
                    if response.headers.get_content_type() not in IMAGE_FORMATS.values():
                        raise ImportFailure(f"{method} {route}: ожидалось изображение")
                    return None
                if response.headers.get_content_type() != "application/json":
                    raise ImportFailure(f"{method} {route}: сервер вернул ответ другого формата")
                return json.load(response)
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
            raise ShopHttpError(method, route, error.code, detail) from None
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

    def image_exists(self, image_id):
        try:
            self.call(f"{IMAGES}/{image_id}", image=True)
        except ShopHttpError as error:
            if error.status == 404:
                return False
            raise
        return True

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


def catalog_body(current):
    body = {key: current[key] for key in ("slug", "name", "kind", "displayOrder", "published", "description")}
    body["coverImageId"] = current["cover"]["id"] if current["cover"] else None
    return body


def catalog_products(api, catalog):
    return {item["slug"]: item for item in api.pages(f"{CATALOGS}/{catalog['id']}", "products")}


def check_product_ids(journal, existing):
    for key, saved_id in journal.products.items():
        current = existing.get(key)
        if current and current["id"] != saved_id:
            raise ImportFailure(f"{key}: на сервере другой ID товара. Проверьте журнал импорта")


def check_placements(products, memberships, removed):
    managed = {item.slug for item in products} | removed
    occupied = {(key, item["displayOrder"]): item["slug"] for key, members in memberships.items()
                for item in members.values() if item["slug"] not in managed and item["published"]}
    for product in products:
        for key in product.catalogs:
            if other := occupied.get((key, product.order)):
                raise ImportFailure(f"{product.slug}: порядок {product.order} в коллекции {key} занят товаром {other}")


def prepare_images(api, journal, package):
    photos = [item for product in package.products for item in product.photos]
    photos.extend(catalog.cover for catalog in package.catalogs.values() if catalog.cover)
    if package.storefront.banner:
        photos.append(package.storefront.banner)
    for example in package.gallery:
        photos.append(example.embroidery)
        if example.source:
            photos.append(example.source)
    unique_photos = {item.digest: item for item in photos}

    # Check cached IDs before writing products. Missing photos can be uploaded again.
    for item in unique_photos.values():
        cached_id = journal.images.get(item.digest)
        if cached_id and not api.image_exists(cached_id):
            del journal.images[item.digest]
        journal.image_id(api, item)


def upsert_catalogs(api, journal, catalogs, existing):
    resolved = {}
    for key, catalog in catalogs.items():
        form = dict(catalog.form, coverImageId=journal.images[catalog.cover.digest] if catalog.cover else None)
        if key not in existing:
            saved = api.call(CATALOGS, "POST", form, expected=201)
        elif catalog_body(existing[key]) != form:
            saved = api.call(f"{CATALOGS}/{existing[key]['id']}", "PUT", form)
        else:
            saved = existing[key]
        if catalog_body(saved) != form:
            raise ImportFailure(f"{key}: сервер сохранил другие настройки коллекции")
        resolved[key] = saved
    return resolved


def variant_data(items):
    return {item["sku"]: {key: value for key, value in item.items() if key != "id"} for item in items}


def variant_slot(item):
    return item["size"].lower(), item["color"].lower()


def plan_variants(current, wanted):
    current_by_sku = {item["sku"]: item for item in current}
    current_by_slot = {variant_slot(item): item for item in current}
    planned = []
    taken = set()
    for variant in wanted:
        saved = current_by_sku.get(variant["sku"])
        by_slot = current_by_slot.get(variant_slot(variant))
        # Keep an existing ID when a size/color match receives a different SKU.
        if saved is None:
            saved = by_slot
        if saved and (saved["id"] in taken or (by_slot and by_slot["id"] != saved["id"])):
            raise ImportFailure(f"{variant['sku']}: SKU и размер с цветом относятся к разным вариантам")
        planned.append((variant, saved))
        if saved:
            taken.add(saved["id"])
    return planned, [item for item in current if item["id"] not in taken]


def sync_variants(api, route, plan):
    planned, removed = plan
    for variant, saved in planned:
        if not saved:
            api.call(route, "POST", variant, expected=201)
        elif any(saved[key] != value for key, value in variant.items()):
            api.call(f"{route}/{saved['id']}", "PUT", variant)
    for item in removed:
        api.call(f"{route}/{item['id']}", "DELETE", expected=204)


def update_product(api, journal, product, current, variant_plan, catalogs, memberships):
    form = product.with_images([journal.images[item.digest] for item in product.photos])
    if current is None:
        current = api.call(PRODUCTS, "POST", form, expected=201)
    product_id = current["id"]
    if journal.products.get(product.slug) != product_id:
        journal.products[product.slug] = product_id
        journal.save()
    route = f"{PRODUCTS}/{product_id}"
    desired = dict(form, published=product.publish)
    # On an already visible card, keep it published during changes to its content.
    update = dict(form, published=current["published"] and product.publish)
    if product_body(current) != update:
        current = api.call(route, "PUT", update)
    sync_variants(api, f"{route}/variants", variant_plan)
    for key, catalog in catalogs.items():
        placement = memberships[key].get(product.slug)
        placement_route = f"{CATALOGS}/{catalog['id']}/products/{product_id}"
        if key in product.catalogs:
            if placement is None or placement["displayOrder"] != product.order:
                api.call(placement_route, "PUT", {"displayOrder": product.order}, expected=204)
        elif placement is not None:
            api.call(placement_route, "DELETE", expected=204)

    saved = api.call(route)
    if saved["published"] != product.publish:
        saved = api.call(route, "PUT", desired)
    if product_body(saved) != desired or variant_data(saved["variants"]) != variant_data(product.variants):
        raise ImportFailure("карточка или варианты после сохранения отличаются от таблицы")


def verify_catalogs(api, journal, products, catalogs, removed):
    for key, catalog in catalogs.items():
        actual = catalog_products(api, catalog)
        for product in products:
            placement = actual.get(product.slug)
            if key in product.catalogs:
                if not placement or placement["id"] != journal.products[product.slug] or placement["displayOrder"] != product.order:
                    raise ImportFailure(f"{product.slug}: размещение в коллекции {key} отличается от таблицы")
            elif placement:
                raise ImportFailure(f"{product.slug}: лишняя привязка к коллекции {key}")
        if retired := removed & actual.keys():
            raise ImportFailure(f"{key}: сохранены привязки снятых с публикации товаров {', '.join(sorted(retired))}")
        if catalog["published"]:
            summary = {item["slug"]: item for item in api.pages(f"/api/catalogs/{key}", "products")}
            for product in products:
                should_show = product.publish and key in product.catalogs
                if (product.slug in summary) != should_show:
                    raise ImportFailure(f"{product.slug}: неверная видимость в коллекции {key}")
                if should_show and summary[product.slug]["displayOrder"] != product.order:
                    raise ImportFailure(f"{product.slug}: неверный порядок на витрине в коллекции {key}")


def retire_products(api, journal, removed, existing, catalogs, memberships):
    for key in sorted(removed):
        if key in existing:
            route = f"{PRODUCTS}/{existing[key]['id']}"
            current = product_body(api.call(route))
            if current["published"]:
                api.call(route, "PUT", dict(current, published=False))
                print(f"Снят с публикации {key}", flush=True)
            for catalog_key, catalog in catalogs.items():
                if key in memberships[catalog_key]:
                    api.call(f"{CATALOGS}/{catalog['id']}/products/{existing[key]['id']}", "DELETE", expected=204)
        else:
            del journal.products[key]
            journal.save()


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


def update_storefront(api, journal, storefront, gallery, current):
    before = storefront_form(current)
    form = dict(storefront.form, version=before["version"])
    form["heroImageId"] = journal.images[storefront.banner.digest] if storefront.banner else None
    form["gallery"] = [
        dict(embroideryImageId=journal.images[item.embroidery.digest],
             sourceImageId=journal.images[item.source.digest] if item.source else None, caption=item.caption)
        for item in gallery
    ]
    if form != before:
        saved = storefront_form(api.call(STOREFRONT, "PUT", form))
        form["version"] = saved["version"]
        if saved != form:
            raise ImportFailure("Сервер сохранил другие настройки витрины")
    if form["published"] and storefront_form(api.call("/api/storefront")) != form:
        raise ImportFailure("Данные на главной отличаются от таблицы")


def import_package(api, journal, package):
    existing = {item["slug"]: item for item in api.pages(PRODUCTS)}
    existing_catalogs = {item["slug"]: item for item in api.pages(CATALOGS)}
    memberships = {key: catalog_products(api, existing_catalogs[key]) if key in existing_catalogs else {}
                   for key in package.catalogs}
    current_storefront = api.call(STOREFRONT)
    removed = journal.products.keys() - {item.slug for item in package.products}
    check_product_ids(journal, existing)
    check_placements(package.products, memberships, removed)

    work = []
    for product in package.products:
        present = existing.get(product.slug)
        detail = api.call(f"{PRODUCTS}/{present['id']}") if present else None
        try:
            variant_plan = plan_variants(detail["variants"] if detail else [], product.variants)
        except ImportFailure as error:
            raise ImportFailure(f"{product.slug}: {error}") from error
        work.append((product, detail, variant_plan))

    prepare_images(api, journal, package)

    catalogs = upsert_catalogs(api, journal, package.catalogs, existing_catalogs)
    retire_products(api, journal, removed, existing, catalogs, memberships)
    for product, detail, variant_plan in work:
        try:
            update_product(api, journal, product, detail, variant_plan, catalogs, memberships)
        except ImportFailure as error:
            raise ImportFailure(f"{product.slug}: {error}") from error

    verify_catalogs(api, journal, package.products, catalogs, removed)
    update_storefront(api, journal, package.storefront, package.gallery, current_storefront)
    print(f"Сверено: товаров {len(package.products)}, коллекций {len(catalogs)}, примеров галереи {len(package.gallery)}", flush=True)


def arguments():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("operation", choices=("check", "import"))
    parser.add_argument("--workbook", type=Path, required=True, help="путь к заполненному XLSX")
    parser.add_argument("--photos", type=Path, required=True, help="путь к папке photos распакованного пакета")
    parser.add_argument("--url", help="HTTPS-адрес Shop")
    parser.add_argument("--email", help="рабочий email сотрудника")
    parser.add_argument("--state", type=Path, help="путь к журналу импорта для этого сервера")
    return parser.parse_args()


def run(args):
    package = read_inputs(args)
    if args.operation == "check":
        products = package.products
        print(f"Проверено: товаров {len(products)}, к публикации {sum(p.publish for p in products)}, "
              f"{sum(len(p.photos) for p in products)} фото в карточках, "
              f"{sum(len(p.variants) for p in products)} вариантов, "
              f"{len(package.catalogs)} коллекций, {len(package.gallery)} примеров галереи")
        return
    if not args.url or not args.state:
        raise ImportFailure("Для записи укажите адрес --url и журнал --state")
    base = origin(args.url)
    if args.state.resolve() == args.workbook.resolve() or args.state.resolve().is_relative_to(args.photos.resolve()):
        raise ImportFailure("Сохраните журнал отдельно от таблицы и папки photos")
    print(f"Адрес Shop {base}", flush=True)
    with locked_journal(args.state, base) as journal:
        api = Shop(base)
        api.login(args.email)
        import_package(api, journal, package)


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
