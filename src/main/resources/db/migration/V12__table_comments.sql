-- tf-game 新表与新字段注释
-- 只修改表与字段注释，不改变类型、默认值、索引；列定义逐字取自 MySQL 8.4 的 SHOW CREATE TABLE。
-- 列注释统一用 ALGORITHM=INSTANT，若定义与生产不一致会直接报错，避免静默改结构。

ALTER TABLE `game_tournament`
    MODIFY COLUMN `blind_layout` varchar(24) DEFAULT NULL COMMENT '盲棋摆法，STANDARD_SHUFFLE 标准点位洗牌 / FULL_RANDOM 全随机 / HALF_RANDOM 半随机；非盲棋为空',
    ALGORITHM=INSTANT;

ALTER TABLE `game_move`
    MODIFY COLUMN `action` varchar(16) NOT NULL DEFAULT 'PLACE' COMMENT '落子动作，PLACE 放置 / MOVE 移动',
    MODIFY COLUMN `from_x` int DEFAULT NULL COMMENT '移动起点横坐标，仅 action=MOVE 有值',
    MODIFY COLUMN `from_y` int DEFAULT NULL COMMENT '移动起点纵坐标，仅 action=MOVE 有值',
    MODIFY COLUMN `piece_type` varchar(16) DEFAULT NULL COMMENT '本次落子兵种，GENERAL/ADVISOR/ELEPHANT/HORSE/CHARIOT/CANNON/SOLDIER',
    MODIFY COLUMN `piece_color` varchar(8) DEFAULT NULL COMMENT '本次落子颜色，RED/BLACK',
    MODIFY COLUMN `captured_piece_type` varchar(16) DEFAULT NULL COMMENT '被吃棋子兵种，未吃子为空',
    MODIFY COLUMN `captured_piece_color` varchar(8) DEFAULT NULL COMMENT '被吃棋子颜色，未吃子为空',
    ALGORITHM=INSTANT;

ALTER TABLE `game_blind_piece` COMMENT = '盲棋暗棋：服务端保存每枚棋子的真实颜色与兵种';

ALTER TABLE `game_blind_piece`
    MODIFY COLUMN `round_id` varchar(36) NOT NULL COMMENT '小局 ID',
    MODIFY COLUMN `piece_id` varchar(36) NOT NULL COMMENT '棋子 ID',
    MODIFY COLUMN `color` varchar(8) NOT NULL COMMENT '真实颜色，RED/BLACK',
    MODIFY COLUMN `piece_type` varchar(16) NOT NULL COMMENT '真实兵种，GENERAL/ADVISOR/ELEPHANT/HORSE/CHARIOT/CANNON/SOLDIER',
    MODIFY COLUMN `x` int NOT NULL COMMENT '当前横坐标',
    MODIFY COLUMN `y` int NOT NULL COMMENT '当前纵坐标',
    MODIFY COLUMN `revealed` tinyint(1) NOT NULL DEFAULT '0' COMMENT '是否已翻开',
    MODIFY COLUMN `alive` tinyint(1) NOT NULL DEFAULT '1' COMMENT '是否仍在棋盘上',
    ALGORITHM=INSTANT;

ALTER TABLE `game_player_game_stat` COMMENT = '分游戏排行榜：按游戏维度累计对局、胜负、积分与冠军数';

ALTER TABLE `game_player_game_stat`
    MODIFY COLUMN `game_id` varchar(32) NOT NULL COMMENT '游戏标识',
    MODIFY COLUMN `user_id` varchar(64) NOT NULL COMMENT '选手用户标识',
    MODIFY COLUMN `nickname` varchar(64) NOT NULL COMMENT '选手昵称快照',
    MODIFY COLUMN `matches` int NOT NULL DEFAULT '0' COMMENT '已完成对局数',
    MODIFY COLUMN `wins` int NOT NULL DEFAULT '0' COMMENT '胜局数',
    MODIFY COLUMN `losses` int NOT NULL DEFAULT '0' COMMENT '负局数',
    MODIFY COLUMN `points` int NOT NULL DEFAULT '0' COMMENT '累计积分',
    MODIFY COLUMN `championships` int NOT NULL DEFAULT '0' COMMENT '获得冠军次数',
    MODIFY COLUMN `updated_at` datetime(3) NOT NULL COMMENT '最后更新时间',
    ALGORITHM=INSTANT;
