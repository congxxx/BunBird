CREATE TABLE managed_account (
 id VARCHAR(64) PRIMARY KEY,
 name VARCHAR(80) NOT NULL,
 mode VARCHAR(32) NOT NULL,
 model VARCHAR(128) NOT NULL,
 base_url VARCHAR(1024) NOT NULL,
 secret_enc TEXT NOT NULL,
 priority INT NOT NULL,
 updated_at BIGINT NOT NULL
);
CREATE TABLE account_catalog_guard (id INT PRIMARY KEY);
INSERT INTO account_catalog_guard(id) VALUES(1);
