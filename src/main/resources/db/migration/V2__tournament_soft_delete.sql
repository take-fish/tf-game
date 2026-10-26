ALTER TABLE game_tournament
    ADD COLUMN deleted_at DATETIME(3) NULL AFTER finished_at,
    ADD KEY idx_tournament_deleted (deleted_at, created_at);
