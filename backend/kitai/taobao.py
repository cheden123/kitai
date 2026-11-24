from datetime import datetime, timedelta, timezone
import hmac
import os

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


GATEWAY = "https://eco.taobao.com/router/rest"
SEARCH = "taobao.ovs.tbk.material.item.optional"
DETAIL = "taobao.ovs.tbk.material.item.info.get"
LINK = "taobao.ovs.tbk.material.item.link.convert"
SITES = frozenset({"TW", "HK", "MY", "SG", "AU", "CA", "MO", "GLOBAL"})


class Taobao:
    def __init__(
        self, client: httpx.AsyncClient, app_key: str, app_secret: str, adzone: str, site: str
    ) -> None:
        self.client = client
        self.app_key = app_key
        self.app_secret = app_secret
        self.adzone = adzone
        self.site = site

    @classmethod
    def configured(cls, client: httpx.AsyncClient) -> "Taobao | None":
        app_key = os.getenv("KITAI_TAOBAO_APP_KEY", "").strip()
        app_secret = os.getenv("KITAI_TAOBAO_APP_SECRET", "").strip()
        adzone = os.getenv("KITAI_TAOBAO_ADZONE_ID", "").strip()
        site = os.getenv("KITAI_TAOBAO_SITE", "").strip().upper()
        if app_key and app_secret and item_id(adzone, numeric=True) and site in SITES:
            return cls(client, app_key, app_secret, adzone, site)
        return None

    async def _call(self, method: str, **business: str) -> JSON:
        params = {
            "method": method,
            "app_key": self.app_key,
            "format": "json",
            "sign_method": "hmac",
            "timestamp": datetime.now(timezone(timedelta(hours=8))).strftime("%Y-%m-%d %H:%M:%S"),
            "v": "2.0",
            "site": self.site,
            **business,
        }
        message = "".join(key + params[key] for key in sorted(params))
        params["sign"] = hmac.new(self.app_secret.encode(), message.encode(), "md5").hexdigest().upper()
        payload = await request_json(self.client, "POST", GATEWAY, data=params)
        if "error_response" in payload:
            error = require_object(payload["error_response"])
            code = str(error.get("code"))
            sub_code = str(error.get("sub_code", ""))
            if code in {"7", "8"} or sub_code.startswith("isp.traffic"):
                raise SourceError("rate_limited")
            if code in {"11", "12", "13", "14", "25", "26", "27", "29", "40", "41", "42", "43"}:
                raise SourceError("forbidden")
            raise SourceError()
        return require_object(payload.get(method.removeprefix("taobao.").replace(".", "_") + "_response"))

    async def search(self, query: str, page: int, size: int) -> Page:
        response = await self._call(
            SEARCH, q=query, page_no=str(page), page_size=str(size), adzone_id=self.adzone, language="zh_CN"
        )
        rows = _rows(response, "result_list")
        products = [_product(row) for row in rows]
        candidate = page_number(response.get("next_page"))
        if response.get("has_next") is not True:
            candidate = None
        return Page(products, next_page(page, candidate))

    async def detail(self, product_id: str) -> Product | None:
        if item_id(product_id) is None:
            return None
        response = await self._call(DETAIL, item_ids=product_id, language="zh_CN")
        rows = _rows(response, "items")
        if not rows:
            return None
        detail = rows[0]
        if product_id not in (item_id(detail.get("item_id")), item_id(detail.get("input_item_id"))):
            raise SourceError()
        url = _click_url(detail)
        if url is None:
            converted = await self._call(
                LINK, item_ids=product_id, adzone_id=self.adzone, language="zh_CN"
            )
            for row in _rows(converted, "items"):
                if item_id(row.get("item_id")) == item_id(detail.get("item_id")):
                    url = _click_url(row)
                    break
        return _product(detail, url=url, requested_id=product_id)


def _rows(response: JSON, field: str) -> list[JSON]:
    value = response.get(field)
    if value in (None, {}) and (field == "items" or page_number(response.get("total_results")) == 0):
        return []
    return require_rows(require_object(value).get("material_item_summary_info"))


def _click_url(row: JSON) -> str | None:
    info = object_or_empty(row.get("publish_info"))
    return platform_url(info.get("click_url"), "taobao.com", "tmall.com")


def _product(row: JSON, *, url: str | None = None, requested_id: str | None = None) -> Product:
    product_id = requested_id or item_id(row.get("item_id"))
    basic = require_object(row.get("item_basic_info"))
    title = optional_text(basic.get("title"))
    url = url or _click_url(row)
    if not product_id or not title or not url:
        raise SourceError()
    prices = object_or_empty(row.get("price_promotion_info"))
    price = quantity(prices.get("zk_final_price"))
    if price is None:
        price = quantity(prices.get("reserve_price"))
    return Product(
        id=product_id,
        source="taobao",
        title=title,
        url=url,
        image_url=platform_url(basic.get("pict_url"), "alicdn.com", "taobaocdn.com"),
        price_min=price,
        price_max=price,
        currency="CNY" if price is not None else None,
        seller=optional_text(basic.get("shop_title")),
        observed_at=observation_time(),
    )
