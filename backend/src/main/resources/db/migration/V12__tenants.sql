CREATE TABLE organization (
    id VARCHAR(128) PRIMARY KEY,
    name VARCHAR(80) NOT NULL,
    app_id VARCHAR(128),
    bridge_hash VARCHAR(64) UNIQUE,
    created_at BIGINT NOT NULL
);
CREATE TABLE app_user (
    id VARCHAR(64) PRIMARY KEY,
    username VARCHAR(80) NOT NULL UNIQUE,
    password_hash VARCHAR(256) NOT NULL,
    platform_admin BOOLEAN NOT NULL DEFAULT FALSE,
    created_at BIGINT NOT NULL
);
CREATE TABLE membership (
    tenant_id VARCHAR(128) NOT NULL REFERENCES organization(id),
    user_id VARCHAR(64) NOT NULL REFERENCES app_user(id),
    role VARCHAR(16) NOT NULL CHECK (role IN ('ADMIN','MEMBER')),
    PRIMARY KEY(tenant_id,user_id)
);
CREATE TABLE login_session (
    token_hash VARCHAR(64) PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL REFERENCES app_user(id),
    expires_at BIGINT NOT NULL
);
CREATE TABLE invitation (
    token_hash VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(128) NOT NULL REFERENCES organization(id),
    role VARCHAR(16) NOT NULL CHECK (role IN ('ADMIN','MEMBER')),
    expires_at BIGINT NOT NULL
);
CREATE TABLE identity_guard (id INT PRIMARY KEY);
INSERT INTO identity_guard VALUES(1);
CREATE TABLE feishu_identity (
    tenant_id VARCHAR(128) NOT NULL REFERENCES organization(id),
    sender_id VARCHAR(128) NOT NULL,
    last_seen BIGINT NOT NULL,
    PRIMARY KEY(tenant_id,sender_id)
);
ALTER TABLE managed_account ADD COLUMN tenant_id VARCHAR(128);
ALTER TABLE managed_skill ADD COLUMN tenant_id VARCHAR(128);
ALTER TABLE delivery ADD COLUMN tenant_id VARCHAR(128);
CREATE INDEX managed_account_tenant ON managed_account(tenant_id);
CREATE INDEX managed_skill_tenant ON managed_skill(tenant_id);
CREATE INDEX delivery_tenant ON delivery(tenant_id,status,available_at);
