-- Задания на расчёт. Сами файлы (вход/выход) лежат на диске в app.storage.dir, здесь — метаданные.
CREATE TABLE jobs (
    id              UUID PRIMARY KEY,
    status          VARCHAR(32)  NOT NULL,          -- UPLOADED / RUNNING / DONE / FAILED
    mode            VARCHAR(16)  NOT NULL,          -- PLAN_2D / DEPTH_3D
    input_path      TEXT         NOT NULL,
    input_size      BIGINT,
    result_path     TEXT,
    error_message   TEXT,
    created_at      TIMESTAMP    NOT NULL DEFAULT now(),
    started_at      TIMESTAMP,
    finished_at     TIMESTAMP
);

CREATE INDEX jobs_created_at_idx ON jobs (created_at DESC);
