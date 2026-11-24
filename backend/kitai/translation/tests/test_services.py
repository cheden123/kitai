from io import BytesIO

from fastapi import HTTPException
from PIL import Image
import pytest

from kitai.translation.router import parse_rates, recognize, validate_pair


def test_rates_convert_nominal_and_preserve_decimal():
    rates = parse_rates(b'''
    <ValCurs Date="24.11.2026">
      <Valute><CharCode>CNY</CharCode><Nominal>10</Nominal><Value>112,50</Value></Valute>
      <Valute><CharCode>USD</CharCode><Nominal>1</Nominal><Value>82,1250</Value></Valute>
    </ValCurs>
    ''')
    assert rates["date"] == "2026-11-24"
    assert rates["rates"] == {"CNY": "11.25", "USD": "82.1250"}


def test_unsupported_translation_pair():
    with pytest.raises(HTTPException) as error:
        validate_pair("ru", "en")
    assert error.value.status_code == 422


def test_ocr_combines_words_and_bounds(monkeypatch):
    image = BytesIO()
    Image.new("RGB", (30, 20)).save(image, format="PNG")
    tsv = (
        "header\n"
        "5\t1\t1\t1\t1\t1\t2\t3\t5\t4\t90\thello\n"
        "5\t1\t1\t1\t1\t2\t9\t4\t8\t5\t90\tworld\n"
    ).encode("utf-8")

    class Output:
        stdout = tsv

    monkeypatch.setattr("kitai.translation.router.subprocess.run", lambda *args, **kwargs: Output())
    result = recognize("tesseract", image.getvalue(), "en")
    assert result == {
        "width": 30,
        "height": 20,
        "blocks": [{"text": "hello world", "translation": None, "box": [2, 3, 15, 6]}],
    }


def test_openrouter_translation_and_cache(monkeypatch):
    import asyncio
    import json

    import httpx

    from kitai.translation.router import translate_with_engine

    monkeypatch.setenv("KITAI_OPENROUTER_API_KEY", "test-key")
    monkeypatch.delenv("KITAI_OPENROUTER_MODEL", raising=False)
    requests = []

    def respond(request):
        requests.append(request)
        assert str(request.url) == "https://openrouter.ai/api/v1/chat/completions"
        assert request.headers["Authorization"] == "Bearer test-key"
        body = json.loads(request.content)
        assert body["model"] == "openrouter/free"
        assert body["messages"][-1] == {"role": "user", "content": "Тестовый рюкзак"}
        return httpx.Response(200, json={"choices": [
            {"finish_reason": "stop", "message": {"content": "测试背包"}}
        ]})

    client = httpx.AsyncClient
    monkeypatch.setattr(httpx, "AsyncClient", lambda **options: client(
        transport=httpx.MockTransport(respond), **options
    ))

    async def run():
        for _ in range(2):
            assert await translate_with_engine("Тестовый рюкзак", "ru", "zh") == ("测试背包", "openrouter")

    asyncio.run(run())
    assert len(requests) == 1
