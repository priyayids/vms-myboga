DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'visitor') THEN
        ALTER TABLE visitor RENAME TO visitors_legacy;
    END IF;
END$$;
