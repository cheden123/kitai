import asyncio
from collections.abc import Awaitable, Callable
from contextlib import asynccontextmanager
from datetime import date
from decimal import Decimal, ROUND_HALF_UP
import hmac
import os
import re
from typing import Annotated

from fastapi import FastAPI, HTTPException, Path, Query
import httpx
from starlette.responses import JSONResponse
from starlette.types import ASGIApp, Receive, Scope, Send

from .a1688 import Alibaba1688
from .alibaba import AlibabaCom
from .models import Product, SearchResponse, SourceId, SourcesResponse
from .service import Catalog, InvalidCursor
from .taobao import Taobao
from .translation.router import get_rates, router as translation_router, translate_text
from .upstream import Marketplace, SourceError


OCR_BODY_LIMIT = 6 * 1024 * 1024
TRANSLATE_BODY_LIMIT = 64 * 1024
Translator = Callable[[str, str, str], Awaitable[str]]
HAN = re.compile(r"[\u3400-\u4dbf\u4e00-\u9fff\U00020000-\U000323af]")


def _route_path(scope: Scope) -> str:
    path = scope["path"]
    root_path = scope.get("root_path", "").rstrip("/")
    return path[len(root_path):] if path.startswith(root_path + "/") else path


class ApiAuthentication:
    def __init__(self, app: ASGIApp, token: bytes) -> None:
        self.app = app
        self.token = token

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] == "http" and _route_path(scope).startswith("/api/v1/"):
            headers = [value for key, value in scope["headers"] if key == b"authorization"]
            credentials = headers[0].split(None, 1) if len(headers) == 1 else []
            if (
                len(credentials) != 2
                or credentials[0].lower() != b"bearer"
                or not hmac.compare_digest(credentials[1], self.token)
            ):
                await JSONResponse(
                    {"detail": "authentication_required"}, 401, headers={"WWW-Authenticate": "Bearer"}
                )(scope, receive, send)
                return
        await self.app(scope, receive, send)


class ApiBodyLimits:
    def __init__(self, app: ASGIApp) -> None:
        self.app = app
        self.limits = {
            "/api/v1/ocr": (OCR_BODY_LIMIT, 30, asyncio.Semaphore(4)),
            "/api/v1/translate": (TRANSLATE_BODY_LIMIT, 15, asyncio.Semaphore(16)),
        }

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        settings = None
        if scope["type"] == "http" and scope["method"] == "POST":
            settings = self.limits.get(_route_path(scope).rstrip("/"))
        if settings is None:
            await self.app(scope, receive, send)
            return
        limit, timeout, slots = settings
        if slots.locked():
            await JSONResponse({"detail": "service_busy"}, 429)(scope, receive, send)
            return
        async with slots:
            await self._read_bounded(scope, receive, send, limit, timeout)

    async def _read_bounded(self, scope: Scope, receive: Receive, send: Send, limit: int, timeout: int) -> None:
        for key, value in scope["headers"]:
            if key == b"content-length" and (not value.isdigit() or len(value) > 10 or int(value) > limit):
                await JSONResponse({"detail": "request_too_large"}, 413)(scope, receive, send)
                return

        body = bytearray()
        try:
            async with asyncio.timeout(timeout):
                while True:
                    message = await receive()
                    if message["type"] == "http.disconnect":
                        return
                    chunk = message.get("body", b"")
                    if len(body) + len(chunk) > limit:
                        await JSONResponse({"detail": "request_too_large"}, 413)(scope, receive, send)
                        return
                    body.extend(chunk)
                    if not message.get("more_body"):
                        break
        except TimeoutError:
            await JSONResponse({"detail": "upload_timeout"}, 408)(scope, receive, send)
            return

        replayed = False

        async def replay():
            nonlocal replayed
            if replayed:
                return await receive()
            replayed = True
            data = bytes(body)
            body.clear()
            return {"type": "http.request", "body": data, "more_body": False}

        await self.app(scope, replay, send)


def _configure(client: httpx.AsyncClient) -> dict[SourceId, Marketplace]:
    candidates = {
        "taobao": Taobao.configured(client),
        "1688": Alibaba1688.configured(client),
        "alibaba": AlibabaCom.configured(client),
    }
    return {source: adapter for source, adapter in candidates.items() if adapter is not None}


async def _with_rubles(
    products: list[Product], rates_provider: Callable[[], Awaitable[dict]]
) -> list[Product]:
    supported = {"CNY", "USD"}
    if not any(product.currency in supported and product.price_min is not None for product in products):
        return products
    try:
        async with asyncio.timeout(2.5):
            snapshot = await rates_provider()
    except (TimeoutError, HTTPException):
        return products
    rates = snapshot["rates"]
    day = date.fromisoformat(snapshot["date"])
    return [
        product.model_copy(update={
            "price_rub": (product.price_min * Decimal(rates[product.currency])).quantize(
                Decimal("0.01"), rounding=ROUND_HALF_UP
            ),
            "fx_date": day,
        })
        if product.currency in rates and product.price_min is not None else product
        for product in products
    ]


async def _translate(
    translator: Translator, text: str, source: str, target: str, max_length: int
) -> str | None:
    try:
        async with asyncio.timeout(2):
            translated = (await translator(text, source, target)).strip()
    except (TimeoutError, HTTPException):
        return None
    return translated if 0 < len(translated) <= max_length and translated != text else None


def _title_language(title: str) -> str | None:
    letters = "".join(char for char in title if char.isalpha())
    if not letters:
        return None
    if letters.isascii():
        return "en"
    if HAN.search(letters) and all(char.isascii() or HAN.fullmatch(char) for char in letters):
        return "zh"
    return None


async def _translated_title(product: Product, translator: Translator) -> str | None:
    if product.title_ru:
        return product.title_ru
    language = _title_language(product.title)
    if language is None:
        return None
    return await _translate(translator, product.title, language, "ru", 5000)


def create_app(
    adapters: dict[SourceId, Marketplace] | None = None,
    rates_provider: Callable[[], Awaitable[dict]] = get_rates,
    translator: Translator = translate_text,
) -> FastAPI:
    @asynccontextmanager
    async def lifespan(application: FastAPI):
        async with httpx.AsyncClient(
            timeout=httpx.Timeout(5, connect=3),
            limits=httpx.Limits(max_connections=16, max_keepalive_connections=8),
            follow_redirects=False,
            trust_env=False,
        ) as client:
            application.state.catalog = Catalog(
                _configure(client) if adapters is None else adapters, os.getenv("KITAI_CURSOR_SECRET")
            )
            yield

    token = os.getenv("KITAI_API_TOKEN", "").strip()
    application = FastAPI(
        title="Kitai", version="0.1.0", lifespan=lifespan,
        openapi_url=None if token else "/openapi.json",
    )
    application.add_middleware(ApiBodyLimits)
    if token:
        application.add_middleware(ApiAuthentication, token=token.encode())
    application.include_router(translation_router, prefix="/api/v1")

    @application.get("/health")
    async def health() -> dict[str, str]:
        return {"status": "ok"}

    @application.get("/api/v1/sources")
    async def sources() -> SourcesResponse:
        return application.state.catalog.sources()

    @application.get("/api/v1/search")
    async def search(
        q: Annotated[str, Query(min_length=1, max_length=200)],
        source: Annotated[list[SourceId] | None, Query(max_length=3)] = None,
        limit: Annotated[int, Query(ge=1, le=50)] = 20,
        cursor: Annotated[str | None, Query(max_length=2048)] = None,
    ) -> SearchResponse:
        query = q.strip()
        if not query:
            raise HTTPException(422, detail="empty_query")
        async def translate_query(text: str) -> str | None:
            if re.search(r"[А-Яа-яЁё]", text):
                return await _translate(translator, text, "ru", "zh", 200)
            return None

        try:
            result = await application.state.catalog.search(query, source, limit, cursor, translate_query)
        except InvalidCursor:
            raise HTTPException(422, detail="invalid_cursor") from None
        result.items = await _with_rubles(result.items, rates_provider)
        return result

    @application.get("/api/v1/products/{source}/{id}")
    async def detail(source: SourceId, id: Annotated[str, Path(pattern=r"^[A-Za-z0-9_-]{1,128}$")]) -> Product:
        try:
            product = await application.state.catalog.detail(source, id)
        except SourceError as error:
            status = {
                "not_configured": 503,
                "unsupported": 501,
                "forbidden": 403,
                "rate_limited": 429,
                "unavailable": 502,
            }[error.status]
            raise HTTPException(status, detail={"source": source, "status": error.status}) from None
        if product is None:
            raise HTTPException(404, detail="product_not_found")
        products, translated = await asyncio.gather(
            _with_rubles([product], rates_provider), _translated_title(product, translator)
        )
        return products[0].model_copy(update={"title_ru": translated})

    return application


app = create_app()
