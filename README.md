# Shop

Backend фабрики кастомной вышивки. API поддерживает два сценария заказа, ручной расчёт доставки до ПВЗ СДЭК, оплату через тестовый магазин ЮKassa, переписку и настройки витрины.

[Требования к Shop](docs/requirements.md) описывают оба сценария заказа, первый полноценный релиз и архитектуру.

## Локальный запуск

Понадобятся JDK 21, Docker с Compose v2 и Python 3 для сквозной проверки.

~~~sh
./backend/gradlew -p backend --no-daemon check bootJar
docker compose --env-file .env.example -f compose.yaml -f compose.local.yaml -f infra/tests/compose.yaml up --build --wait
python3 infra/tests/smoke.py
~~~

- API доступен через [Nginx](http://127.0.0.1:18080/api/csrf), коды и уведомления попадают в [Mailpit](http://127.0.0.1:18025). Тестовая учётная запись сотрудника — staff@example.test.
- Скрипт создаёт тестовые заказы и запрашивает коды для вымышленных адресов. Повторный запуск под той же почтой сотрудника возможен через минуту из-за ограничения выдачи кодов.
- Для остановки выполните `docker compose --env-file .env.example -f compose.yaml -f compose.local.yaml -f infra/tests/compose.yaml down`. Тома сохраняются. Базу с тестовыми заказами по старой схеме 50/50 нужно пересоздать в рамках миграции на один платёж. Используйте `down --volumes` только для своего временного тестового стенда.

## Заказы

| Действие | HTTP |
| --- | --- |
| Получить токен для изменения данных | `GET /api/csrf` |
| Создать черновик заказа по фото с email, изделием, размером и цветом | `POST /api/orders/drafts` |
| Создать черновик заказа из каталога с `email`, `catalogSlug`, `variantId` и `delivery` | `POST /api/orders/catalog-drafts` |
| Запросить и подтвердить код для черновика или действующего заказа | `POST /api/orders/{id}/access/code` и `POST /api/orders/{id}/access/verify` |
| Загрузить фото, эскиз или вложение | `POST /api/orders/{id}/files?purpose=SOURCE\|SKETCH\|CHAT`, поле multipart `file` |
| Передать заказ менеджеру после входа по коду. Для заказа по фото сначала загрузить исходник | `POST /api/orders/{id}/submit` |
| Открыть заказ или скачать доступный файл | `GET /api/orders/{id}` или `GET /api/orders/{id}/files/{fileId}` |
| Указать или обновить данные получателя и ПВЗ | `PUT /api/orders/{id}/delivery` |
| Войти сотруднику | `POST /api/staff/access/code` и `POST /api/staff/access/verify` |
| Открыть очередь или карточку заказа сотрудником | `GET /api/staff/orders` и `GET /api/staff/orders/{id}` |
| Оценить заказ по фото | `POST /api/staff/orders/{id}/quote` с `itemKopecks` и `productionDays` |
| Подтвердить ПВЗ и выставить общую сумму с доставкой | `POST /api/staff/orders/{id}/delivery-quote` |
| Отклонить заявку при рассмотрении | `POST /api/staff/orders/{id}/reject` с `reason` |
| Получить платёжную ссылку или сверить платёж после возврата на сайт | `POST /api/orders/{id}/payment/checkout` и `POST /api/orders/{id}/payment/sync` |
| Начать отмену заказа до отправки | `POST /api/staff/orders/{id}/cancel` с `reason` |
| Вернуть полную оплату при отмене или после отправки | `POST /api/staff/orders/{id}/refund` |
| Открыть историю суммы, отмены, отправки и возврата | `GET /api/staff/orders/{id}/history` с `page` и `size` |
| Опубликовать эскиз по `fileId` | `POST /api/staff/orders/{id}/sketches` |
| Согласовать версию эскиза или запросить правки | `POST /api/orders/{id}/sketches/{sketchId}/approve` или `POST /api/orders/{id}/sketches/{sketchId}/request-changes` |
| Отметить готовность и после полной оплаты указать `trackingNumber` | `POST /api/staff/orders/{id}/production-complete` и `POST /api/staff/orders/{id}/shipment` |
| Выйти из сессии | `POST /api/session/logout` |

Запросы на изменение передают cookie сессии и CSRF-токен в заголовке, имя и значение которого возвращает `GET /api/csrf`. После входа нужно получить свежий токен. Код действует 10 минут, доступ покупателя ограничен одним заказом. Файлы JPEG и PNG до 10 МиБ проходят через backend и хранятся в приватном S3-совместимом бакете.

Очередь сотрудника показывает поданные заявки и возвращает `items`, `page`, `size`, `hasMore`. Параметры `page` и `size` по умолчанию равны 0 и 24, максимальный размер страницы — 100. `type` фильтрует по `CUSTOM_PHOTO` или `CATALOG`, `status` — по этапу, `search` ищет по полному UUID заказа или email без учёта регистра.

При создании черновика из каталога backend проверяет публикацию коллекции и товара, принадлежность варианта коллекции и его доступность. Карточка хранит выбранные размер, цвет, цену изделия в `order.itemKopecks` и сведения о товаре в `catalogItem`. Цена и SKU фиксируются при создании черновика.

Для доставки покупатель указывает `recipientName`, `phone` в международном формате с плюсом, `city` и хотя бы одно из полей `pickupPointCode` и `pickupPointAddress`. Менеджер передаёт оба поля ПВЗ, `priceKopecks`, `deliveryDays` и `deliveryVersion` из `delivery.version`. При обновлении данных покупателем версия меняется. Попытка применить старый расчёт или провести действие вне допустимого этапа возвращает HTTP `409`.

Карточка возвращает `delivery`, `totalKopecks` и единственный `payment` после расчёта доставки. Сумма покрывает изделие и перевозку. Подтверждение платежа открывает подготовку заказа из каталога или эскиза для вышивки по фото. [Этапы обоих типов заказа](docs/requirements.md#статусная-модель)

Чат доступен после отправки черновика. `GET /api/orders/{id}/messages` возвращает последние сообщения и `lastSequence`, параметр `before` открывает предыдущую страницу. Отправка идёт через `POST /api/orders/{id}/messages` с `clientMessageId` для безопасного повтора. Подписка `GET /api/orders/{id}/messages/stream?after={lastSequence}` передаёт события `message.created`. При переподключении сервер использует заголовок `Last-Event-ID`, если браузер его передал.

### Тестовый магазин ЮKassa

Compose из `infra/tests/compose.yaml` запускает локальный имитатор. Для проверки с ЮKassa запустите обычные `compose.yaml` и `compose.local.yaml`. В защищённом файле окружения задайте `SHOP_YOOKASSA_ENABLED=true`, `SHOP_YOOKASSA_SHOP_ID`, `SHOP_YOOKASSA_SECRET_KEY` тестового магазина и `SHOP_PUBLIC_ORIGIN` стенда. В кабинете тестового магазина укажите HTTPS-адрес `/api/payments/yookassa/webhook` и события `payment.succeeded`, `payment.canceled`, `refund.succeeded`. URL возврата ведёт на будущую карточку `/orders/{id}`. Для приёма реальных денег потребуется настроить кассу и чеки. [Инструкция ЮKassa](https://yookassa.ru/developers/payment-acceptance/testing-and-going-live/testing)

Менеджер может сверить открытый платёж и состояние возврата через `payment/sync`. При отмене с открытой ссылкой заказ ждёт результата платежа. Если деньги поступили, менеджер оформляет полный возврат. Если провайдер ещё обрабатывает возврат, карточка показывает его состояние. Повтор запроса после неопределённого ответа использует сохранённый ключ в пределах 23 часов, затем требуется сверка в кабинете. [Идемпотентность ЮKassa](https://yookassa.ru/developers/using-api/interaction-format)

## Настройки главной

Опубликованные настройки доступны через `GET /api/storefront`. Сотрудник использует `GET` и `PUT /api/staff/storefront`. Запись заменяет настройки целиком и принимает `version`, `published`, `heroTitle`, `heroImageId` и массив `gallery`. У примера обязательное `embroideryImageId` и необязательные `sourceImageId` и `caption`. Порядок массива задаёт порядок показа. Изображения загружаются через `POST /api/staff/catalog-images`. Устаревшая версия настроек возвращает HTTP `409`, неопубликованная витрина публично возвращает `404`.

## Каталоги

После миграции в PostgreSQL доступны пустые «Выбор команды» (`team-choice`, тип `SELECTION`) и «Русь» (`rus`, тип `COLLECTION`). Порядок показа коллекций задаёт `displayOrder`. Каталоги используют общие маршруты, а один товар можно разместить в нескольких каталогах.

| Публичный API | Результат |
| --- | --- |
| `GET /api/catalogs` | Опубликованные каталоги с типом, описанием, обложкой и порядком |
| `GET /api/catalogs/{slug}` | Объект `catalog` и страница `products` с названием, обложкой и ценой от для каждого товара |
| `GET /api/catalogs/{slug}/products/{productSlug}` | Опубликованный товар в выбранном каталоге, упорядоченные фото и варианты с ID, SKU, размером, цветом, ценой и наличием |

Списки принимают `page` с нуля и `size` от 1 до 100 (по умолчанию 24). Страница возвращает `items`, `page`, `size` и `hasMore`. Суммы в копейках. Поля `cover` и `images` содержат ссылки вида `{id, url}`. Фото выдаются по указанному `url` и доступны покупателю, пока опубликован хотя бы один связанный каталог или товар в опубликованном каталоге.

| API сотрудников | Действие |
| --- | --- |
| `GET /api/staff/catalogs`, `GET /api/staff/catalogs/{id}` | Посмотреть каталоги и привязанные товары, включая черновики |
| `POST /api/staff/catalogs`, `PUT /api/staff/catalogs/{id}` | Создать или заменить каталог. Поля `slug`, `name`, `kind`, `description`, `coverImageId`, `displayOrder`, `published` |
| `PUT /api/staff/catalogs/{id}/products/{productId}`, `DELETE /api/staff/catalogs/{id}/products/{productId}` | Привязать товар с полем `displayOrder`, изменить порядок или убрать товар |
| `GET /api/staff/products`, `GET /api/staff/products/{id}` | Посмотреть товары, включая черновики |
| `POST /api/staff/products`, `PUT /api/staff/products/{id}` | Создать черновик или заменить товар. Поля `slug`, `name`, `design`, `garmentType`, `description`, `imageIds`, `published` |
| `POST /api/staff/products/{id}/variants`, `PUT /api/staff/products/{id}/variants/{variantId}`, `DELETE /api/staff/products/{id}/variants/{variantId}` | Создать, изменить или удалить вариант. Поля `sku`, `size`, `color`, `priceKopecks`, `availability`, `productionDays` |
| `POST /api/staff/catalog-images`, `GET /api/staff/catalog-images/{id}`, `DELETE /api/staff/catalog-images/{id}` | Загрузить JPEG или PNG до 10 МиБ в поле multipart `file`, открыть фото для предпросмотра или удалить его после снятия всех привязок |

Для публикации товара нужны хотя бы одно фото и один вариант. Первое фото в `imageIds` становится обложкой. Вариант имеет наличие `IN_STOCK`, `MADE_TO_ORDER` или `OUT_OF_STOCK`. Для `MADE_TO_ORDER` указывают `productionDays`. Наличие перед выставлением платежа подтверждает сотрудник. Для записи нужны роль сотрудника, cookie сессии и CSRF-токен. Повторяющийся `slug`, SKU или сочетание размера и цвета в одном товаре возвращает `409`.

## Один сервер

`compose.yaml` поднимает PostgreSQL, SeaweedFS, backend и Nginx. `compose.prod.yaml` включает HTTPS и рабочий SMTP. Только Nginx публикует порты 80 и 443. PostgreSQL и S3 доступны приложению в отдельных закрытых сетях. Тома БД и S3 сохраняются между запусками.

1. Соберите backend вне VPS и перенесите образ на сервер через реестр или `docker save` и `docker load`. Для сервера x86-64 используйте `docker buildx build --platform linux/amd64 --load -t shop-backend:0.1.0 backend` после сборки JAR.
2. Создайте защищённый `.env.production` с переменными из `.env.example` для БД, S3, кода доступа, списка сотрудников и отправителя. Установите уникальные секреты, email сотрудников через запятую и корректный отправитель. Для `SHOP_CODE_SECRET` можно использовать результат `openssl rand -hex 32`.
3. Добавьте `SHOP_BACKEND_IMAGE`, `SHOP_DOMAIN`, `SHOP_TLS_DIR`, `SHOP_SMTP_HOST`, `SHOP_SMTP_USERNAME`, `SHOP_SMTP_PASSWORD` и при необходимости `SHOP_SMTP_PORT`. Укажите в `SHOP_TLS_DIR` каталог на сервере с `fullchain.pem` и `privkey.pem`. После обновления сертификата выполните `nginx -s reload` внутри контейнера Nginx.
4. Запустите `docker compose --env-file .env.production -f compose.yaml -f compose.prod.yaml up --build --wait`. Перед приёмом заказов нужны интеграция кассы, юридические документы и резервные копии с проверкой восстановления.

Лимиты памяти контейнеров рассчитаны на совместный запуск на VPS с 2 vCPU и 4 ГБ. Для согласованной резервной копии на одном сервере остановите backend на время `pg_dump` базы и копирования тома SeaweedFS при остановленном `storage`. Храните зашифрованную копию вне VPS. Включите обновление TLS-сертификата и настройте копирование на выбранном хостинге.
