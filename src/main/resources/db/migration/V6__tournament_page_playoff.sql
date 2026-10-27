-- 赛制调整：赛事只认输赢，大排行榜只统计冠军数、亚军数与总积分。
-- 冠军 30 分、亚军 10 分；其余名次不额外加分。
ALTER TABLE game_player_stat
    ADD COLUMN runner_ups INT NOT NULL DEFAULT 0 COMMENT '亚军次数' AFTER championships;

ALTER TABLE game_tournament
    ADD COLUMN runner_up_user_id VARCHAR(64) NULL COMMENT '亚军用户 ID' AFTER champion_nickname,
    ADD COLUMN runner_up_nickname VARCHAR(64) NULL COMMENT '亚军名号' AFTER runner_up_user_id;

ALTER TABLE game_match
    ADD COLUMN first_game_id VARCHAR(32) NULL COMMENT '首局剧本 ID；系统随机赛事开赛前确定' AFTER next_match_id,
    ADD COLUMN game_chooser_user_id VARCHAR(64) NULL COMMENT '首局剧本选择权用户 ID；页面淘汰赛高顺位选手' AFTER first_game_id;

-- 历史亚军回填：从已结束赛事的最后一场决赛中取负者。
UPDATE game_player_stat stat
JOIN (
    SELECT loser_id, COUNT(*) AS runner_up_count
      FROM (
        SELECT CASE
                   WHEN m.winner_user_id = m.player1_user_id THEN m.player2_user_id
                   ELSE m.player1_user_id
               END AS loser_id
          FROM game_match m
          JOIN game_tournament t ON t.id = m.tournament_id
         WHERE m.status = 'FINISHED'
           AND m.winner_user_id IS NOT NULL
           AND m.round_no = t.total_rounds
           AND m.stage IN ('KNOCKOUT', 'LADDER', 'PAGE_PLAYOFF')
           AND t.kind = 'TOURNAMENT'
      ) finals
     WHERE loser_id IS NOT NULL
     GROUP BY loser_id
) backfill ON backfill.loser_id = stat.user_id
SET stat.runner_ups = backfill.runner_up_count;

-- 旧积分来自对局/突围分，不再符合“冠军 30 / 亚军 10”的口径，统一重算。
UPDATE game_player_stat
   SET points = championships * 30 + runner_ups * 10;

ALTER TABLE game_player_stat
    MODIFY COLUMN points INT NOT NULL DEFAULT 0 COMMENT '总积分：冠军 30 分/次，亚军 10 分/次';
