ALTER TABLE room
    ADD COLUMN IF NOT EXISTS lift_group_id BIGINT DEFAULT 630;

UPDATE room
SET lift_group_id = 630
WHERE lift_group_id IS NULL;
