CREATE TABLE IF NOT EXISTS game_player_stat (
    user_id VARCHAR(64) NOT NULL,
    nickname VARCHAR(64) NOT NULL,
    matches INT NOT NULL DEFAULT 0,
    wins INT NOT NULL DEFAULT 0,
    losses INT NOT NULL DEFAULT 0,
    points INT NOT NULL DEFAULT 0,
    championships INT NOT NULL DEFAULT 0,
    best_score BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (user_id)
);

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
    invite_deadline TIMESTAMP(3) NOT NULL,
    current_round INT NOT NULL DEFAULT 0,
    total_rounds INT NOT NULL DEFAULT 0,
    champion_user_id VARCHAR(64) NULL,
    champion_nickname VARCHAR(64) NULL,
    created_at TIMESTAMP(3) NOT NULL,
    started_at TIMESTAMP(3) NULL,
    finished_at TIMESTAMP(3) NULL,
    deleted_at TIMESTAMP(3) NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS game_tournament_player (
    tournament_id VARCHAR(36) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    nickname VARCHAR(64) NOT NULL,
    seed INT NOT NULL DEFAULT 0,
    status VARCHAR(16) NOT NULL,
    invited_at TIMESTAMP(3) NOT NULL,
    responded_at TIMESTAMP(3) NULL,
    PRIMARY KEY (tournament_id, user_id)
);

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
    created_at TIMESTAMP(3) NOT NULL,
    started_at TIMESTAMP(3) NULL,
    finished_at TIMESTAMP(3) NULL,
    PRIMARY KEY (id),
    UNIQUE (tournament_id, round_no, slot_no)
);

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
    last_move_at TIMESTAMP(3) NOT NULL,
    rest_until TIMESTAMP(3) NULL,
    ready_user_ids VARCHAR(512) NOT NULL DEFAULT '',
    started_at TIMESTAMP(3) NOT NULL,
    finished_at TIMESTAMP(3) NULL,
    PRIMARY KEY (id),
    UNIQUE (match_id, game_no)
);

CREATE TABLE IF NOT EXISTS game_move (
    round_id VARCHAR(36) NOT NULL,
    move_no INT NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    x INT NOT NULL,
    y INT NOT NULL,
    created_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (round_id, move_no)
);

CREATE TABLE IF NOT EXISTS game_chat_message (
    id VARCHAR(36) NOT NULL,
    tournament_id VARCHAR(36) NOT NULL,
    match_id VARCHAR(36) NULL,
    channel VARCHAR(16) NOT NULL,
    from_user_id VARCHAR(64) NOT NULL,
    from_nickname VARCHAR(64) NOT NULL,
    to_user_id VARCHAR(64) NULL,
    content VARCHAR(2000) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS game_notification (
    id VARCHAR(36) NOT NULL,
    type VARCHAR(16) NOT NULL,
    tournament_id VARCHAR(36) NOT NULL,
    match_id VARCHAR(36) NULL,
    title VARCHAR(128) NOT NULL,
    message VARCHAR(256) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL,
    expires_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_player_points ON game_player_stat (points, wins, championships);
CREATE INDEX IF NOT EXISTS idx_tournament_status ON game_tournament (status, created_at);
CREATE INDEX IF NOT EXISTS idx_tournament_deleted ON game_tournament (deleted_at, created_at);
CREATE INDEX IF NOT EXISTS idx_tournament_player_user ON game_tournament_player (user_id, status);
CREATE INDEX IF NOT EXISTS idx_match_tournament ON game_match (tournament_id, round_no, slot_no);
CREATE INDEX IF NOT EXISTS idx_match_player ON game_match (player1_user_id, player2_user_id);
CREATE INDEX IF NOT EXISTS idx_round_match ON game_round (match_id);
CREATE INDEX IF NOT EXISTS idx_chat_room ON game_chat_message (tournament_id, match_id, channel, created_at);
CREATE INDEX IF NOT EXISTS idx_notification_time ON game_notification (created_at);
