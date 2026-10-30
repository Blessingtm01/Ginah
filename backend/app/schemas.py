"""Shared API types. All timestamps are UTC and serialised with a trailing Z."""
from datetime import datetime
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, PlainSerializer

UtcDt = Annotated[
    datetime,
    PlainSerializer(lambda d: d.replace(tzinfo=None).isoformat(timespec="seconds") + "Z", return_type=str, when_used="json"),
]
Priority = Literal["CRITICAL", "HIGH", "MEDIUM", "LOW"]
TriState = Literal["YES", "NO", "UNKNOWN"]


class Out(BaseModel):
    model_config = ConfigDict(from_attributes=True)


class Page(BaseModel):
    items: list
    total: int
    page: int
    page_size: int


class Message(BaseModel):
    detail: str
