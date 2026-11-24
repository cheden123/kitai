CREATE TABLE IF NOT EXISTS kitai_fx_cache (
    source text PRIMARY KEY,
    payload jsonb NOT NULL,
    fetched_at double precision NOT NULL
);
