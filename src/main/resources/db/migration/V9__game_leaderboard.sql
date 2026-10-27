-- 分游戏排行榜：综合榜继续使用 game_player_stat，本表按 game_id 累计棋类赛事战绩。
-- 历史综合数据包含打砖块分数，无法准确拆分，因此分游戏榜从本次迁移后开始累计。

CREATE TABLE IF NOT EXISTS game_player_game_stat (
    game_id VARCHAR(32) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    nickname VARCHAR(64) NOT NULL,
    matches INT NOT NULL DEFAULT 0,
    wins INT NOT NULL DEFAULT 0,
    losses INT NOT NULL DEFAULT 0,
    points INT NOT NULL DEFAULT 0,
    championships INT NOT NULL DEFAULT 0,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (game_id, user_id),
    KEY idx_game_player_points (game_id, points, wins, championships)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
