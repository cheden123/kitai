# Kitai

Русскоязычный Android-клиент для поиска товаров на Taobao, 1688 и Alibaba.com. Поиск обращается к официальным API через backend. Возможности каждой площадки зависят от разрешений приложения.

## Запуск для разработки

1. Установите JDK 17 и Android SDK с платформой, указанной в конфигурации модуля Android.
2. В каталоге `backend` выполните `uv sync --frozen`, затем `uv run uvicorn kitai.main:app --host 127.0.0.1 --port 8000`.
3. Откройте каталог `android` в Android Studio и соберите отладочную версию. Для стандартного эмулятора используйте адрес сервера `http://10.0.2.2:8000/`. Для устройства укажите доступный ему HTTPS-адрес.
4. Проверьте `GET /health` и `GET /api/v1/sources`. Площадка без настроенных реквизитов должна сообщить о состоянии доступа. Проверьте реальный товарный запрос после предоставления прав.

## Развёртывание

Скопируйте `deploy/kitai.env.example` в `deploy/kitai.env` и заполните выданные площадками реквизиты. Храните ключи площадок только на сервере. Команда `docker compose -f deploy/compose.yaml up -d --build` собирает backend и по умолчанию публикует порт только на loopback. На Synology задайте разные случайные значения `KITAI_API_TOKEN` и `KITAI_CURSOR_SECRET`, используйте обратный прокси с TLS и введите токен API в приложении. Проверьте доступ на целевом устройстве.

Backend использует официальный XML Банка России для CNY и USD. Для перевода настройте `KITAI_LIBRETRANSLATE_URL` на доверенный локальный сервис LibreTranslate с установленными языковыми моделями. Для OCR задайте путь к Tesseract в `KITAI_TESSERACT` и установите данные `chi_sim`, `eng`, `rus`. Эти данные входят в Docker-образ backend.

Для сохранения последнего известного курса в PostgreSQL и общего кеша Redis задайте случайный `KITAI_DB_PASSWORD` в `deploy/.env` и используйте команду ниже. Сервисы хранения доступны внутри сети Compose. PostgreSQL использует том `postgres_data`. Резервную копию снимайте через `pg_dump` и проверяйте восстановление на отдельном экземпляре.

```sh
docker compose --env-file deploy/.env -f deploy/compose.yaml -f deploy/compose.storage.yaml up -d --build
```

Для LibreTranslate можно загрузить только нужные языки командой `libretranslate --load-only en,ru,zh`. Для RU↔ZH модель может использовать промежуточный английский. Проверьте качество на ваших поисковых запросах и карточках. [LibreTranslate](https://docs.libretranslate.com/guides/supported_languages/)

## Документация

- [Архитектура и целевой HTTP-контракт](docs/architecture/architecture.md)
- [Техническое задание, план и критерии выпуска](docs/architecture/implementation-plan.md)
- [Развёртывание на Synology SA6400](docs/guides/synology.md)
- [Результаты проверки перевода и OCR](docs/guides/engine-verification.md)
- [Получение ключей Taobao, 1688 и Alibaba.com](docs/marketplaces/keys.md)
- Отчёты площадок · [Taobao](docs/marketplaces/taobao.md) · [1688](docs/marketplaces/1688.md) · [Alibaba.com](docs/marketplaces/alibaba.md)
- [Порядок работы агентов](AGENTS.md)
