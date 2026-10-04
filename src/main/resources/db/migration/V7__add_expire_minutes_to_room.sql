ALTER TABLE room
    ADD COLUMN IF NOT EXISTS expire_minutes INTEGER DEFAULT 15;

UPDATE room
SET expire_minutes = 15
WHERE expire_minutes IS NULL;
