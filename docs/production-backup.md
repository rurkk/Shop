# Резервное копирование KNITSEW

Копируйте PostgreSQL и том SeaweedFS вместе: в базе хранятся заказы и ссылки на файлы, в томе — сами фотографии и вложения. [Команда `shop_backup.py`](../infra/ops/shop_backup.py) создаёт один архив `.zip.age`. Проверяйте восстановление в отдельном проекте Compose с пустыми томами. Для запуска нужны Python 3.9 или новее, Docker с Compose и [age](https://github.com/FiloSottile/age).

## Создание копии

1. Создайте ключ через `age-keygen` на машине восстановления. На боевом VPS храните только открытый ключ `age1…` и передайте его в `KNITSEW_BACKUP_RECIPIENT`. Закрытый ключ, `.env.production` и сведения о версии релиза храните отдельно в защищённом хранилище. Они понадобятся для чтения архива и запуска восстановленного сайта.
2. Запускайте копирование в согласованное окно: на время снимка команда останавливает приём заказов и запись файлов, затем возвращает работавшие сервисы. Проверьте, что переменные `project` и `release_dir` соответствуют контуру и версии, из которых нужно копировать. Используйте тот же набор Compose-файлов, что и при запуске. Для закрытого пилота добавьте `-f "$release_dir/compose.prod.pilot.yaml"`. Не запускайте параллельно другие операции Compose с этим проектом.

```sh
cd /opt/knitsew-production
project=$(cat project-name)
release_dir=$(realpath current)
install -d -m 0700 /opt/knitsew-production/backups
archive="/opt/knitsew-production/backups/$(date -u +%Y%m%dT%H%M%SZ).zip.age"
python3 "$release_dir/infra/ops/shop_backup.py" backup \
    --project "$project" --env-file /opt/knitsew-production/.env.production \
    -f "$release_dir/compose.yaml" -f "$release_dir/compose.prod.yaml" \
    --archive "$archive" --recipient "$KNITSEW_BACKUP_RECIPIENT"
```

3. Передайте готовый архив в приватный бакет [Yandex Object Storage](https://yandex.cloud/ru/docs/storage/s3/) в России. Настройте расписание, сроки хранения и уведомления о неудаче копирования или передачи. Убедитесь, что можете скачать архив и проверить его командой `verify`; сделайте это перед удалением локального экземпляра.

## Проверка архива и восстановление

На машине восстановления задайте `KNITSEW_BACKUP_IDENTITY` как путь к защищённому файлу закрытого ключа, а `TMPDIR` направьте на зашифрованный диск с местом для расшифрованного архива. `verify` и `restore` используют закрытый временный файл, который автоматически освобождается. Скачайте архив из доверенного внешнего хранилища, укажите путь в `archive` и проверьте целостность.

```sh
python3 infra/ops/shop_backup.py verify --archive "$archive" --identity "$KNITSEW_BACKUP_IDENTITY"
```

Для восстановления используйте тот же релиз, из которого сделана копия. Соберите или загрузите образы на целевую машину. Выберите новое имя Compose-проекта и отдельный файл окружения. В примере это `knitsew-production-recovery-01` и `.env.recovery`. Если восстановление прервалось после создания томов, для повторной попытки выберите новое имя проекта.

```sh
python3 infra/ops/shop_backup.py restore \
    --project knitsew-production-recovery-01 --env-file .env.recovery \
    -f compose.yaml -f compose.prod.yaml \
    --archive "$archive" --identity "$KNITSEW_BACKUP_IDENTITY"
```

После загрузки базы и файлов команда оставляет запущенным только PostgreSQL. Для проверки запускайте остальные сервисы с закрытым доступом, тестовой почтой и выключенной оплатой. Войдите в известный заказ, проверьте статус и скачайте вложение. При аварийном восстановлении сверяйте изменения в заказах и платежах после времени копии до открытия сайта. [Порядок переключения](production-deployment.md#обновление-и-откат)
