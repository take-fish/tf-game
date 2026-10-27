-- 实时竞技场：一个赛事可以选择不同演练剧本，小局用 state_json 保存服务端权威快照。
-- 五子棋小局继续使用 game_move；竞技场小局没有轮次，turn_user_id 允许为空。
ALTER TABLE game_round
    ADD COLUMN game_id VARCHAR(32) NOT NULL DEFAULT 'heatmap-gomoku' AFTER match_id,
    ADD COLUMN attacker_user_id VARCHAR(64) NULL AFTER white_user_id,
    ADD COLUMN state_json MEDIUMTEXT NULL AFTER ready_user_ids,
    ADD COLUMN state_version INT NOT NULL DEFAULT 0 AFTER state_json,
    ADD COLUMN ends_at DATETIME(3) NULL AFTER started_at,
    MODIFY COLUMN turn_user_id VARCHAR(64) NULL,
    ADD KEY idx_round_game_status (game_id, status);
