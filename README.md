# Shop

Backend фабрики кастомной вышивки. Первый сценарий в API охватывает заказ по фото от черновика до отправки.

## Локальный запуск

Понадобятся JDK 21, Docker с Compose v2 и Python 3 для сквозной проверки.

~~~sh
./backend/gradlew -p backend --no-daemon check bootJar
docker compose --env-file .env.example -f compose.yaml -f compose.local.yaml up --build --wait
python3 infra/tests/smoke.py
~~~

- API доступен через [Nginx](http://127.0.0.1:18080/api/csrf), коды и уведомления попадают в [Mailpit](http://127.0.0.1:18025). Тестовая учётная запись сотрудника — staff@example.test.
- Скрипт создаёт тестовые заказы и запрашивает коды для вымышленных адресов. Повторный запуск под той же почтой сотрудника возможен через минуту из-за ограничения выдачи кодов.
- Для остановки выполните `docker compose --env-file .env.example -f compose.yaml -f compose.local.yaml down`. Тома сохраняются.

## Сценарий заказа

| Действие | HTTP |
| --- | --- |
| Получить токен для изменения данных | `GET /api/csrf` |
| Создать черновик с email, изделием, размером и цветом | `POST /api/orders/drafts` |
| Запросить и подтвердить код для черновика или действующего заказа | `POST /api/orders/{id}/access/code` и `POST /api/orders/{id}/access/verify` |
| Загрузить фото, эскиз или вложение | `POST /api/orders/{id}/files?purpose=SOURCE\|SKETCH\|CHAT`, поле multipart `file` |
| Передать заказ менеджеру после загрузки фото | `POST /api/orders/{id}/submit` |
| Открыть заказ или скачать доступный файл | `GET /api/orders/{id}` или `GET /api/orders/{id}/files/{fileId}` |
| Войти сотруднику и открыть список заказов | `POST /api/staff/access/code`, `POST /api/staff/access/verify` и `GET /api/staff/orders` |
| Выставить цену в копейках и срок в днях | `POST /api/staff/orders/{id}/quote` |
| Подтвердить платёж на локальном стенде | `POST /api/staff/orders/{id}/test-payments/{paymentId}/confirm` |
| Опубликовать эскиз по `fileId` | `POST /api/staff/orders/{id}/sketches` |
| Согласовать версию эскиза или запросить правки | `POST /api/orders/{id}/sketches/{sketchId}/approve` или `POST /api/orders/{id}/sketches/{sketchId}/request-changes` |
| Отметить готовность и указать трек-номер после доплаты | `POST /api/staff/orders/{id}/production-complete` и `POST /api/staff/orders/{id}/shipment` |
| Выйти из сессии | `POST /api/session/logout` |

Запросы на изменение передают cookie сессии и CSRF-токен в заголовке, имя и значение которого возвращает `GET /api/csrf`. После входа нужно получить свежий токен. Код действует 10 минут, доступ покупателя ограничен одним заказом. Файлы JPEG и PNG до 10 МиБ проходят через backend и хранятся в приватном S3-совместимом бакете.

Чат доступен после отправки черновика. `GET /api/orders/{id}/messages` возвращает последние сообщения и `lastSequence`, параметр `before` открывает предыдущую страницу. Отправка идёт через `POST /api/orders/{id}/messages` с `clientMessageId` для безопасного повтора. Подписка `GET /api/orders/{id}/messages/stream?after={lastSequence}` передаёт события `message.created`. При переподключении сервер использует заголовок `Last-Event-ID`, если браузер его передал.

Сумма делится на предоплату и доплату перед отправкой. Тестовые подтверждения включаются только локальным профилем. В составе первого этапа также нет каталога «Русь», выбора доставки и интеграции с Instagram.

## Один сервер

`compose.yaml` поднимает PostgreSQL, SeaweedFS, backend и Nginx. `compose.prod.yaml` включает HTTPS и рабочий SMTP. Только Nginx публикует порты 80 и 443. PostgreSQL и S3 доступны приложению в отдельных закрытых сетях. Тома БД и S3 сохраняются между запусками.

1. Соберите backend вне VPS и перенесите образ на сервер через реестр или `docker save` и `docker load`. Для сервера x86-64 используйте `docker buildx build --platform linux/amd64 --load -t shop-backend:0.1.0 backend` после сборки JAR.
2. Создайте защищённый `.env.production` с переменными из `.env.example` для БД, S3, кода доступа, списка сотрудников и отправителя. Установите уникальные секреты, email сотрудников через запятую и корректный отправитель. Для `SHOP_CODE_SECRET` можно использовать результат `openssl rand -hex 32`.
3. Добавьте `SHOP_BACKEND_IMAGE`, `SHOP_DOMAIN`, `SHOP_TLS_DIR`, `SHOP_SMTP_HOST`, `SHOP_SMTP_USERNAME`, `SHOP_SMTP_PASSWORD` и при необходимости `SHOP_SMTP_PORT`. Укажите в `SHOP_TLS_DIR` каталог на сервере с `fullchain.pem` и `privkey.pem`. После обновления сертификата выполните `nginx -s reload` внутри контейнера Nginx.
4. Запустите `docker compose --env-file .env.production -f compose.yaml -f compose.prod.yaml up --build --wait`. Перед приёмом заказов нужны интеграция кассы, юридические документы и резервные копии с проверкой восстановления.

Лимиты памяти контейнеров рассчитаны на совместный запуск на VPS с 2 vCPU и 4 ГБ. Для согласованной резервной копии на одном сервере остановите backend на время `pg_dump` базы и копирования тома SeaweedFS при остановленном `storage`. Храните зашифрованную копию вне VPS. Включите обновление TLS-сертификата и настройте копирование на выбранном хостинге.
