from datetime import UTC, datetime
from decimal import Decimal

from fastapi import HTTPException
from fastapi.testclient import TestClient

from kitai.main import OCR_BODY_LIMIT, TRANSLATE_BODY_LIMIT, create_app
from kitai.models import Product
from kitai.upstream import Page, SourceError


class Adapter:
    def __init__(self, source, ids=(), error=None):
        self.source = source
        self.ids = list(ids)
        self.error = error
        self.queries = []

    def product(self, item_id):
        return Product(
            id=item_id,
            source=self.source,
            title="Товар",
            url="https://www.taobao.com/",
            currency="CNY",
            price_min=Decimal("12.20"),
            observed_at=datetime.now(UTC),
        )

    async def search(self, query, page, size):
        self.queries.append(query)
        if self.error:
            raise SourceError(self.error)
        start = (page - 1) * size
        end = start + size
        return Page([self.product(i) for i in self.ids[start:end]], page + 1 if end < len(self.ids) else None)

    async def detail(self, product_id):
        if self.error:
            raise SourceError(self.error)
        return self.product(product_id) if product_id in self.ids else None


async def rates():
    return {"date": "2026-11-24", "rates": {"CNY": "12.5", "USD": "100"}}


def test_unconfigured_sources_and_invalid_queries():
    with TestClient(create_app({})) as client:
        assert client.get("/health").json() == {"status": "ok"}
        statuses = client.get("/api/v1/sources").json()["sources"]
        assert [(entry["id"], entry["status"]) for entry in statuses] == [
            ("taobao", "not_configured"), ("1688", "not_configured"), ("alibaba", "not_configured")
        ]
        result = client.get("/api/v1/search", params={"q": "платье", "source": "1688"})
        assert result.json() == {
            "items": [], "sources": [{"id": "1688", "status": "not_configured"}], "next_cursor": None
        }
        assert client.get("/api/v1/search", params={"q": " "}).status_code == 422
        assert client.get("/api/v1/search", params={"q": "a", "limit": 51}).status_code == 422
        assert client.get("/api/v1/search", params={"q": "a", "source": "bogus"}).status_code == 422
        assert client.get("/api/v1/products/taobao/example").status_code == 503


def test_search_pages_preserve_each_source_and_require_same_query():
    taobao = Adapter("taobao", ["a", "b"])
    source1688 = Adapter("1688", ["1", "2"])
    app = create_app({"taobao": taobao, "1688": source1688}, rates)
    with TestClient(app) as client:
        params = [("q", " 裙子 "), ("limit", 1), ("source", "1688"), ("source", "taobao")]
        page = client.get("/api/v1/search", params=params).json()
        items = page["items"]
        cursor = page["next_cursor"]
        changed = [(key, value) for key, value in params if key != "q"] + [("q", "другой"), ("cursor", cursor)]
        assert client.get("/api/v1/search", params=changed).status_code == 422
        assert client.get("/api/v1/search", params=params + [("cursor", cursor + "!")]).status_code == 422
        for _ in range(3):
            page = client.get("/api/v1/search", params=params + [("cursor", page["next_cursor"])]).json()
            items.extend(page["items"])
        assert page["next_cursor"] is None
        assert [item["id"] for item in items] == ["a", "1", "b", "2"]
        assert all(item["price_min"] == "12.20" for item in items)
        assert all(item["price_rub"] == "152.50" and item["fx_date"] == "2026-11-24" for item in items)
        assert all(query == "裙子" for query in taobao.queries + source1688.queries)


def test_source_and_rates_failures_keep_other_results():
    async def unavailable_rates():
        raise HTTPException(503)

    app = create_app({"taobao": Adapter("taobao", ["a"]), "1688": Adapter("1688", error="rate_limited")}, unavailable_rates)
    with TestClient(app) as client:
        configured = client.get("/api/v1/sources").json()["sources"]
        assert [item["status"] for item in configured] == ["configured", "configured", "not_configured"]
        assert client.get("/api/v1/products/1688/bad-id").status_code == 404
        assert client.get("/api/v1/sources").json()["sources"][1]["status"] == "configured"
        result = client.get("/api/v1/search", params={"q": "платье"}).json()
        assert result["items"][0]["id"] == "a"
        assert result["items"][0]["price_rub"] is None
        assert [item["status"] for item in result["sources"]] == ["available", "rate_limited", "not_configured"]
        assert client.get("/api/v1/sources").json()["sources"][1]["status"] == "rate_limited"
        assert client.get("/api/v1/products/taobao/a").json()["title"] == "Товар"
        assert client.get("/api/v1/products/taobao/b").status_code == 404
        assert client.get("/api/v1/products/1688/123").status_code == 429


def test_ocr_caps_declared_and_streaming_request_sizes():
    def chunks():
        for _ in range(OCR_BODY_LIMIT // 65536 + 1):
            yield bytes(65536)

    with TestClient(create_app({})) as client:
        headers = {"Content-Length": str(OCR_BODY_LIMIT + 1)}
        assert client.post("/api/v1/ocr", headers=headers).status_code == 413
        assert client.post("/api/v1/ocr", content=chunks()).status_code == 413
        assert client.post("/api/v1/ocr", files={"file": ("broken.png", b"broken", "image/png")}).status_code == 422


def test_translate_caps_json_before_parsing():
    with TestClient(create_app({})) as client:
        response = client.post("/api/v1/translate", content=iter([b"x" * (TRANSLATE_BODY_LIMIT + 1)]))
        assert response.status_code == 413


def test_configured_api_token_protects_api_and_disables_docs(monkeypatch):
    monkeypatch.setenv("KITAI_API_TOKEN", "test-only-token")
    with TestClient(create_app({})) as client:
        assert client.get("/health").status_code == 200
        assert client.get("/docs").status_code == 404
        assert client.get("/openapi.json").status_code == 404
        failure = client.get("/api/v1/sources", headers={"Authorization": "Bearer invalid"})
        assert failure.status_code == 401 and failure.headers["www-authenticate"] == "Bearer"
        assert client.post("/api/v1/ocr", headers={"Content-Length": str(OCR_BODY_LIMIT + 1)}).status_code == 401
        assert client.get("/api/v1/sources", headers={"Authorization": "Bearer test-only-token"}).status_code == 200


def test_search_translates_russian_for_chinese_sources_and_cursors_preserve_query():
    calls = []

    async def translate(text, source, target):
        calls.append((text, source, target))
        return "手提包"

    taobao = Adapter("taobao", ["a", "b"])
    alibaba = Adapter("alibaba", [])
    app = create_app({"taobao": taobao, "alibaba": alibaba}, rates, translate)
    with TestClient(app) as client:
        params = {"q": "сумка", "limit": 1}
        page = client.get("/api/v1/search", params=params).json()
        second = client.get("/api/v1/search", params={**params, "cursor": page["next_cursor"]})
        assert second.json()["items"][0]["id"] == "b"
    assert calls == [("сумка", "ru", "zh")]
    assert taobao.queries == ["手提包", "手提包"]
    assert alibaba.queries == ["сумка"]


def test_search_falls_back_to_original_and_detail_translates_missing_title():
    class ChineseAdapter(Adapter):
        def product(self, item_id):
            return super().product(item_id).model_copy(update={"title": "手提包"})

    async def translate(text, source, target):
        if (source, target) == ("ru", "zh"):
            raise HTTPException(503)
        assert (text, source, target) == ("手提包", "zh", "ru")
        return "Сумка"

    taobao = ChineseAdapter("taobao", ["a"])
    app = create_app({"taobao": taobao}, rates, translate)
    with TestClient(app) as client:
        listing = client.get("/api/v1/search", params={"q": "сумка"}).json()["items"][0]
        assert listing["title_ru"] is None
        assert taobao.queries == ["сумка"]
        detail = client.get("/api/v1/products/taobao/a").json()
        assert detail["title"] == "手提包" and detail["title_ru"] == "Сумка"
