ALTER TABLE jobs ADD COLUMN diagnostics_json TEXT;
ALTER TABLE jobs ADD COLUMN error_code VARCHAR(64);
