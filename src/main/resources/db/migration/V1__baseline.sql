-- FishMatrix 比赛域基线：综合排行榜、赛事、淘汰/积分赛、五子棋小局、落子、比赛聊天、全站通知。
-- 业务容器只持有 DML 账号；本脚本由 db-migrate-game 一次性任务使用迁移账号执行。

CREATE TABLE IF NOT EXISTS game_player_stat (
    user_id VARCHAR(64) NOT NULL,
    nickname VARCHAR(64) NOT NULL,
    matches INT NOT NULL DEFAULT 0,
    wins INT NOT NULL DEFAULT 0,
    losses INT NOT NULL DEFAULT 0,
    points INT NOT NULL DEFAULT 0,
    championships INT NOT NULL DEFAULT 0,
    best_score BIGINT NOT NULL DEFAULT 0,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (user_id),
    KEY idx_player_points (points, wins, championships)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS game_tournament (
    id VARCHAR(36) NOT NULL,
    name VARCHAR(64) NOT NULL,
    game_id VARCHAR(32) NOT NULL,
    format VARCHAR(8) NOT NULL,
    bracket_type VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL,
    creator_user_id VARCHAR(64) NOT NULL,
    creator_nickname VARCHAR(64) NOT NULL,
    target_players INT NOT NULL,
    invite_deadline DATETIME(3) NOT NULL,
    current_round INT NOT NULL DEFAULT 0,
    total_rounds INT NOT NULL DEFAULT 0,
    champion_user_id VARCHAR(64) NULL,
    champion_nickname VARCHAR(64) NULL,
    created_at DATETIME(3) NOT NULL,
    started_at DATETIME(3) NULL,
    finished_at DATETIME(3) NULL,
    PRIMARY KEY (id),
    KEY idx_tournament_status (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS game_tournament_player (
    tournament_id VARCHAR(36) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    nickname VARCHAR(64) NOT NULL,
    seed INT NOT NULL DEFAULT 0,
    status VARCHAR(16) NOT NULL,
    invited_at DATETIME(3) NOT NULL,
    responded_at DATETIME(3) NULL,
    PRIMARY KEY (tournament_id, user_id),
    KEY idx_tournament_player_user (user_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS game_match (
    id VARCHAR(36) NOT NULL,
    tournament_id VARCHAR(36) NOT NULL,
    round_no INT NOT NULL,
    slot_no INT NOT NULL,
    stage VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL,
    player1_user_id VARCHAR(64) NULL,
    player1_nickname VARCHAR(64) NULL,
    player2_user_id VARCHAR(64) NULL,
    player2_nickname VARCHAR(64) NULL,
    player1_score INT NOT NULL DEFAULT 0,
    player2_score INT NOT NULL DEFAULT 0,
    winner_user_id VARCHAR(64) NULL,
    next_match_id VARCHAR(36) NULL,
    created_at DATETIME(3) NOT NULL,
    started_at DATETIME(3) NULL,
    finished_at DATETIME(3) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_match_slot (tournament_id, round_no, slot_no),
    KEY idx_match_tournament (tournament_id, round_no, slot_no),
    KEY idx_match_player (player1_user_id, player2_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS game_round (
    id VARCHAR(36) NOT NULL,
    match_id VARCHAR(36) NOT NULL,
    game_no INT NOT NULL,
    status VARCHAR(16) NOT NULL,
    black_user_id VARCHAR(64) NOT NULL,
    white_user_id VARCHAR(64) NOT NULL,
    turn_user_id VARCHAR(64) NOT NULL,
    winner_user_id VARCHAR(64) NULL,
    move_count INT NOT NULL DEFAULT 0,
    last_move_at DATETIME(3) NOT NULL,
    rest_until DATETIME(3) NULL,
    ready_user_ids VARCHAR(512) NOT NULL DEFAULT '',
    started_at DATETIME(3) NOT NULL,
    finished_at DATETIME(3) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_round_game (match_id, game_no),
    KEY idx_round_match (match_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS game_move (
    round_id VARCHAR(36) NOT NULL,
    move_no INT NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    x INT NOT NULL,
    y INT NOT NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (round_id, move_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS game_chat_message (
    id VARCHAR(36) NOT NULL,
    tournament_id VARCHAR(36) NOT NULL,
    match_id VARCHAR(36) NULL,
    channel VARCHAR(16) NOT NULL,
    from_user_id VARCHAR(64) NOT NULL,
    from_nickname VARCHAR(64) NOT NULL,
    to_user_id VARCHAR(64) NULL,
    content VARCHAR(2000) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_chat_room (tournament_id, match_id, channel, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS game_notification (
    id VARCHAR(36) NOT NULL,
    type VARCHAR(16) NOT NULL,
    tournament_id VARCHAR(36) NOT NULL,
    match_id VARCHAR(36) NULL,
    title VARCHAR(128) NOT NULL,
    message VARCHAR(256) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    expires_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_notification_time (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
