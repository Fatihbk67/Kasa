-- Local development only. The application role must not own tables or bypass RLS.
CREATE ROLE kasa_app LOGIN PASSWORD 'kasa_app' NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE;
