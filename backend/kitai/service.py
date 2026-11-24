import asyncio
import base64
from collections.abc import Awaitable, Callable
from dataclasses import dataclass
import hashlib
import hmac
import json
import secrets
from time import time

from .models import Product, SearchResponse, SourceId, SourceInfo, SourceOutcome, SourceStatus, SourcesResponse
from .upstream import Marketplace, Page, SourceError, item_id


@dataclass(frozen=True)
class CatalogSource:
    name: str
    homepage_url: str
    supports_detail: bool = True


CATALOG: dict[SourceId, CatalogSource] = {
    "taobao": CatalogSource("Taobao", "https://www.taobao.com/"),
    "1688": CatalogSource("1688", "https://www.1688.com/"),
    "alibaba": CatalogSource("Alibaba.com", "https://www.alibaba.com/"),
}
CHINESE_QUERY_SOURCES = {"taobao", "1688"}


class InvalidCursor(ValueError):
    pass


class Catalog:
    def __init__(self, adapters: dict[SourceId, Marketplace], cursor_secret: str | None = None) -> None:
        self.adapters = adapters
        self.status: dict[SourceId, SourceStatus] = dict.fromkeys(adapters, "configured")
        self.cursor_key = cursor_secret.encode() if cursor_secret else secrets.token_bytes(32)

    def sources(self) -> SourcesResponse:
        return SourcesResponse(sources=[
            SourceInfo(
                id=source,
                status=self.status.get(source, "not_configured"),
                name=info.name,
                supports_search=True,
                supports_detail=info.supports_detail,
                homepage_url=info.homepage_url,
            )
            for source, info in CATALOG.items()
        ])

    async def _load(
        self, source: SourceId, query: str, page: int, limit: int
    ) -> tuple[Page | None, SourceStatus]:
        try:
            result = await self.adapters[source].search(query, page, limit)
        except SourceError as error:
            self.status[source] = error.status
            return None, error.status
        self.status[source] = "available"
        return result, "available"

    async def search(
        self, query: str, selected: list[SourceId] | None, limit: int, cursor: str | None,
        translate_query: Callable[[str], Awaitable[str | None]],
    ) -> SearchResponse:
        sources = [source for source in CATALOG if selected is None or source in selected]
        key = hashlib.sha256(_json([query, sources, limit])).hexdigest()
        if cursor is None:
            positions = {source: (1, 0) for source in sources if source in self.adapters}
            start = 0
            expires = int(time()) + 900
            translated = await translate_query(query) if CHINESE_QUERY_SOURCES.intersection(positions) else None
        else:
            positions, start, expires, translated = self._decode(cursor, key)

        order = sources[start:] + sources[:start]
        active = [source for source in order if source in positions and source in self.adapters]
        results = await asyncio.gather(*(
            self._load(
                source,
                translated if translated and source in CHINESE_QUERY_SOURCES else query,
                positions[source][0], limit,
            )
            for source in active
        ))
        statuses = {source: status for source, (_, status) in zip(active, results)}
        outcomes = [
            SourceOutcome(id=source, status=statuses.get(source, self.status.get(source, "not_configured")))
            for source in sources
        ]
        pages = {source: page for source, (page, _) in zip(active, results) if page is not None}
        offsets = {source: positions[source][1] for source in pages}

        items: list[Product] = []
        seen: set[tuple[SourceId, str]] = set()
        while len(items) < limit:
            has_items = False
            for source, page in pages.items():
                offset = offsets[source]
                if offset >= len(page.items):
                    continue
                has_items = True
                item = page.items[offset]
                offsets[source] += 1
                identity = (source, item.id)
                if identity not in seen:
                    items.append(item)
                    seen.add(identity)
                    start = (sources.index(source) + 1) % len(sources)
                if len(items) == limit:
                    break
            if not has_items:
                break

        remaining = {}
        for source, page in pages.items():
            if offsets[source] < len(page.items):
                remaining[source] = (positions[source][0], offsets[source])
            elif page.next_page is not None:
                remaining[source] = (page.next_page, 0)
        if remaining or items:
            for source, (page, status) in zip(active, results):
                if page is None and status in {"rate_limited", "unavailable"}:
                    remaining[source] = positions[source]

        next_cursor = self._encode(key, remaining, start, expires, translated) if remaining else None
        return SearchResponse(items=items, sources=outcomes, next_cursor=next_cursor)

    async def detail(self, source: SourceId, product_id: str) -> Product | None:
        if not CATALOG[source].supports_detail:
            raise SourceError("unsupported")
        if source not in self.adapters:
            raise SourceError("not_configured")
        if source in {"1688", "alibaba"} and item_id(product_id, numeric=True) is None:
            return None
        try:
            product = await self.adapters[source].detail(product_id)
        except SourceError as error:
            self.status[source] = error.status
            raise
        self.status[source] = "available"
        return product

    def _encode(
        self, key: str, positions: dict[SourceId, tuple[int, int]], start: int, expires: int, translated: str | None
    ) -> str:
        body = base64.urlsafe_b64encode(_json([key, positions, start, expires, translated])).decode().rstrip("=")
        signature = hmac.new(self.cursor_key, body.encode(), "sha256").hexdigest()
        return f"{body}.{signature}"

    def _decode(self, cursor: str, key: str) -> tuple[dict[SourceId, tuple[int, int]], int, int, str | None]:
        body, _, signature = cursor.partition(".")
        expected = hmac.new(self.cursor_key, body.encode(), "sha256").hexdigest()
        if not signature.isascii() or not hmac.compare_digest(expected, signature):
            raise InvalidCursor()
        try:
            query_key, positions, start, expires, translated = json.loads(
                base64.urlsafe_b64decode(body + "=" * (-len(body) % 4))
            )
        except ValueError:
            raise InvalidCursor() from None
        if query_key != key or expires < time():
            raise InvalidCursor()
        return positions, start, expires, translated


def _json(value: object) -> bytes:
    return json.dumps(value, separators=(",", ":"), ensure_ascii=False).encode()
