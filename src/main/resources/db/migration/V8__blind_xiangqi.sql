-- 盲棋：赛事摆法、公开棋谱扩展、服务端私有棋子状态。
-- 反面棋的真实颜色与兵种只存放在 game_blind_piece，查询公开视图时不得直接下发。

ALTER TABLE game_tournament
    ADD COLUMN blind_layout VARCHAR(24) NULL AFTER game_id;

ALTER TABLE game_move
    ADD COLUMN action VARCHAR(16) NOT NULL DEFAULT 'PLACE' AFTER y,
    ADD COLUMN from_x INT NULL AFTER action,
    ADD COLUMN from_y INT NULL AFTER from_x,
    ADD COLUMN piece_type VARCHAR(16) NULL AFTER from_y,
    ADD COLUMN piece_color VARCHAR(8) NULL AFTER piece_type,
    ADD COLUMN captured_piece_type VARCHAR(16) NULL AFTER piece_color,
    ADD COLUMN captured_piece_color VARCHAR(8) NULL AFTER captured_piece_type;

CREATE TABLE IF NOT EXISTS game_blind_piece (
    round_id VARCHAR(36) NOT NULL,
    piece_id VARCHAR(36) NOT NULL,
    color VARCHAR(8) NOT NULL,
    piece_type VARCHAR(16) NOT NULL,
    x INT NOT NULL,
    y INT NOT NULL,
    revealed BOOLEAN NOT NULL DEFAULT FALSE,
    alive BOOLEAN NOT NULL DEFAULT TRUE,
    PRIMARY KEY (round_id, piece_id),
    KEY idx_blind_piece_round (round_id, alive)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
