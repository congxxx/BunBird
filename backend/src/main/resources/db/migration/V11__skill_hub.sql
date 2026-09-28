CREATE TABLE managed_skill (
 id VARCHAR(64) PRIMARY KEY,
 name VARCHAR(80) NOT NULL,
 source_type VARCHAR(16) NOT NULL,
 source TEXT NOT NULL,
 revision VARCHAR(64) NOT NULL,
 enabled BOOLEAN NOT NULL,
 files_json TEXT NOT NULL,
 instructions TEXT NOT NULL,
 created_at BIGINT NOT NULL
);
