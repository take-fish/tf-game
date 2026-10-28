-- 管理员治理能力：排行榜与比赛记录的可见性、展示顺序与操作审计。
-- 展示顺序是权重：数值越大越靠前，0 表示按原有战绩规则排序。
-- 排行榜与比赛记录默认可见，历史数据不需要回填。

ALTER TABLE game_player_stat
    ADD COLUMN visible TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否在排行榜展示' AFTER best_score,
    ADD COLUMN display_order INT NOT NULL DEFAULT 0 COMMENT '展示权重，越大越靠前' AFTER visible,
    ADD COLUMN managed_by VARCHAR(64) NULL COMMENT '最后调整人用户标识' AFTER display_order,
    ADD COLUMN managed_at DATETIME(3) NULL COMMENT '最后调整时间' AFTER managed_by,
    ADD KEY idx_player_visible (visible, display_order, points);

ALTER TABLE game_player_game_stat
    ADD COLUMN visible TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否在分游戏榜展示'
        AFTER championships,
    ADD COLUMN display_order INT NOT NULL DEFAULT 0 COMMENT '展示权重，越大越靠前'
        AFTER visible,
    ADD COLUMN managed_by VARCHAR(64) NULL COMMENT '最后调整人用户标识' AFTER display_order,
    ADD COLUMN managed_at DATETIME(3) NULL COMMENT '最后调整时间' AFTER managed_by,
    ADD KEY idx_game_player_visible (game_id, visible, display_order, points);

ALTER TABLE game_match
    ADD COLUMN visible TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否比赛记录列表展示'
        AFTER game_chooser_user_id,
    ADD COLUMN display_order INT NOT NULL DEFAULT 0 COMMENT '展示权重，越大越靠前'
        AFTER visible,
    ADD COLUMN managed_by VARCHAR(64) NULL COMMENT '最后调整人用户标识' AFTER display_order,
    ADD COLUMN managed_at DATETIME(3) NULL COMMENT '最后调整时间' AFTER managed_by,
    ADD KEY idx_match_visible (visible, display_order, created_at);
