import hmac
import json
import os
import re

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


GATEWAY = "https://gw.open.1688.com/openapi/"
NAMESPACE = "com.alibaba.fenxiao.crossborder"
SEARCH = "product.search.keywordQuery"
DETAIL = "product.search.queryProductDetail"


class Alibaba1688:
    def __init__(
        self,
        client: httpx.AsyncClient,
        app_key: str,
        app_secret: str,
        access_token: str,
        out_member_id: str | None,
    ) -> None:
        self.client = client
        self.app_key = app_key
        self.app_secret = app_secret
        self.access_token = access_token
        self.out_member_id = out_member_id

    @classmethod
    def configured(cls, client: httpx.AsyncClient) -> "Alibaba1688 | None":
        app_key = os.getenv("KITAI_1688_APP_KEY", "").strip()
        app_secret = os.getenv("KITAI_1688_APP_SECRET", "").strip()
        access_token = os.getenv("KITAI_1688_ACCESS_TOKEN", "").strip()
        out_member_id = optional_text(os.getenv("KITAI_1688_OUT_MEMBER_ID"))
        if re.fullmatch(r"[A-Za-z0-9_-]{1,128}", app_key) and app_secret and access_token:
            return cls(client, app_key, app_secret, access_token, out_member_id)
        return None

    async def _call(self, method: str, key: str, business: JSON) -> JSON | None:
        path = f"param2/1/{NAMESPACE}/{method}/{self.app_key}"
        params = {
            "access_token": self.access_token,
            key: json.dumps(business, ensure_ascii=False, separators=(",", ":")),
        }
        message = path + "".join(name + params[name] for name in sorted(params))
        params["_aop_signature"] = hmac.new(self.app_secret.encode(), message.encode(), "sha1").hexdigest().upper()
        payload = await request_json(self.client, "POST", GATEWAY + path, data=params)
        if "error_code" in payload:
            raise _error(payload["error_code"])
        result = require_object(payload.get("result"))
        if result.get("success") is not True and result.get("success") != "true":
            raise _error(result.get("code"))
        data = result.get("result")
        return None if data is None else require_object(data)

    async def search(self, query: str, page: int, size: int) -> Page:
        data = await self._call(
            SEARCH, "offerQueryParam", {"keyword": query, "beginPage": page, "pageSize": size, "country": "ru"}
        )
        if data is None:
            raise SourceError()
        products = [_product(row, detail=False) for row in require_rows(data.get("data"))]
        total = page_number(data.get("totalPage"))
        return Page(products, next_page(page, page + 1 if total is not None and page < total else None))

    async def detail(self, product_id: str) -> Product | None:
        if item_id(product_id, numeric=True) is None:
            return None
        params = {"offerId": int(product_id), "country": "ru"}
        if self.out_member_id:
            params["outMemberId"] = self.out_member_id
        data = await self._call(DETAIL, "offerDetailParam", params)
        if data is None:
            return None
        if item_id(data.get("offerId"), numeric=True) != product_id:
            raise SourceError()
        return _product(data, detail=True)


def _error(code: object) -> SourceError:
    code = str(code).lower()
    if code.split("_", 1)[0] in {"401", "403"} or code in {"invalid_access_token", "invalid_signature"}:
        return SourceError("forbidden")
    if code.split("_", 1)[0] == "429" or code in {"too_many_requests", "isp.traffic-limit"}:
        return SourceError("rate_limited")
    return SourceError()


def _product(row: JSON, *, detail: bool) -> Product:
    offer_id = item_id(row.get("offerId"), numeric=True)
    title = optional_text(row.get("subject"))
    if not offer_id or not title:
        raise SourceError()
    url = platform_url(row.get("promotionUrl" if detail else "promotionURL"), "1688.com")
    fallback = f"https://detail.1688.com/offer/{offer_id}.html"
    if detail:
        sale = object_or_empty(row.get("productSaleInfo"))
        ranges = sale.get("priceRangeList")
        prices = [quantity(tier.get("price")) for tier in require_rows(ranges)] if isinstance(ranges, list) else []
        image_group = object_or_empty(row.get("productImage"))
        images = image_group.get("images")
        image = images[0] if isinstance(images, list) and images else None
    else:
        sale = object_or_empty(row.get("priceInfo"))
        prices = [quantity(sale.get("price"))]
        image = row.get("imageUrl")
    prices = [price for price in prices if price is not None]
    return Product(
        id=offer_id,
        source="1688",
        title=title,
        title_ru=optional_text(row.get("subjectTrans")),
        url=url or fallback,
        image_url=platform_url(image, "alicdn.com", "1688.com"),
        price_min=min(prices) if prices else None,
        price_max=max(prices) if prices else None,
        currency="CNY" if prices else None,
        seller=optional_text(row.get("companyName")),
        moq=quantity(row.get("minOrderQuantity"), positive=True),
        observed_at=observation_time(),
    )
