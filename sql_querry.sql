-- ============================================================
-- Таблицы Directum НЕ трогаем: сервис их только читает (SELECT).
-- ============================================================
-- Очередь задач: только служебные поля, БЕЗ бинарника и БЕЗ результата.
-- file_id — ссылка на dvsys_files.FileID. Если в вашей БД FileID имеет тип
-- UNIQUEIDENTIFIER, можно оставить NVARCHAR(64) — сравнение работать будет.
CREATE TABLE dbo.doc_documents (
                                   id            BIGINT IDENTITY(1,1) PRIMARY KEY,
                                   file_id       NVARCHAR(64)  NOT NULL,           -- → dvsys_files.FileID
                                   file_name     NVARCHAR(255) NOT NULL,
                                   status        TINYINT       NOT NULL DEFAULT 0, -- 0=NEW,1=PROCESSING,2=DONE,3=ERROR
                                   locked_by     NVARCHAR(100) NULL,
                                   locked_at     DATETIME2     NULL,
                                   error_message NVARCHAR(2000) NULL,
                                   retry_count   INT           NOT NULL DEFAULT 0, -- число неудачных попыток
                                   created_at    DATETIME2     NOT NULL DEFAULT SYSUTCDATETIME(),
                                   updated_at    DATETIME2     NULL
);
-- Идемпотентность ингеста: один файл = одна задача, независимо от числа инстансов
CREATE UNIQUE INDEX uq_doc_documents_file ON dbo.doc_documents (file_id);
-- Критично для конкурентности: поиск по статусу без сканов таблицы
CREATE INDEX ix_doc_documents_status
    ON dbo.doc_documents (status) INCLUDE (locked_at, retry_count, updated_at);
-- Результаты в отдельной таблице: очередь не пухнет от больших XML
CREATE TABLE dbo.doc_results (
                                 id         BIGINT IDENTITY(1,1) PRIMARY KEY,
                                 doc_id     BIGINT        NOT NULL,              -- → doc_documents.id
                                 file_id    NVARCHAR(64)  NOT NULL,
                                 file_name  NVARCHAR(255) NOT NULL,
                                 xml_data   VARBINARY(MAX) NULL,
                                 created_by NVARCHAR(100) NULL,                  -- какой инстанс обработал
                                 created_at DATETIME2     NOT NULL DEFAULT SYSUTCDATETIME()
);
CREATE UNIQUE INDEX uq_doc_results_doc ON dbo.doc_results (doc_id);