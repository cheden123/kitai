import asyncio
from dataclasses import dataclass
from datetime import UTC, datetime
from decimal import Decimal, InvalidOperation
import json
import re
from typing import Any, Protocol
from urllib.parse import urlsplit

import httpx

from .models import Product, SourceStatus


JSON = dict[str, Any]
MAX_RESPONSE = 2 * 1024 * 1024
MAX_PAGE = 1000


class SourceError(Exception):
    def __init__(self, status: SourceStatus = "unavailable") -> None:
        self.status = status
        super().__init__(status)


@dataclass(frozen=True)
class Page:
    items: list[Product]
    next_page: int | None = None


class Marketplace(Protocol):
    async def search(self, query: str, page: int, size: int) -> Page: ...
    async def detail(self, product_id: str) -> Product | None: ...


def object_or_empty(value: Any) -> JSON:
    return value if isinstance(value, dict) else {}


def require_object(value: Any) -> JSON:
    if not isinstance(value, dict):
        raise SourceError()
    return value


def require_rows(value: Any) -> list[JSON]:
    if not isinstance(value, list):
        raise SourceError()
    return [require_object(row) for row in value]


def optional_text(value: Any) -> str | None:
    return (value.strip() or None) if isinstance(value, str) else None


def item_id(value: Any, *, numeric: bool = False) -> str | None:
    if isinstance(value, int) and not isinstance(value, bool):
        value = str(value)
    value = optional_text(value)
    pattern = r"[1-9][0-9]{0,24}" if numeric else r"[A-Za-z0-9_-]{1,128}"
    return value if value and re.fullmatch(pattern, value) else None


def quantity(value: Any, *, positive: bool = False) -> Decimal | None:
    if not isinstance(value, (str, int, Decimal)) or isinstance(value, bool):
        return None
    try:
        result = Decimal(value)
    except InvalidOperation:
        return None
    if not result.is_finite() or not 0 <= result < 10**12 or not -8 <= result.as_tuple().exponent <= 12:
        return None
    return result if not positive or result > 0 else None


def page_number(value: Any) -> int | None:
    if isinstance(value, str) and re.fullmatch(r"[0-9]{1,12}", value):
        return int(value)
    return value if type(value) is int and 0 <= value < 10**12 else None


def next_page(current: int, candidate: int | None) -> int | None:
    return candidate if candidate is not None and current < candidate <= MAX_PAGE else None


def platform_url(value: Any, *domains: str) -> str | None:
    raw_url = optional_text(value)
    if not raw_url or any(char.isspace() or ord(char) < 32 for char in raw_url) or "\\" in raw_url:
        return None
    if raw_url.startswith("//"):
        raw_url = "https:" + raw_url
    elif raw_url.startswith("http://"):
        raw_url = "https://" + raw_url[len("http://"):]
    try:
        url = urlsplit(raw_url)
        host = url.hostname or ""
        port = url.port
    except ValueError:
        return None
    if url.scheme != "https" or url.username is not None or port not in (None, 443):
        return None
    return raw_url if any(host == domain or host.endswith("." + domain) for domain in domains) else None


def observation_time() -> datetime:
    return datetime.now(UTC)


async def request_json(
    client: httpx.AsyncClient,
    method: str,
    url: str,
    *,
    params: dict[str, str] | None = None,
    data: dict[str, str] | None = None,
) -> JSON:
    try:
        async with asyncio.timeout(8):
            async with client.stream(method, url, params=params, data=data) as response:
                if response.status_code in (401, 403):
                    raise SourceError("forbidden")
                if response.status_code == 429:
                    raise SourceError("rate_limited")
                response.raise_for_status()
                buffer = bytearray()
                async for chunk in response.aiter_bytes():
                    if len(buffer) + len(chunk) > MAX_RESPONSE:
                        raise SourceError()
                    buffer.extend(chunk)
        return require_object(json.loads(buffer, parse_float=Decimal))
    except (TimeoutError, httpx.HTTPError, ValueError, RecursionError):
        # The response, URL and transport exception can contain credentials.
        raise SourceError() from None
