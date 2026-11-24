import hmac
import json
import os
import re
from decimal import Decimal
from time import time_ns

import httpx

from .models import Product
from .upstream import (
    JSON,
    Page,
    SourceError,
    item_id,
    next_page,
    object_or_empty,
    observation_time,
    optional_text,
    page_number,
    platform_url,
    quantity,
    request_json,
    require_object,
    require_rows,
)


GATEWAY = "https://openapi-api.alibaba.com/rest"
SEARCH = "/eco/buyer/product/search"
DETAIL = "/eco/buyer/product/description"


class AlibabaCom:
    def __init__(self, client: httpx.AsyncClient, app_key: str, app_secret: str, access_token: str) -> None:
        self.client = client
        self.app_key = app_key
        self.app_secret = app_secret
        self.access_token = access_token

    @classmethod
    def configured(cls, client: httpx.AsyncClient) -> "AlibabaCom | None":
        app_key = os.getenv("KITAI_ALIBABA_APP_KEY", "").strip()
        app_secret = os.getenv("KITAI_ALIBABA_APP_SECRET", "").strip()
        access_token = os.getenv("KITAI_ALIBABA_ACCESS_TOKEN", "").strip()
        if app_key and app_secret and access_token:
            return cls(client, app_key, app_secret, access_token)
        return None

    async def _call(self, path: str, key: str, business: JSON) -> JSON:
        params = {
            "app_key": self.app_key,
            "access_token": self.access_token,
            "timestamp": str(time_ns() // 1_000_000),
            "sign_method": "sha256",
            key: json.dumps(business, ensure_ascii=False, separators=(",", ":")),
        }
        message = path + "".join(name + params[name] for name in sorted(params))
        params["sign"] = hmac.new(self.app_secret.encode(), message.encode(), "sha256").hexdigest().upper()
        content = await request_json(self.client, "POST", GATEWAY + path, data=params)
        if str(content.get("code")) != "0":
            raise _error(content.get("code"))
        result = require_object(content.get("result"))
        code = result.get("code" if path == SEARCH else "result_code")
        if str(code) != "200":
            raise _error(code)
        return result

    async def search(self, query: str, page: int, size: int) -> Page:
        result = await self._call(
            SEARCH, "param0", {"keyword": query, "index": page, "size": size, "currency": "USD", "language": "en"}
        )
        data = require_object(result.get("data"))
        products = [_product(row, detail=False) for row in require_rows(data.get("products"))]
        pagination = object_or_empty(data.get("pagination"))
        total = page_number(pagination.get("page_count"))
        return Page(products, next_page(page, page + 1 if total is not None and page < total else None))

    async def detail(self, product_id: str) -> Product | None:
        if item_id(product_id, numeric=True) is None:
            return None
        result = await self._call(
            DETAIL, "query_req", {"product_id": int(product_id), "currency": "USD", "language": "en-US"}
        )
        row = result.get("result_data")
        if row is None:
            return None
        row = require_object(row)
        if row.get("status") in ("PRODUCT_OFFLINE", "PRODUCT_DELETE"):
            return None
        if item_id(row.get("product_id"), numeric=True) != product_id:
            raise SourceError()
        return _product(row, detail=True)


def _error(code: object) -> SourceError:
    code = str(code).lower()
    if code in {"401", "403", "invalidappkey", "invalidaccesstoken", "incompletesignature", "invalidsignature"}:
        return SourceError("forbidden")
    if code in {"429", "requestthrottled", "appcalllimited", "apiusagelimit"}:
        return SourceError("rate_limited")
    return SourceError()


def _product(row: JSON, *, detail: bool) -> Product:
    product_id = item_id(row.get("product_id"), numeric=True)
    title = optional_text(row.get("title"))
    url = platform_url(row.get("detail_url" if detail else "permalink"), "alibaba.com")
    if not product_id or not title or not url:
        raise SourceError()
    if detail:
        image = row.get("main_image")
        currency = optional_text(row.get("currency"))
        price = object_or_empty(row.get("wholesale_trade")).get("price")
    else:
        image = object_or_empty(row.get("image")).get("main_image")
        currency = "USD"
        price = row.get("price")
    price_min, price_max = _prices(price)
    if currency is None or not re.fullmatch(r"[A-Z]{3}", currency):
        currency = None
    return Product(
        id=product_id,
        source="alibaba",
        title=title,
        url=url,
        image_url=platform_url(image, "alicdn.com", "alibaba.com"),
        price_min=price_min,
        price_max=price_max,
        currency=currency if price_min is not None else None,
        seller=optional_text(row.get("supplier")),
        moq=quantity(row.get("min_order_quantity"), positive=True),
        observed_at=observation_time(),
    )


def _prices(value: object) -> tuple[Decimal | None, Decimal | None]:
    if isinstance(value, str) and "-" in value:
        lower, _, upper = value.partition("-")
        price_min = quantity(lower.strip())
        price_max = quantity(upper.strip())
        if price_min is not None and price_max is not None and price_min <= price_max:
            return price_min, price_max
        return None, None
    price = quantity(value)
    return price, price
