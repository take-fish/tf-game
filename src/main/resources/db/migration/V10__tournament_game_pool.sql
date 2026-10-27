-- 赛事类型：S赛（综合赛）按启用池逐场随机抽选剧本；锦标赛固定单一游戏。
-- game_pool 存逗号分隔的 gameId，NULL 表示非随机赛事（锦标赛 / 约战）。
ALTER TABLE game_tournament
    ADD COLUMN game_pool VARCHAR(255) NULL COMMENT 'S赛随机剧本池，逗号分隔；NULL 表示固定剧本' AFTER game_id;
