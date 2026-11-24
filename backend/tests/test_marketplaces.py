import asyncio
from decimal import Decimal
import hmac
import json
from urllib.parse import parse_qsl

import httpx
import pytest

from kitai.a1688 import Alibaba1688
from kitai.alibaba import AlibabaCom
from kitai.taobao import Taobao
from kitai.upstream import SourceError


def test_taobao_search_detail_and_documented_link_conversion():
    listing = {
        "item_id": "affiliate-id",
        "item_basic_info": {"title": "手提包", "pict_url": "//img.alicdn.com/image.png", "shop_title": "商店"},
        "price_promotion_info": {"zk_final_price": "19.50", "final_promotion_price": "8.00"},
    }
    link = {"click_url": "https://intl.taobao.com/dp?item=1"}
    calls = []

    def respond(request):
        assert request.method == "POST"
        assert str(request.url) == "https://eco.taobao.com/router/rest"
        params = dict(parse_qsl(request.content.decode()))
        signature = params.pop("sign")
        message = "".join(key + value for key, value in sorted(params.items()))
        assert hmac.digest(b"secret", message.encode(), "md5").hex().upper() == signature
        assert params["language"] == "zh_CN"
        method = params["method"]
        calls.append(method)
        if method.endswith(".optional"):
            assert params["q"] == "手提包"
            assert params["adzone_id"] == "123"
            return httpx.Response(200, json={"ovs_tbk_material_item_optional_response": {
                "total_results": 99,
                "has_next": True,
                "next_page": 2,
                "result_list": {"material_item_summary_info": [{**listing, "publish_info": link}]},
            }})
        assert params["item_ids"] == "affiliate-id"
        if method.endswith(".info.get"):
            return httpx.Response(200, json={"ovs_tbk_material_item_info_get_response": {
                "items": {"material_item_summary_info": [listing]},
            }})
        return httpx.Response(200, json={"ovs_tbk_material_item_link_convert_response": {
            "items": {"material_item_summary_info": [{"item_id": "affiliate-id", "publish_info": link}]},
        }})

    async def run():
        async with httpx.AsyncClient(transport=httpx.MockTransport(respond)) as client:
            adapter = Taobao(client, "test-key", "secret", "123", "TW")
            page = await adapter.search("手提包", 1, 20)
            product = page.items[0]
            assert page.next_page == 2
            assert product.currency == "CNY" and product.price_min == Decimal("19.50")
            assert product.image_url == "https://img.alicdn.com/image.png"
            detail = await adapter.detail(product.id)
            assert detail.url == product.url
        assert calls == [
            "taobao.ovs.tbk.material.item.optional",
            "taobao.ovs.tbk.material.item.info.get",
            "taobao.ovs.tbk.material.item.link.convert",
        ]

    asyncio.run(run())


def test_1688_signed_keyword_search_and_detail():
    base = {"offerId": 123, "subject": "箱子", "subjectTrans": "Коробка", "minOrderQuantity": "2"}

    def respond(request):
        assert request.url.host == "gw.open.1688.com"
        assert request.method == "POST"
        params = dict(parse_qsl(request.content.decode()))
        signature = params.pop("_aop_signature")
        message = request.url.path.removeprefix("/openapi/") + "".join(k + v for k, v in sorted(params.items()))
        assert hmac.digest(b"secret", message.encode(), "sha1").hex().upper() == signature
        assert params["access_token"] == "test-token"
        if "keywordQuery" in request.url.path:
            assert json.loads(params["offerQueryParam"]) == {
                "keyword": "箱子", "beginPage": 1, "pageSize": 10, "country": "ru"
            }
            data = {"totalPage": 2, "data": [{**base, "priceInfo": {"price": "2.75"}}]}
        else:
            assert json.loads(params["offerDetailParam"]) == {"offerId": 123, "country": "ru", "outMemberId": "buyer"}
            data = {**base, "companyName": "Company", "productSaleInfo": {
                "priceRangeList": [{"price": "4.50"}, {"price": "2.30"}],
            }}
        return httpx.Response(200, json={"result": {"success": "true", "result": data}})

    async def run():
        async with httpx.AsyncClient(transport=httpx.MockTransport(respond)) as client:
            adapter = Alibaba1688(client, "test-key", "secret", "test-token", "buyer")
            page = await adapter.search("箱子", 1, 10)
            product = page.items[0]
            assert page.next_page == 2
            assert product.url == "https://detail.1688.com/offer/123.html"
            assert product.title_ru == "Коробка"
            assert product.moq == 2
            assert product.price_min == Decimal("2.75")
            detail = await adapter.detail(product.id)
            assert detail.price_min == Decimal("2.30") and detail.price_max == Decimal("4.50")
            assert detail.seller == "Company"

    asyncio.run(run())


def test_alibaba_signed_buyer_search_and_detail():
    def respond(request):
        assert request.url.host == "openapi-api.alibaba.com"
        assert request.method == "POST" and not request.url.query
        params = dict(parse_qsl(request.content.decode()))
        signature = params.pop("sign")
        message = request.url.path.removeprefix("/rest") + "".join(k + v for k, v in sorted(params.items()))
        assert hmac.digest(b"secret", message.encode(), "sha256").hex().upper() == signature
        assert params["sign_method"] == "sha256"
        item = {"product_id": 1234, "title": "Handbag"}
        url = "https://www.alibaba.com/product-detail/Handbag_1234.html"
        if request.url.path.endswith("/search"):
            assert json.loads(params["param0"]) == {
                "keyword": "handbag", "index": 1, "size": 20, "currency": "USD", "language": "en"
            }
            item.update(permalink=url, price="4.20-6.80", image={"main_image": "https://s01.alicdn.com/file.jpg"})
            result = {"code": "200", "data": {"products": [item], "pagination": {"page_count": 2}}}
        else:
            assert json.loads(params["query_req"]) == {
                "product_id": 1234, "currency": "USD", "language": "en-US"
            }
            item.update(detail_url=url, currency="USD", wholesale_trade={"price": "12.34"}, min_order_quantity="50")
            result = {"result_code": "200", "result_data": item}
        return httpx.Response(200, json={"code": "0", "result": result})

    async def run():
        async with httpx.AsyncClient(transport=httpx.MockTransport(respond)) as client:
            adapter = AlibabaCom(client, "test-key", "secret", "test-token")
            page = await adapter.search("handbag", 1, 20)
            product = page.items[0]
            assert product.price_min == Decimal("4.20") and product.price_max == Decimal("6.80")
            assert product.currency == "USD" and page.next_page == 2
            detail = await adapter.detail(product.id)
            assert detail.price_min == Decimal("12.34") and detail.moq == 50

    asyncio.run(run())


@pytest.mark.parametrize(("http_status", "payload", "status"), [
    (401, {}, "forbidden"),
    (429, {}, "rate_limited"),
    (200, {"error_response": {"code": 11}}, "forbidden"),
    (200, {"error_response": {"code": 7}}, "rate_limited"),
    (200, {"unexpected": []}, "unavailable"),
])
def test_taobao_rejects_upstream_failures(http_status, payload, status):
    transport = httpx.MockTransport(lambda _: httpx.Response(http_status, json=payload))

    async def run():
        async with httpx.AsyncClient(transport=transport) as client:
            adapter = Taobao(client, "test-key", "secret", "123", "TW")
            with pytest.raises(SourceError) as failure:
                await adapter.search("test", 1, 20)
            assert failure.value.status == status

    asyncio.run(run())
