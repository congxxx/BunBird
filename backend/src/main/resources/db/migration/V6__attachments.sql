CREATE TABLE attachment (
    id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(128) NOT NULL,
    app_id VARCHAR(128) NOT NULL,
    chat_id VARCHAR(128) NOT NULL,
    sender_id VARCHAR(128) NOT NULL,
    scope_key VARCHAR(256) NOT NULL,
    message_id VARCHAR(128) NOT NULL,
    file_name VARCHAR(160) NOT NULL,
    file_size BIGINT NOT NULL,
    sha256 VARCHAR(64) NOT NULL,
    task_id VARCHAR(64) REFERENCES task(id),
    created_at BIGINT NOT NULL,
    UNIQUE(tenant_id,app_id,message_id)
);
CREATE INDEX attachment_pending ON attachment(tenant_id,chat_id,sender_id,task_id);
