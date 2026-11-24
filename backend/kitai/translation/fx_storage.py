import asyncio
import json
import logging
import os

import psycopg
from psycopg.types.json import Jsonb
from redis.asyncio import Redis
from redis.exceptions import RedisError

logger = logging.getLogger(__name__)
REDIS_KEY = "kitai:fx:cbr:v1"
REDIS_TTL = 3600


async def connect():
    return await psycopg.AsyncConnection.connect(
        os.environ["KITAI_POSTGRES_DSN"],
        password=os.getenv("KITAI_POSTGRES_PASSWORD"),
        connect_timeout=2,
    )


async def load_rates():
    redis_url = os.getenv("KITAI_REDIS_URL")
    if redis_url:
        try:
            async with Redis.from_url(redis_url, socket_timeout=1, socket_connect_timeout=1) as cache:
                cached = await cache.get(REDIS_KEY)
            if cached:
                return json.loads(cached)
        except (RedisError, ValueError):
            logger.warning("FX Redis read failed")
    if os.getenv("KITAI_POSTGRES_DSN"):
        try:
            async with asyncio.timeout(3), await connect() as db:
                cursor = await db.execute("SELECT payload FROM kitai_fx_cache WHERE source = 'CBR'")
                row = await cursor.fetchone()
                return row[0] if row else None
        except (psycopg.Error, TimeoutError):
            logger.warning("FX PostgreSQL read failed")
    return None


async def save_rates(snapshot):
    if os.getenv("KITAI_POSTGRES_DSN"):
        try:
            async with asyncio.timeout(3), await connect() as db:
                await db.execute(
                    """
                    INSERT INTO kitai_fx_cache (source, payload, fetched_at)
                    VALUES ('CBR', %s, %s)
                    ON CONFLICT (source) DO UPDATE
                    SET payload = EXCLUDED.payload, fetched_at = EXCLUDED.fetched_at
                    WHERE kitai_fx_cache.fetched_at <= EXCLUDED.fetched_at
                    """,
                    (Jsonb(snapshot), snapshot["fetched_at"]),
                )
        except (psycopg.Error, TimeoutError):
            logger.warning("FX PostgreSQL write failed")
    redis_url = os.getenv("KITAI_REDIS_URL")
    if redis_url:
        try:
            async with Redis.from_url(redis_url, socket_timeout=1, socket_connect_timeout=1) as cache:
                await cache.set(REDIS_KEY, json.dumps(snapshot), ex=REDIS_TTL)
        except RedisError:
            logger.warning("FX Redis write failed")
