# Kitai

Русскоязычное Android-приложение для поиска товаров на Taobao, 1688 и Alibaba.com. Версия 0.2 работает автономно. Для перевода текст отправляется напрямую в OpenRouter, а распознавание изображения выполняется на устройстве.

1. Установите APK из артефакта `kitai-debug-apk` в GitHub Actions.
2. В настройках Kitai введите свой ключ OpenRouter. Приложение хранит его в зашифрованном виде с Android Keystore. Для запросов используется бесплатный маршрутизатор `openrouter/free`. [OpenRouter](https://openrouter.ai/openrouter/free)
3. Введите запрос и откройте поиск на нужной площадке. Сайты показывают собственную выдачу. Если площадка требует вход или перенаправляет на другой домен, нажмите «В браузере». Избранное, история и сравнение хранятся на устройстве. Предложения добавляются вручную.

Для сборки откройте каталог `android` в Android Studio с JDK 17 и Android SDK 34 или выполните `./gradlew testDebugUnitTest assembleDebug` из этого каталога.

## Backend предыдущей версии

Скопируйте `deploy/kitai.env.example` в `deploy/kitai.env` и заполните выданные площадками реквизиты. Храните ключи площадок только на сервере. Команда `docker compose -f deploy/compose.yaml up -d --build` собирает backend и по умолчанию публикует порт только на loopback. Этот сервер можно запускать отдельно для проверки старого API.

Backend использует официальный XML Банка России для CNY и USD. Для перевода настройте `KITAI_LIBRETRANSLATE_URL` на доверенный локальный сервис LibreTranslate с установленными языковыми моделями. Для OCR задайте путь к Tesseract в `KITAI_TESSERACT` и установите данные `chi_sim`, `eng`, `rus`. Эти данные входят в Docker-образ backend.

Для сохранения последнего известного курса в PostgreSQL и общего кеша Redis задайте случайный `KITAI_DB_PASSWORD` в `deploy/.env` и используйте команду ниже. Сервисы хранения доступны внутри сети Compose. PostgreSQL использует том `postgres_data`. Резервную копию снимайте через `pg_dump` и проверяйте восстановление на отдельном экземпляре.

```sh
docker compose --env-file deploy/.env -f deploy/compose.yaml -f deploy/compose.storage.yaml up -d --build
```

Для LibreTranslate можно загрузить только нужные языки командой `libretranslate --load-only en,ru,zh`. Для RU↔ZH модель может использовать промежуточный английский. Проверьте качество на ваших поисковых запросах и карточках. [LibreTranslate](https://docs.libretranslate.com/guides/supported_languages/)

## Документация

Для перевода через OpenRouter укажите `KITAI_OPENROUTER_API_KEY` в серверном файле `deploy/kitai.env` и пересоздайте контейнер API. При заданном ключе Kitai отправляет OpenRouter текст перевода, включая текст, распознанный локальным Tesseract. По умолчанию используется `openrouter/free`. Модель задаётся через `KITAI_OPENROUTER_MODEL`, а стоимость зависит от выбранной модели. При пустом ключе Kitai использует настроенный LibreTranslate. [OpenRouter](https://openrouter.ai/openrouter/free)

- [Архитектура и целевой HTTP-контракт](docs/architecture/architecture.md)
- [Техническое задание, план и критерии выпуска](docs/architecture/implementation-plan.md)
- [Развёртывание на Synology SA6400](docs/guides/synology.md)
- [Результаты проверки перевода и OCR](docs/guides/engine-verification.md)
- [Получение ключей Taobao, 1688 и Alibaba.com](docs/marketplaces/keys.md)
- Отчёты площадок · [Taobao](docs/marketplaces/taobao.md) · [1688](docs/marketplaces/1688.md) · [Alibaba.com](docs/marketplaces/alibaba.md)
- [Порядок работы агентов](AGENTS.md)
