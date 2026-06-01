-- Houses are permanent physical residences; ownership changes via UPDATE, never deletion
-- (grill Q10). Remove the soft-delete flag.
ALTER TABLE house DROP COLUMN deleted;
