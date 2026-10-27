-- 集群巡检对抗（2D 轻 FPS）：休闲房间、赛事房间与单局战绩。
-- 实时权威状态保存在 state_json，每秒快照，服务重启后可恢复仍在进行的房间。
CREATE TABLE game_exercise_room (
    id VARCHAR(36) NOT NULL COMMENT '房间 ID；赛事小局直接使用 roundId',
    room_code VARCHAR(12) NOT NULL COMMENT '加入房间的短码',
    mode VARCHAR(16) NOT NULL COMMENT '模式：FFA 混战 / TEAM 团队 / TRAINING 单人训练',
    source VARCHAR(16) NOT NULL COMMENT '来源：CASUAL 休闲 / TOURNAMENT 赛事',
    status VARCHAR(16) NOT NULL COMMENT '状态：WAITING / RUNNING / FINISHED / CANCELLED',
    match_id VARCHAR(36) NULL COMMENT '赛事比赛 ID；休闲房间为空',
    owner_user_id VARCHAR(64) NULL COMMENT '房主用户 ID；赛事房间为第一位选手',
    max_players INT NOT NULL COMMENT '真人席位上限',
    duration_seconds INT NOT NULL COMMENT '单局时长秒数',
    state_json MEDIUMTEXT NULL COMMENT '服务端权威状态快照（JSON）',
    state_version INT NOT NULL DEFAULT 0 COMMENT '状态版本号，防止旧帧回退',
    winner_user_id VARCHAR(64) NULL COMMENT '获胜用户 ID',
    winner_team VARCHAR(8) NULL COMMENT '获胜队伍 A/B；混战与训练为空',
    finish_reason VARCHAR(255) NULL COMMENT '结算说明',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间',
    started_at DATETIME(3) NULL COMMENT '开始时间',
    finished_at DATETIME(3) NULL COMMENT '结束时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_exercise_room_code (room_code),
    KEY idx_exercise_room_status (status, created_at),
    KEY idx_exercise_room_match (match_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '集群巡检对抗房间';

CREATE TABLE game_exercise_member (
    room_id VARCHAR(36) NOT NULL COMMENT '房间 ID',
    user_id VARCHAR(64) NOT NULL COMMENT '用户 ID；训练靶使用 bot- 前缀',
    nickname VARCHAR(64) NOT NULL COMMENT '名号',
    team VARCHAR(8) NULL COMMENT '队伍 A/B；混战与训练为空',
    ready TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否已准备',
    bot TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否为训练靶',
    score INT NOT NULL DEFAULT 0 COMMENT '对局得分（辅助效果）',
    kills INT NOT NULL DEFAULT 0 COMMENT '击倒数',
    deaths INT NOT NULL DEFAULT 0 COMMENT '被击倒数',
    assists INT NOT NULL DEFAULT 0 COMMENT '协助击倒数',
    joined_at DATETIME(3) NOT NULL COMMENT '加入时间',
    left_at DATETIME(3) NULL COMMENT '离开时间；仍在房间为空',
    PRIMARY KEY (room_id, user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '集群巡检对抗成员';

CREATE TABLE game_exercise_result (
    id VARCHAR(36) NOT NULL COMMENT '战绩 ID',
    room_id VARCHAR(36) NOT NULL COMMENT '房间 ID',
    mode VARCHAR(16) NOT NULL COMMENT '模式',
    user_id VARCHAR(64) NOT NULL COMMENT '用户 ID',
    nickname VARCHAR(64) NOT NULL COMMENT '名号',
    team VARCHAR(8) NULL COMMENT '队伍 A/B',
    rank_no INT NOT NULL COMMENT '本局名次',
    score INT NOT NULL DEFAULT 0 COMMENT '对局得分',
    kills INT NOT NULL DEFAULT 0 COMMENT '击倒数',
    deaths INT NOT NULL DEFAULT 0 COMMENT '被击倒数',
    assists INT NOT NULL DEFAULT 0 COMMENT '协助击倒数',
    winner TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否本局胜者',
    created_at DATETIME(3) NOT NULL COMMENT '结算时间',
    PRIMARY KEY (id),
    KEY idx_exercise_result_time (created_at),
    KEY idx_exercise_result_user (user_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '集群巡检对抗单局战绩';
