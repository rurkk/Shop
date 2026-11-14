# Доступ к инфраструктуре и деплой KNITSEW

| Что | Где |
| --- | --- |
| VPS для демо | [Панель ZetaLink](https://bill.zetalink.ru/), публичный IPv4 `82.114.230.101`. Ubuntu 22.04 x86-64, 4 vCPU, 4 ГБ RAM, SSD 50 ГБ |
| Домен и DNS | [Timeweb → Домены и SSL](https://hosting.timeweb.ru/domains), домен `knitsew.ru`. Запись `demo`, тип `A`, значение `82.114.230.101`, TTL 600 |
| Адрес демо | `https://demo.knitsew.ru`. Для показа используем отдельные тома, вымышленные данные заказов, тестовые изображения и почтовые ящики |
| Почта и платежи демо | [Yandex Cloud Postbox](https://yandex.cloud/ru/docs/postbox/quickstart) для писем, [тестовый магазин ЮKassa](https://yookassa.ru/developers/payment-acceptance/testing-and-going-live/testing) для оплаты |

VPS активен, домен зарегистрирован, DNS проверен. Для первого деплоя подготовьте доступ по SSH, Docker, сертификат и почту. Тестовый магазин ЮKassa можно подключить после запуска демо. [Боевой контур на ATLEX](requirements.md#размещение) и [подготовка к приёму настоящих заказов](legal-launch.md) описаны отдельно.

## Доступ по SSH

В панели ZetaLink выберите VPS по публичному IPv4 из таблицы и откройте **Web-console**. Для первого входа используйте `root` и пароль из **Instructions**. В открытой консоли получите отпечаток сервера. Он нужен для проверки при первом SSH-подключении.

```sh
ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub
```

На рабочем компьютере используйте выделенный ключ `~/.ssh/knitsew-demo-deploy`. Если ключа ещё нет, создайте его командой `ssh-keygen`. Для добавления на VPS нужна одна строка из файла `.pub`.

```sh
ssh-keygen -t ed25519 -f ~/.ssh/knitsew-demo-deploy -C knitsew-demo-deploy
```

```sh
cat ~/.ssh/knitsew-demo-deploy.pub
```

В консоли VPS выполните следующий блок и введите строку публичного ключа по запросу. Закрытый ключ остаётся на рабочем компьютере.

```bash
install -d -m 0700 /root/.ssh
touch /root/.ssh/authorized_keys
chmod 0600 /root/.ssh/authorized_keys
read -r -p 'Строка из файла .pub: ' deploy_pubkey
if printf '%s\n' "$deploy_pubkey" | ssh-keygen -lf -; then
    grep -qxF -- "$deploy_pubkey" /root/.ssh/authorized_keys ||
        printf '%s\n' "$deploy_pubkey" >> /root/.ssh/authorized_keys
fi
unset deploy_pubkey
```

На рабочем компьютере добавьте в `~/.ssh/config`:

```sshconfig
Host knitsew-demo
    HostName 82.114.230.101
    User root
    IdentityFile ~/.ssh/knitsew-demo-deploy
    IdentitiesOnly yes
    HostKeyAlgorithms ssh-ed25519
```

```sh
ssh -o PreferredAuthentications=publickey knitsew-demo 'id -un && hostname'
```

Подтвердите ключ сервера только после сверки отпечатка `SHA256` с консолью. Команда должна вывести `root` и имя VPS. После успешной проверки можно отключить парольный вход по SSH. Веб-консоль остаётся способом восстановления доступа. Оставьте её открытой, пока проверяете вход по ключу из нового SSH-сеанса.

```sh
cat > /etc/ssh/sshd_config.d/00-knitsew-key-only.conf <<'EOF'
PermitRootLogin prohibit-password
PasswordAuthentication no
KbdInteractiveAuthentication no
EOF
sshd -t && systemctl reload ssh
```

Затем проверьте `ssh -o BatchMode=yes knitsew-demo 'id -un'` в новом сеансе. Пароль, использованный для первого входа, смените в консоли командой `passwd`. Ключ развёртывания даёт полный доступ к VPS. Для закрытого стенда есть отдельная [учётная запись, которая умеет только читать логи](../README.md#чтение-логов-тестового-стенда-по-ssh).

## Подготовка VPS и TLS

На VPS установите Docker Engine с плагинами Buildx и Compose по [инструкции Docker для Ubuntu](https://docs.docker.com/engine/install/ubuntu/). Проверьте `docker info` и `docker compose version`. Подготовьте каталог и Certbot.

```sh
install -d -m 0700 /opt/knitsew-demo /opt/knitsew-demo/tls
apt-get update && apt-get install -y certbot
```

С рабочей машины проверьте `dig +short A demo.knitsew.ru`. Ответ должен содержать `82.114.230.101`. Снаружи нужны TCP-порты `80` и `443` для сайта и `22` для SSH. Исходящие соединения используют HTTPS и TCP `587` до Postbox. PostgreSQL и SeaweedFS остаются во внутренних сетях Compose. [Запись A в Timeweb](https://timeweb.com/ru/docs/domeny/resursnye-zapisi-domena-dns-zapisi/nastrojka-dns-zapisej/a-aaaa/)

До запуска Nginx выпустите сертификат на VPS, пока порт `80` свободен. Укажите доступный почтовый адрес, когда Certbot попросит контакт.

```sh
certbot certonly --standalone --cert-name demo.knitsew.ru -d demo.knitsew.ru
```

Compose монтирует `/opt/knitsew-demo/tls` только для чтения. Копируйте сертификат и закрытый ключ в этот каталог как обычные файлы, поскольку пути в `/etc/letsencrypt/live` ссылаются на файлы в другом каталоге. Создайте скрипт копирования для первого запуска и последующих обновлений.

```sh
install -d -m 0755 /etc/letsencrypt/renewal-hooks/deploy
cat > /etc/letsencrypt/renewal-hooks/deploy/knitsew-demo <<'SH'
#!/bin/sh
set -eu
[ "$RENEWED_LINEAGE" = /etc/letsencrypt/live/demo.knitsew.ru ] || exit 0
install -m 0644 "$RENEWED_LINEAGE/fullchain.pem" /opt/knitsew-demo/tls/fullchain.pem
install -m 0600 "$RENEWED_LINEAGE/privkey.pem" /opt/knitsew-demo/tls/privkey.pem
SH
chmod 0755 /etc/letsencrypt/renewal-hooks/deploy/knitsew-demo
RENEWED_LINEAGE=/etc/letsencrypt/live/demo.knitsew.ru /etc/letsencrypt/renewal-hooks/deploy/knitsew-demo
```

[Проверка HTTP-01](https://letsencrypt.org/docs/challenge-types/) · [Обновление сертификатов в Certbot](https://eff-certbot.readthedocs.io/en/stable/using.html#renewing-certificates)

## Сборка и перенос релиза

На рабочей машине возьмите чистый checkout коммита релиза. Проверьте его по разделу [«Локальный запуск»](../README.md#локальный-запуск). Соберите образы `linux/amd64` под архитектуру VPS. Тег обоих образов совпадает с сокращённым хешем коммита.

```bash
(
    set -euo pipefail
    release=$(git rev-parse --short=12 HEAD)
    ./backend/gradlew -p backend --no-daemon check bootJar
    docker buildx build --platform linux/amd64 --load -t "shop-backend:$release" backend
    docker buildx build --platform linux/amd64 --load -t "shop-frontend:$release" frontend
    docker save "shop-backend:$release" "shop-frontend:$release" | gzip -1 |
        ssh knitsew-demo 'docker load'
    tar -czf - compose.yaml compose.prod.yaml infra/nginx infra/postgres |
        ssh knitsew-demo 'tar -xzf - -C /opt/knitsew-demo'
    printf 'Тег релиза: %s\n' "$release"
)
```

Для демонстрационной сборки используется Arial. Если есть лицензия и оба файла Gotham Pro, способ сборки описан в [README](../README.md#витрина-и-браузерная-проверка). При повторной выкладке сделайте копию данных по разделу ниже **до** переноса файлов нового релиза.

## Окружение и запуск демо

В `/opt/knitsew-demo` на VPS создайте `.env.demo` с правами `0600`. Укажите DNS-имя, путь к сертификату и настройки отправки.

```dotenv
SHOP_DOMAIN=demo.knitsew.ru
SHOP_PUBLIC_ORIGIN=https://demo.knitsew.ru
SHOP_TLS_DIR=/opt/knitsew-demo/tls
SHOP_SMTP_HOST=postbox.cloud.yandex.net
SHOP_SMTP_PORT=587
```

Добавьте следующие значения в тот же файл.

| Переменные | Значения |
| --- | --- |
| `SHOP_BACKEND_IMAGE`, `SHOP_FRONTEND_IMAGE` | `shop-backend:<тег>` и `shop-frontend:<тег>` из шага сборки |
| `SHOP_DB_USER`, `SHOP_DB_PASSWORD`, `SHOP_DB_ADMIN_PASSWORD` | Имя `shop` и два разных пароля. PostgreSQL применяет начальные значения при создании пустого тома |
| `SHOP_S3_BUCKET`, `SHOP_S3_ACCESS_KEY`, `SHOP_S3_SECRET_KEY` | Бакет `order-files` и отдельная случайная пара ключей SeaweedFS |
| `SHOP_CODE_SECRET`, `SHOP_STAFF_TOTP_ACCOUNTS` | Секрет для кодов входа и [отдельный TOTP для каждого сотрудника](../README.md#вход-сотрудников) |
| `SHOP_MAIL_FROM`, `SHOP_SMTP_USERNAME`, `SHOP_SMTP_PASSWORD` | Подтверждённый отправитель на `knitsew.ru` и учётные данные SMTP из Postbox |

Для паролей БД, ключей SeaweedFS и `SHOP_CODE_SECRET` можно использовать отдельные вызовы `openssl rand -hex 32`. Используйте `.env.example` как справочник по формату. Его пароли и TOTP предназначены для локальных тестов. Храните `.env.demo`, приватный SSH-ключ, приватный ключ сертификата и пароль из Instructions вне Git. Для SMTP подтвердите домен в Postbox и добавьте выданную запись DKIM в Timeweb. [Настройка Postbox](https://yandex.cloud/ru/docs/postbox/quickstart) · [Учётные данные SMTP](https://yandex.cloud/ru/docs/postbox/operations/send-email#smtp)

В том же каталоге сохраните `compose.demo.yaml`. Он использует профиль `demo` и настройки HTTPS и SMTP из `compose.prod.yaml`. С выключенной ЮKassa можно отправить заявку, рассчитать заказ и открыть переписку. На этапе оплаты покупатель увидит сообщение о недоступности платежа при попытке перейти к оплате.

```yaml
services:
  backend:
    environment:
      SPRING_PROFILES_ACTIVE: demo
      SHOP_YOOKASSA_ENABLED: "false"
```

На VPS сохраните команду, которую будут использовать при выкладках и обновлении сертификата. Постоянное имя Compose-проекта `knitsew-demo` сохраняет привязку к томам базы и файлов между релизами.

```sh
cat > /usr/local/sbin/knitsew-demo-compose <<'SH'
#!/bin/sh
set -eu
cd /opt/knitsew-demo
exec docker compose -p knitsew-demo --env-file .env.demo \
    -f compose.yaml -f compose.prod.yaml -f compose.demo.yaml "$@"
SH
chmod 0755 /usr/local/sbin/knitsew-demo-compose
knitsew-demo-compose config --quiet
knitsew-demo-compose up -d --build --wait --wait-timeout 120
knitsew-demo-compose ps
```

С рабочего компьютера проверьте HTTP-переадресацию и HTTPS без отключения проверки сертификата.

```sh
curl -fsSI http://demo.knitsew.ru/
curl -fsS -o /dev/null https://demo.knitsew.ru/
curl -fsS -o /dev/null https://demo.knitsew.ru/admin
curl -fsS -o /dev/null https://demo.knitsew.ru/api/csrf
```

HTTP должен вернуть `308` на тот же адрес с `https://`. Через админку опубликуйте демонстрационную витрину. С телефона откройте сайт через мобильную сеть. Проверьте отправку заявки с тестовыми данными, получение письма, вход сотрудника с TOTP и доступ к вложению. Состояние контейнеров и свежие логи доступны через `knitsew-demo-compose ps` и `knitsew-demo-compose logs --tail 100 nginx backend frontend`.

После запуска включите обновление TLS. Certbot на время проверки освобождает порт `80`, копирует обновлённые файлы и запускает Nginx. Настройка рассчитана на VPS с одним сайтом.

```sh
install -d -m 0755 /etc/letsencrypt/renewal-hooks/pre /etc/letsencrypt/renewal-hooks/post
cat > /etc/letsencrypt/renewal-hooks/pre/knitsew-demo <<'SH'
#!/bin/sh
exec /usr/local/sbin/knitsew-demo-compose stop nginx
SH
cat > /etc/letsencrypt/renewal-hooks/post/knitsew-demo <<'SH'
#!/bin/sh
exec /usr/local/sbin/knitsew-demo-compose start nginx
SH
chmod 0755 /etc/letsencrypt/renewal-hooks/pre/knitsew-demo \
    /etc/letsencrypt/renewal-hooks/post/knitsew-demo
certbot renew --cert-name demo.knitsew.ru --dry-run
systemctl enable --now certbot.timer
systemctl list-timers certbot.timer
```

### Тестовая оплата через ЮKassa

Добавьте `SHOP_YOOKASSA_SHOP_ID` и `SHOP_YOOKASSA_SECRET_KEY` тестового магазина в `.env.demo`. В кабинете этого магазина укажите `https://demo.knitsew.ru/api/payments/yookassa/webhook` и события `payment.succeeded`, `payment.canceled`, `refund.succeeded`. [Тестовый магазин ЮKassa](https://yookassa.ru/developers/payment-acceptance/testing-and-going-live/testing)

Замените содержимое `compose.demo.yaml` следующим блоком.

```yaml
services:
  backend:
    environment:
      SPRING_PROFILES_ACTIVE: demo
      SHOP_YOOKASSA_ENABLED: "true"
      SHOP_YOOKASSA_SHOP_ID: ${SHOP_YOOKASSA_SHOP_ID:?Set the YooKassa test shop ID}
      SHOP_YOOKASSA_SECRET_KEY: ${SHOP_YOOKASSA_SECRET_KEY:?Set the YooKassa test shop key}
```

Проверьте конфигурацию командой `knitsew-demo-compose config --quiet` и примените её командой `knitsew-demo-compose up -d --wait --wait-timeout 120`. Проведите тестовый платёж и проверьте обновление статуса заказа после уведомления от ЮKassa.

## Обновление, резервные копии и откат

Сохраните тег текущего рабочего релиза и копию `.env.demo` в защищённом хранилище. Перед обновлением сделайте согласованную копию БД и файлов. На время копирования остановите Nginx, backend и SeaweedFS. PostgreSQL продолжает работать, чтобы выполнить `pg_dump`.

```bash
(
    set -eu
    umask 077
    backup=$(mktemp -d /opt/knitsew-demo/backup.XXXXXXXX)
    docker volume inspect knitsew-demo_storage-data > /dev/null
    tar -czf "$backup/compose.tar.gz" -C /opt/knitsew-demo \
        compose.yaml compose.prod.yaml compose.demo.yaml infra/nginx infra/postgres
    trap 'knitsew-demo-compose up -d --wait --wait-timeout 120' EXIT
    knitsew-demo-compose stop nginx backend storage
    knitsew-demo-compose exec -T postgres sh -ec \
        'PGPASSWORD="$POSTGRES_PASSWORD" pg_dump -h 127.0.0.1 -U postgres -Fc shop' > "$backup/shop.dump"
    docker run --rm --network none --entrypoint tar \
        --mount type=volume,source=knitsew-demo_storage-data,target=/data,readonly \
        shop-postgres:17-alpine -czf - -C /data . > "$backup/files.tar.gz"
    printf 'Каталог копии: %s\n' "$backup"
)
```

Перенесите каталог копии по SSH в зашифрованное хранилище вне VPS. Для проверки восстановления подготовьте соответствующий релиз в отдельном Compose-проекте и сначала запустите только PostgreSQL. Загрузите `shop.dump` через `pg_restore` в пустую базу `shop` с созданной ролью приложения. Распакуйте `files.tar.gz` в пустой том SeaweedFS, затем запустите остальные контейнеры и проверьте восстановленный заказ вместе с вложением.

Для обновления соберите и перенесите новую версию, замените теги двух образов в `.env.demo` и повторите `config --quiet`, `up -d --build --wait --wait-timeout 120` и проверку сайта. Постоянные тома сохраняются. Для отката верните Compose-файлы и теги предыдущего релиза. Если успели примениться несовместимые миграции, восстановите базу и файлы из копии, сделанной перед обновлением.
