from datetime import date, datetime
from decimal import Decimal
from typing import Literal

from pydantic import BaseModel, Field


SourceId = Literal["taobao", "1688", "alibaba"]
SourceStatus = Literal[
    "available", "configured", "not_configured", "unsupported", "forbidden", "rate_limited", "unavailable"
]


class SourceOutcome(BaseModel):
    id: SourceId
    status: SourceStatus


class SourceInfo(SourceOutcome):
    name: str
    supports_search: bool
    supports_detail: bool
    homepage_url: str


class SourcesResponse(BaseModel):
    sources: list[SourceInfo]


class Product(BaseModel):
    id: str
    source: SourceId
    title: str
    url: str
    observed_at: datetime
    title_ru: str | None = None
    image_url: str | None = None
    price_min: Decimal | None = Field(default=None, ge=0)
    price_max: Decimal | None = Field(default=None, ge=0)
    currency: str | None = None
    price_rub: Decimal | None = Field(default=None, ge=0)
    fx_date: date | None = None
    seller: str | None = None
    moq: Decimal | None = Field(default=None, gt=0)


class SearchResponse(BaseModel):
    items: list[Product]
    sources: list[SourceOutcome]
    next_cursor: str | None = None
