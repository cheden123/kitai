# Развёртывание Kitai на Synology SA6400

1. Проверьте, что Container Manager установлен, а Docker Compose доступен по SSH. Для Kitai используйте отдельный каталог на томе NAS и отдельное имя Compose-проекта.
2. Перенесите исходники backend, файлы deploy и .dockerignore с сохранением структуры. Скопируйте deploy/kitai.env.example в deploy/kitai.env. Задайте два разных случайных секрета KITAI_API_TOKEN и KITAI_CURSOR_SECRET, ограничьте права файла до 600. Ключи площадок для проверки подключения Android можно оставить пустыми.
3. Запустите сборку из корня проекта командой ниже. Если порт 8000 уже занят, задайте свободный порт через KITAI_PORT. Docker публикует API на loopback.
4. В DSM создайте правило обратного прокси с отдельным HTTPS-именем и действующим сертификатом. Адрес назначения — HTTP 127.0.0.1 и порт Kitai. Ограничьте доступ домашней сетью или VPN. Проверьте, что имя разрешается и сертификат доверен на Android.
5. Проверьте /health с ПК. Для /api/v1/sources без токена ожидается 401, с токеном — 200. Введите HTTPS-адрес и токен в настройках Kitai. Убедитесь, что отображаются три статуса и что поиск получает ответ даже при отсутствии ключей площадок.

```sh
docker compose -p kitai -f deploy/compose.yaml up -d --build
docker compose -p kitai -f deploy/compose.yaml ps
```

Для общего кеша Redis и сохранения курсов в PostgreSQL добавьте deploy/compose.storage.yaml и передайте deploy/.env с KITAI_DB_PASSWORD. Для перевода задайте KITAI_LIBRETRANSLATE_URL — адрес LibreTranslate, доступный из контейнера API. Tesseract и три набора языковых данных устанавливаются при сборке образа.

[Проекты Container Manager](https://kb.synology.com/en-global/DSM/help/ContainerManager/docker_project) · [Обратный прокси DSM](https://kb.synology.com/en-in/DSM/help/DSM/AdminCenter/system_login_portal_advanced)

Для запуска собственного LibreTranslate в той же сети Compose добавьте `-f deploy/compose.translation.yaml` перед `up`. Сервис переводчика доступен контейнеру API по внутреннему адресу. Порт переводчика на NAS не публикуется. Первое включение загружает модели и может занять несколько минут. [Настройки LibreTranslate](https://github.com/LibreTranslate/LibreTranslate/blob/main/docker-compose.yml)

Если первая загрузка прервалась и переводчик запускается с неполным списком направлений, пересоздайте его с `KITAI_LT_UPDATE_MODELS=true`. Эта переменная включает восстановление пакетов из официального индекса. Именованный том моделей сохраняется.

```sh
KITAI_LT_UPDATE_MODELS=true docker compose -p kitai -f deploy/compose.yaml -f deploy/compose.translation.yaml up -d --force-recreate libretranslate
```
