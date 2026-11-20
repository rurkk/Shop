# Выкладка KNITSEW на knitsew.ru

Сайт работает на VPS ZetaLink по адресу `https://knitsew.ru` в режиме `pilot`. Витрина, личные заказы и админка доступны только участникам. Оплата выключена. [Требования к размещению](requirements.md#размещение) · [Чеклист запуска](production-launch.md)

## Переход с демо

14 ноября 2026 года переключили домен и оставили прежние тома `knitsew-demo_postgres-data` и `knitsew-demo_storage-data`. Backend и frontend остались на образах `4272003d75ae`. До и после переезда в базе было 14 товаров, две коллекции и одна учётная запись сотрудника. Проверили 13 опубликованных товаров «Руси», две записи галереи и контрольную сумму баннера. В Timeweb отключили запись `demo`.

На VPS активна конфигурация `/opt/knitsew-production/releases/domain-cutover-4272003d75ae`. Зашифрованные копии данных и настроек перед переключением лежат в `/opt/knitsew-production/backups` под именами `pre-cutover.zip.age` и `pre-cutover-config.tar.age`. Копия данных проверена, ключ хранится отдельно. [Порядок восстановления](production-backup.md)

Порядок переключения и возврата до начала продаж.

1. Проверьте доступ к VPS и свободное место. Сохраните зашифрованную копию PostgreSQL и SeaweedFS, прежние файлы Compose, настройки и образы для отката. Зафиксируйте число товаров, заказов и учётных записей перед переходом. [Резервное копирование](production-backup.md)
2. Подготовьте окружение для `knitsew.ru` с именем проекта `knitsew-demo`. Сохраните пароли PostgreSQL, ключи SeaweedFS и ключ шифрования TOTP. Пилот запускайте с выключенной оплатой. Зафиксируйте текущие A и AAAA основного домена и настройте DNS. [Настройка окружения](#конфигурация-первого-запуска) · [VPS и DNS](#vps-и-dns)
3. Остановите старый Nginx на время выпуска сертификата. Запустите пилот через `knitsew-production-compose up` с прежним именем проекта. Проверьте, что PostgreSQL и SeaweedFS используют `knitsew-demo_postgres-data` и `knitsew-demo_storage-data`. Сверьте данные каталога и проверьте HTTPS. [Сертификат](#https-и-продление) · [Пилот](#закрытый-пилот)
4. После проверки HTTPS и пароля пилота отключите запись `demo` в Timeweb. Сохраните файл продления сертификата демо вместе со старыми хуками вне каталога Certbot.
5. Для возврата на демо до начала продаж остановите новый Nginx. В Timeweb включите запись `demo` и восстановите A `92.53.96.223` и AAAA `2a03:6f00:1::5c35:60df` основного домена. Переместите файл продления `knitsew.ru.conf` из `/etc/letsencrypt/renewal` в `/opt/knitsew-production/pre-cutover`. Верните из `/opt/knitsew-production/pre-cutover/hooks` хуки демо и `demo.knitsew.ru.conf` в соответствующие каталоги Certbot. Проверьте срок действия сертификата демо и при необходимости выпустите его заново. Пометьте сохранённый образ `shop-nginx:pre-knitsew-cutover-4272003d75ae` тегом `shop-nginx:production` и запустите прежний стек через `knitsew-demo-compose up -d --no-build --wait --wait-timeout 120`. Оба запуска используют одни тома. [Обновление и откат после запуска](#обновление-и-откат)

## VPS и DNS

1. Подтвердите у ZetaLink адрес ЦОДа в России и условия хранения данных покупателей на этом VPS. Переиспользуйте установленные Ubuntu, Docker и SSH-доступ. Проверьте архитектуру, время, свободное место, входящие TCP 80 и 443 и исходящие HTTPS и TCP 587 до Postbox. PostgreSQL, SeaweedFS, backend и Next.js остаются в сетях Compose. [Условия размещения](requirements.md#размещение) · [Действующий VPS и доступ](deployment.md#доступ-по-ssh)
2. В Timeweb направьте A-запись `knitsew.ru` на IPv4 текущего VPS (`82.114.230.101` по результатам проверки 14 ноября 2026 года). Если IPv6 на VPS не настроен, удалите прежнюю AAAA-запись основного домена. Сверьте ответы `dig +short A knitsew.ru` и `dig +short AAAA knitsew.ru` с настройками VPS. Сохраните почтовые записи. [Настройка DNS в Timeweb](https://timeweb.com/ru/docs/domeny/resursnye-zapisi-domena-dns-zapisi/nastrojka-dns-zapisej/a-aaaa/)
3. Подтвердите домен `knitsew.ru` в Postbox, добавьте выданную запись DKIM в Timeweb и дождитесь проверки. Создайте отдельный ключ Postbox для рабочей среды. SMTP использует `postbox.cloud.yandex.net:587` с STARTTLS. Для API-ключа логином служит его идентификатор, паролем — секретная часть. [Подтверждение домена](https://yandex.cloud/ru/docs/postbox/quickstart) · [Параметры SMTP](https://yandex.cloud/ru/docs/postbox/operations/send-email#smtp)

## Сборка и перенос релиза

На VPS проверьте Certbot с таймером `certbot.timer`, `apache2-utils` и OpenSSL. Создайте каталоги от имени администратора.

```sh
install -d -m 0700 /opt/knitsew-production /opt/knitsew-production/releases /opt/knitsew-production/pre-cutover
install -d -m 0755 /opt/knitsew-production/tls /opt/knitsew-production/tls/acme
```

На рабочем компьютере возьмите чистый checkout коммита релиза и прогоните проверки. Настройте SSH-псевдоним `knitsew-prod` с доступом к Docker и каталогу `/opt/knitsew-production`. Команды на рабочем компьютере выполняются в Bash. На VPS выполняйте команды из учётной записи администратора, добавив `/usr/local/sbin` в `PATH`. Укажите архитектуру VPS в `platform`. Перед сборкой витрины добавьте файлы Gotham Pro с веб-лицензией. [Локальные проверки](../README.md#локальный-запуск) · [Шрифты](../README.md#витрина-и-браузерная-проверка)

```bash
(
    set -euo pipefail
    release=$(git rev-parse --short=12 HEAD)
    platform=linux/amd64
    ./backend/gradlew -p backend --no-daemon check bootJar
    docker buildx build --platform "$platform" --load -t "shop-backend:$release" backend
    docker buildx build --platform "$platform" --load --build-arg SHOP_GOTHAM_PRO=1 \
        -t "shop-frontend:$release" frontend
    docker image save --platform "$platform" "shop-backend:$release" "shop-frontend:$release" |
        gzip -1 | ssh -T knitsew-prod 'docker image load'
    git archive "$release" .env.production.example compose.yaml compose.prod.yaml compose.prod.pilot.yaml \
        infra/nginx infra/postgres infra/ops/knitsew-production-compose.sh \
        infra/ops/knitsew-production-tls.sh infra/ops/shop_backup.py |
        ssh -T knitsew-prod "set -eu; cd /opt/knitsew-production/releases; mkdir -m 0700 '$release'; tar -xf - -C '$release'"
    printf '%s\n' "$release"
)
```

## Конфигурация первого запуска

На VPS укажите в `release` тег из сборки. В каталоге `current` лежат конфигурация и скрипты выбранного релиза. Имя Compose-проекта задаёт имена контейнеров и томов.

```sh
cd /opt/knitsew-production
release=TAG_ИЗ_СБОРКИ
test -f "releases/$release/compose.yaml"
ln -s "releases/$release" current
install -m 0600 current/.env.production.example .env.production
printf '%s\n' knitsew-demo > project-name
printf '%s\n' pilot > access-mode
install -m 0755 current/infra/ops/knitsew-production-compose.sh /usr/local/sbin/knitsew-production-compose
```

В `.env.production` укажите теги образов выбранного релиза и перенесите из `.env.demo` параметры PostgreSQL, SeaweedFS, `SHOP_CODE_SECRET` и `SHOP_STAFF_TOTP_ENCRYPTION_KEY`. Действующие учётные записи уже сохранены в PostgreSQL. Укажите подтверждённый `SHOP_MAIL_FROM` на `knitsew.ru` и учётные данные SMTP. Храните прежний файл окружения и постоянный ключ TOTP в защищённом хранилище. [Шаблон окружения](../.env.production.example) · [Вход сотрудников](../README.md#вход-сотрудников) · [Как Compose читает переменные](https://docs.docker.com/compose/how-tos/environment-variables/variable-interpolation/)

Для предварительной проверки оставьте `SHOP_YOOKASSA_ENABLED=false`. Пустые поля `SHOP_YOOKASSA_SHOP_ID` и `SHOP_YOOKASSA_SECRET_KEY` заполните при подключении рабочего магазина.

## HTTPS и продление

При первом выпуске после проверки DNS освободите порт 80. При переключении с демо сохраните и уберите хуки `knitsew-demo` из каталогов `pre` и `post` в `/etc/letsencrypt/renewal-hooks`. Эти хуки управляют старым Nginx. Временно запустите HTTP-прокси, который обслуживает только проверку домена. Сохраните этот блок в `/opt/knitsew-production/pre-cutover/acme.conf`. [Проверка HTTP-01](https://letsencrypt.org/docs/challenge-types/) · [Режим webroot в Certbot](https://eff-certbot.readthedocs.io/en/stable/using.html#webroot)

```nginx
server {
    listen 80 default_server;
    server_name knitsew.ru;
    location ^~ /.well-known/acme-challenge/ {
        root /var/www/acme;
        try_files $uri =404;
    }
    location / { return 503; }
}
```

Укажите доступный операторам контактный email, если Certbot попросит его при первом запуске. После выпуска установите hook и скопируйте файлы в каталог, который читает Nginx.

```sh
cd /opt/knitsew-production
knitsew-production-compose build nginx
(
    set -eu
    docker run -d --rm --pull never --name knitsew-acme-bootstrap -p 80:80 \
        --mount "type=bind,source=$PWD/pre-cutover/acme.conf,target=/etc/nginx/conf.d/shop.conf,readonly" \
        --mount "type=bind,source=$PWD/tls/acme,target=/var/www/acme,readonly" \
        --entrypoint nginx shop-nginx:production -g 'daemon off;'
    trap 'docker stop knitsew-acme-bootstrap' EXIT
    certbot certonly --webroot -w "$PWD/tls/acme" --cert-name knitsew.ru -d knitsew.ru
)
```

После успешного выпуска выполните команды установки.

```sh
cd /opt/knitsew-production
install -m 0755 current/infra/ops/knitsew-production-tls.sh \
    /etc/letsencrypt/renewal-hooks/deploy/knitsew-production
RENEWED_LINEAGE=/etc/letsencrypt/live/knitsew.ru KNITSEW_INSTALL_INITIAL_TLS=1 \
    /etc/letsencrypt/renewal-hooks/deploy/knitsew-production
```

После запуска пилота запросы к `/.well-known/acme-challenge/` идут в тот же каталог `/opt/knitsew-production/tls/acme`. Остальные HTTP-запросы перенаправляются на HTTPS. Пробное продление проверяет доступность каталога. Отдельная проверка hook сверяет пару сертификат и ключ, копирует действующие файлы и перечитывает конфигурацию Nginx.

```sh
certbot renew --cert-name knitsew.ru --dry-run --non-interactive
RENEWED_LINEAGE=/etc/letsencrypt/live/knitsew.ru \
    /etc/letsencrypt/renewal-hooks/deploy/knitsew-production
systemctl enable --now certbot.timer
systemctl list-timers certbot.timer
```

## Закрытый пилот

Создайте файл пользователей для входа через Nginx. Первый `htpasswd` создаёт файл, последующие вызовы добавляют участников. Храните файл в закрытом каталоге на VPS. Выдайте пользователям отдельные пароли пилота через защищённый канал. Для действий сотрудника в админке также нужны код из письма и TOTP. [Параметры htpasswd](https://httpd.apache.org/docs/current/programs/htpasswd.html) · [Проверка Basic Auth в Nginx](https://nginx.org/en/docs/http/ngx_http_auth_basic_module.html)

```sh
cd /opt/knitsew-production
htpasswd -cB pilot.htpasswd pilot-user
chmod 0644 pilot.htpasswd
knitsew-production-compose config --quiet
knitsew-production-compose build postgres
knitsew-production-compose up -d --no-build --wait --wait-timeout 120
knitsew-production-compose exec -T nginx nginx -t
knitsew-production-compose ps
```

Создавайте `pilot.htpasswd` один раз. Для добавления или отзыва доступа используйте `htpasswd -B` или `htpasswd -D` без `-c`. После изменения файла восстановите права `0644` и пересоздайте контейнер командой `knitsew-production-compose up -d --no-deps --no-build --force-recreate --wait nginx`, чтобы он прочитал актуальный файл. Каталог `/opt/knitsew-production` закрыт для других пользователей VPS, а из контейнера файл доступен Nginx для чтения.

С рабочего компьютера проверьте, что HTTP отвечает `308` с `Location: https://knitsew.ru/`, HTTPS проходит проверку сертификата, а витрина, личные заказы, админка и `/api/csrf` без пароля отвечают `401`. Чтобы принимать уведомления ЮKassa, Nginx пропускает `POST /api/payments/yookassa/webhook` без пароля пилота. Ограничение частоты сохраняется, приложение сверяет операции с ЮKassa.

```sh
curl -sSI http://knitsew.ru/
for path in / /orders /admin /api/csrf; do
    curl -sS -o /dev/null -w '%{http_code} %{url_effective}\n' "https://knitsew.ru$path"
done
curl -fsS -u pilot-user https://knitsew.ru/api/csrf -o /dev/null
curl -fsS -u pilot-user https://knitsew.ru/admin -o /dev/null
curl -fsS -u pilot-user https://knitsew.ru/offer -o /dev/null
```

Сверьте перенесённый каталог. Для обновления витрины используйте админку или импорт с `--url https://knitsew.ru --pilot-user pilot-user` и отдельным журналом `--state`. На этапе настройки используйте вымышленные данные. Проверьте приход рабочего письма на ящики участников, вход сотрудника, действия покупателя и ссылки `https://knitsew.ru` в письмах. Проверьте восстановление заказа с файлом из внешней зашифрованной копии. [Импорт каталога](catalog-import.md#запуск-и-повторное-обновление) · [Копирование и восстановление](production-backup.md)

До контрольной оплаты опубликуйте утверждённые документы, настройте фискализацию и подтвердите, что рабочая касса оформит и отправит покупателю нужные чеки. Репозиторий не формирует кассовые чеки. Доработайте интеграцию, если выбранная касса требует передавать данные о чеках из KNITSEW. В кабинете рабочего магазина укажите `https://knitsew.ru/api/payments/yookassa/webhook` для событий `payment.succeeded`, `payment.canceled` и `refund.succeeded`. Заполните ID и секрет рабочего магазина в `.env.production`, задайте `SHOP_YOOKASSA_ENABLED=true` и пересоздайте backend командой `knitsew-production-compose up -d --no-deps --no-build --wait backend`. Пройдите полный контрольный заказ и возврат согласно чеклисту. [Юридическая подготовка](legal-launch.md) · [Уведомления ЮKassa](https://yookassa.ru/developers/using-api/webhooks) · [Контрольные заказы](production-launch.md#5-закрытый-пилот-и-открытие-продаж)

После успешного пилота запишите `public` в `/opt/knitsew-production/access-mode` и пересоздайте только Nginx командой `knitsew-production-compose up -d --no-deps --no-build --force-recreate --wait nginx`. Проверьте, что витрина и `/api/csrf` доступны без пароля пилота, а админка по-прежнему требует входа сотрудника. Затем обновите ссылки для покупателей. Для временного закрытия верните `pilot` и пересоздайте Nginx той же командой.

## Обновление и откат

1. Перед обновлением закройте витрину режимом `pilot` и приостановите приём заказов. Зафиксируйте текущий релиз и сохраните `.env.production` в защищённом хранилище. Сделайте согласованную зашифрованную копию PostgreSQL и SeaweedFS. На время копирования сервисы, которые меняют данные, остановятся. Сохраните прежние образы. [Резервное копирование](production-backup.md)
2. Перенесите новый релиз в `releases/<тег>`. Переключите `current` командой `ln -sfnT "releases/$release" current` на VPS и обновите теги образов в `.env.production`. Для релиза с изменениями прокси или БД пересоберите `nginx` и `postgres`. Выполните `knitsew-production-compose config --quiet` и `knitsew-production-compose up -d --no-build --wait --wait-timeout 120`. Затем выполните `knitsew-production-compose up -d --no-deps --no-build --force-recreate --wait nginx`, чтобы перечитать файл пилота из нового релиза. Повторите проверки закрытого пилота перед возобновлением заказов.
3. Если предыдущий релиз работает с текущей схемой БД, верните прежние Compose-файлы через `current` и прежние теги и настройки окружения. Пересоберите инфраструктурные образы из прежнего релиза, запустите стек и пересоздайте Nginx, как при обновлении. Выполните проверки в закрытом режиме.
4. При несовместимых миграциях восстановите копию в отдельный Compose-проект с именем вида `knitsew-production-recovery-<суффикс>`. Проверьте восстановленный заказ и вложения до переключения. Если после копии были приняты заявки или платежи, сверяйте расхождения по обеим базам и кабинету ЮKassa до открытия. Сначала остановите прежний проект, чтобы освободить порты 80 и 443. Затем запишите имя проекта восстановления в `project-name`, оставьте `pilot` и запустите проект с предыдущим релизом. Сохраните тома прежнего проекта до завершения сверки. [Процедура восстановления](production-backup.md)

### Генерация эскизов

Укажите `SHOP_SKETCH_GENAPI_API_KEY` и `SHOP_SKETCH_GENAPI_ENABLED=true` в защищённом
`/opt/knitsew-production/.env.production`. Проверьте баланс в кабинете GenAPI. Backend обращается
к `proxy.gen-api.ru` за разбором переписки, к `api.gen-api.ru` за генерацией и к
`gen-api.storage.yandexcloud.net` за изображениями. Примените конфигурацию при штатной
выкладке backend. В «Команде → Эскизы с ИИ» проверьте модели, затем сгенерируйте и
исправьте эскиз на тестовом заказе с вымышленными данными.
