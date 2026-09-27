CREATE TABLE session_model (
 topic_id VARCHAR(64) NOT NULL REFERENCES topic(id),
 sender_id VARCHAR(128) NOT NULL,
 account_id VARCHAR(64) NOT NULL,
 model VARCHAR(128) NOT NULL,
 PRIMARY KEY(topic_id,sender_id)
);
ALTER TABLE task ADD COLUMN requested_account_id VARCHAR(64);
ALTER TABLE task ADD COLUMN requested_model VARCHAR(128);
