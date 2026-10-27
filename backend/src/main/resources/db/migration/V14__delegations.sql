CREATE TABLE delegation (
    id VARCHAR(64) PRIMARY KEY,
    task_id VARCHAR(64) NOT NULL REFERENCES task(id) ON DELETE CASCADE,
    name VARCHAR(160) NOT NULL,
    model VARCHAR(128) NOT NULL,
    status VARCHAR(24) NOT NULL,
    started_at BIGINT NOT NULL,
    finished_at BIGINT,
    input_tokens BIGINT,
    output_tokens BIGINT,
    total_tokens BIGINT,
    error_code VARCHAR(64)
);
CREATE INDEX delegation_task ON delegation(task_id,started_at);
