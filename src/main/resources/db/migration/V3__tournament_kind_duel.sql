-- 区分多人演练与一对一约战。约战复用比赛编排与落子引擎，但不出现在演练大厅、
-- 不计排行榜、不发全站播报，也不提供聊天与观战。
ALTER TABLE game_tournament
    ADD COLUMN kind VARCHAR(16) NOT NULL DEFAULT 'TOURNAMENT' AFTER game_id,
    ADD KEY idx_tournament_kind_status (kind, status, created_at);
