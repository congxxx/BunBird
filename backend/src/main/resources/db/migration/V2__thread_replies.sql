ALTER TABLE ingress ADD COLUMN chat_type VARCHAR(16) NOT NULL DEFAULT 'p2p';
ALTER TABLE delivery ADD COLUMN reply_in_thread BOOLEAN NOT NULL DEFAULT FALSE;

-- Recover group classification for pre-upgrade tasks using the existing Topic key contract.
UPDATE ingress SET chat_type='group'
WHERE task_id IN (
    SELECT t.id FROM task t JOIN topic p ON p.id=t.topic_id
    WHERE p.external_key NOT LIKE 'dm:%'
);
UPDATE delivery SET reply_in_thread=TRUE
WHERE task_id IN (
    SELECT t.id FROM task t JOIN topic p ON p.id=t.topic_id
    WHERE p.external_key NOT LIKE 'dm:%'
);
