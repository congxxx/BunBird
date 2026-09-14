-- Freeze the task state at notification creation; later state changes must not rewrite it.
ALTER TABLE delivery ADD COLUMN task_state VARCHAR(32);
