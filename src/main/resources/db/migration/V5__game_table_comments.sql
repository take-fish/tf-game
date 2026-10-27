-- 为比赛域全部表与字段补齐中文注释，便于运维排障与数据字典生成。
-- 仅修改注释，不改动列类型、默认值与索引。

ALTER TABLE game_player_stat
    COMMENT = '比赛综合排行榜：按选手累计的对局、胜负、积分与冠军数排名',
    MODIFY COLUMN user_id VARCHAR(64) NOT NULL COMMENT '选手唯一标识，格式 u+账号ID',
    MODIFY COLUMN nickname VARCHAR(64) NOT NULL COMMENT '选手游戏内名号',
    MODIFY COLUMN matches INT NOT NULL DEFAULT 0 COMMENT '已完成的比赛场次',
    MODIFY COLUMN wins INT NOT NULL DEFAULT 0 COMMENT '胜场数',
    MODIFY COLUMN losses INT NOT NULL DEFAULT 0 COMMENT '负场数',
    MODIFY COLUMN points INT NOT NULL DEFAULT 0 COMMENT '排行榜积分，胜场累积',
    MODIFY COLUMN championships INT NOT NULL DEFAULT 0 COMMENT '获得冠军的次数',
    MODIFY COLUMN best_score BIGINT NOT NULL DEFAULT 0 COMMENT '历史单局最高分',
    MODIFY COLUMN updated_at DATETIME(3) NOT NULL COMMENT '最后更新时间';

ALTER TABLE game_tournament
    COMMENT = '赛事与约战：同一套编排承载多人赛事和一对一约战，game_id 选择演练剧本',
    MODIFY COLUMN id VARCHAR(36) NOT NULL COMMENT '赛事/约战唯一标识（UUID）',
    MODIFY COLUMN name VARCHAR(64) NOT NULL COMMENT '展示名称，缺省使用剧本默认名',
    MODIFY COLUMN game_id VARCHAR(32) NOT NULL COMMENT '演练剧本标识：heatmap-gomoku / log-hotspot-arena / topology-arena',
    MODIFY COLUMN kind VARCHAR(16) NOT NULL DEFAULT 'TOURNAMENT' COMMENT '类型：TOURNAMENT 多人赛事，DUEL 一对一约战',
    MODIFY COLUMN format VARCHAR(8) NOT NULL COMMENT '赛制：BO1 / BO3 / BO5',
    MODIFY COLUMN bracket_type VARCHAR(16) NOT NULL COMMENT '编排类型：KNOCKOUT 淘汰、LEAGUE 积分、TBD 待定',
    MODIFY COLUMN status VARCHAR(16) NOT NULL COMMENT '状态：INVITING 邀请中、RUNNING 进行中、FINISHED 已结束、CANCELLED 已取消',
    MODIFY COLUMN creator_user_id VARCHAR(64) NOT NULL COMMENT '创建者用户标识',
    MODIFY COLUMN creator_nickname VARCHAR(64) NOT NULL COMMENT '创建者游戏内名号',
    MODIFY COLUMN target_players INT NOT NULL COMMENT '目标参赛人数，开赛后即实际接受人数',
    MODIFY COLUMN invite_deadline DATETIME(3) NOT NULL COMMENT '邀请截止时间，超时未响应视为拒绝',
    MODIFY COLUMN current_round INT NOT NULL DEFAULT 0 COMMENT '当前进行到的轮次',
    MODIFY COLUMN total_rounds INT NOT NULL DEFAULT 0 COMMENT '赛事总轮次',
    MODIFY COLUMN champion_user_id VARCHAR(64) NULL COMMENT '冠军用户标识，未结束为空',
    MODIFY COLUMN champion_nickname VARCHAR(64) NULL COMMENT '冠军游戏内名号',
    MODIFY COLUMN created_at DATETIME(3) NOT NULL COMMENT '创建时间',
    MODIFY COLUMN started_at DATETIME(3) NULL COMMENT '开赛时间',
    MODIFY COLUMN finished_at DATETIME(3) NULL COMMENT '结束时间',
    MODIFY COLUMN deleted_at DATETIME(3) NULL COMMENT '软删除时间，非空表示已从大厅隐藏';

ALTER TABLE game_tournament_player
    COMMENT = '赛事报名与邀请状态：每位选手在每个赛事中的一条记录',
    MODIFY COLUMN tournament_id VARCHAR(36) NOT NULL COMMENT '所属赛事/约战标识',
    MODIFY COLUMN user_id VARCHAR(64) NOT NULL COMMENT '选手用户标识',
    MODIFY COLUMN nickname VARCHAR(64) NOT NULL COMMENT '报名时的游戏内名号',
    MODIFY COLUMN seed INT NOT NULL DEFAULT 0 COMMENT '种子序号，用于对阵与同分排序',
    MODIFY COLUMN status VARCHAR(16) NOT NULL COMMENT '邀请状态：INVITED 待响应、ACCEPTED 已接受、DECLINED 已拒绝、EXPIRED 已过期',
    MODIFY COLUMN invited_at DATETIME(3) NOT NULL COMMENT '邀请发出时间',
    MODIFY COLUMN responded_at DATETIME(3) NULL COMMENT '选手响应时间，未响应为空';

ALTER TABLE game_match
    COMMENT = '一场 BO 比赛：由赛事轮次生成，双方就绪后进入五子棋或实时竞技场小局',
    MODIFY COLUMN id VARCHAR(36) NOT NULL COMMENT '比赛唯一标识（UUID）',
    MODIFY COLUMN tournament_id VARCHAR(36) NOT NULL COMMENT '所属赛事/约战标识',
    MODIFY COLUMN round_no INT NOT NULL COMMENT '所属轮次，从 1 开始',
    MODIFY COLUMN slot_no INT NOT NULL COMMENT '轮次内第几场对阵，从 1 开始',
    MODIFY COLUMN stage VARCHAR(16) NOT NULL COMMENT '阶段：LEAGUE 积分赛、KNOCKOUT 淘汰赛、LADDER 阶梯决赛',
    MODIFY COLUMN status VARCHAR(16) NOT NULL COMMENT '状态：PENDING 待定、READY 待就绪、RUNNING 进行中、FINISHED 已结束、BYE 轮空',
    MODIFY COLUMN player1_user_id VARCHAR(64) NULL COMMENT '1 号位选手用户标识',
    MODIFY COLUMN player1_nickname VARCHAR(64) NULL COMMENT '1 号位选手名号',
    MODIFY COLUMN player2_user_id VARCHAR(64) NULL COMMENT '2 号位选手用户标识',
    MODIFY COLUMN player2_nickname VARCHAR(64) NULL COMMENT '2 号位选手名号',
    MODIFY COLUMN player1_score INT NOT NULL DEFAULT 0 COMMENT '1 号位已赢下的小局数',
    MODIFY COLUMN player2_score INT NOT NULL DEFAULT 0 COMMENT '2 号位已赢下的小局数',
    MODIFY COLUMN winner_user_id VARCHAR(64) NULL COMMENT '比赛胜者用户标识，未结束或轮空为空',
    MODIFY COLUMN next_match_id VARCHAR(36) NULL COMMENT '胜者晋级去向的比赛标识',
    MODIFY COLUMN created_at DATETIME(3) NOT NULL COMMENT '对阵生成时间',
    MODIFY COLUMN started_at DATETIME(3) NULL COMMENT '比赛开始时间',
    MODIFY COLUMN finished_at DATETIME(3) NULL COMMENT '比赛结束时间';

ALTER TABLE game_round
    COMMENT = '小局：五子棋使用落子表，实时竞技场使用 state_json 保存服务端权威快照',
    MODIFY COLUMN id VARCHAR(36) NOT NULL COMMENT '小局唯一标识（UUID）',
    MODIFY COLUMN match_id VARCHAR(36) NOT NULL COMMENT '所属比赛标识',
    MODIFY COLUMN game_id VARCHAR(32) NOT NULL DEFAULT 'heatmap-gomoku' COMMENT '本局使用的演练剧本标识',
    MODIFY COLUMN game_no INT NOT NULL COMMENT 'BO 赛制中的第几局，从 1 开始',
    MODIFY COLUMN status VARCHAR(16) NOT NULL COMMENT '状态：RUNNING 进行中、REST 休息中、FINISHED 已结束',
    MODIFY COLUMN black_user_id VARCHAR(64) NOT NULL COMMENT '五子棋执黑方；竞技场中作为先手方',
    MODIFY COLUMN white_user_id VARCHAR(64) NOT NULL COMMENT '五子棋执白方；竞技场中作为后手方',
    MODIFY COLUMN attacker_user_id VARCHAR(64) NULL COMMENT '竞技场攻击方用户标识，非攻防剧本为空',
    MODIFY COLUMN turn_user_id VARCHAR(64) NULL COMMENT '五子棋当前行棋方；实时竞技场没有轮次，为空',
    MODIFY COLUMN winner_user_id VARCHAR(64) NULL COMMENT '小局胜者用户标识，平局或未结束为空',
    MODIFY COLUMN move_count INT NOT NULL DEFAULT 0 COMMENT '五子棋落子步数；竞技场已处理的动作数',
    MODIFY COLUMN last_move_at DATETIME(3) NOT NULL COMMENT '最后一步落子或最后一次动作时间',
    MODIFY COLUMN rest_until DATETIME(3) NULL COMMENT '小局间休息结束时间，非休息状态为空',
    MODIFY COLUMN ready_user_ids VARCHAR(512) NOT NULL DEFAULT '' COMMENT '休息期间已点击立即开始的用户，逗号分隔',
    MODIFY COLUMN state_json MEDIUMTEXT NULL COMMENT '竞技场权威状态快照 JSON，用于重连与重启恢复',
    MODIFY COLUMN state_version INT NOT NULL DEFAULT 0 COMMENT '竞技场状态版本号，每次动作递增',
    MODIFY COLUMN started_at DATETIME(3) NOT NULL COMMENT '小局开始时间；竞技场与服务器时间同源',
    MODIFY COLUMN ends_at DATETIME(3) NULL COMMENT '竞技场计时结束时间，五子棋为空',
    MODIFY COLUMN finished_at DATETIME(3) NULL COMMENT '小局结束时间';

ALTER TABLE game_move
    COMMENT = '五子棋落子记录：按小局与步号顺序保存，用于复盘和胜局校验',
    MODIFY COLUMN round_id VARCHAR(36) NOT NULL COMMENT '所属小局标识',
    MODIFY COLUMN move_no INT NOT NULL COMMENT '小局内落子序号，从 1 开始',
    MODIFY COLUMN user_id VARCHAR(64) NOT NULL COMMENT '落子方用户标识',
    MODIFY COLUMN x INT NOT NULL COMMENT '棋盘横坐标，从 0 开始',
    MODIFY COLUMN y INT NOT NULL COMMENT '棋盘纵坐标，从 0 开始',
    MODIFY COLUMN created_at DATETIME(3) NOT NULL COMMENT '落子时间';

ALTER TABLE game_chat_message
    COMMENT = '比赛聊天消息：公开频道与选手私聊，约战消息不落库',
    MODIFY COLUMN id VARCHAR(36) NOT NULL COMMENT '消息唯一标识（UUID）',
    MODIFY COLUMN tournament_id VARCHAR(36) NOT NULL COMMENT '所属赛事/约战标识',
    MODIFY COLUMN match_id VARCHAR(36) NULL COMMENT '关联比赛标识，赛事大厅消息为空',
    MODIFY COLUMN channel VARCHAR(16) NOT NULL COMMENT '频道：PUBLIC 观战公开、TEAM 选手频道',
    MODIFY COLUMN from_user_id VARCHAR(64) NOT NULL COMMENT '发送者用户标识',
    MODIFY COLUMN from_nickname VARCHAR(64) NOT NULL COMMENT '发送者游戏内名号',
    MODIFY COLUMN to_user_id VARCHAR(64) NULL COMMENT '私聊接收者用户标识，公开消息为空',
    MODIFY COLUMN content VARCHAR(2000) NOT NULL COMMENT '消息正文，已清理控制字符',
    MODIFY COLUMN created_at DATETIME(3) NOT NULL COMMENT '发送时间';

ALTER TABLE game_notification
    COMMENT = '全站比赛通知：决赛开始、冠军产生等，带过期时间',
    MODIFY COLUMN id VARCHAR(36) NOT NULL COMMENT '通知唯一标识（UUID）',
    MODIFY COLUMN type VARCHAR(16) NOT NULL COMMENT '通知类型：FINAL 决赛开始、CHAMPION 冠军产生等',
    MODIFY COLUMN tournament_id VARCHAR(36) NOT NULL COMMENT '关联赛事标识',
    MODIFY COLUMN match_id VARCHAR(36) NULL COMMENT '关联比赛标识，无具体比赛为空',
    MODIFY COLUMN title VARCHAR(128) NOT NULL COMMENT '通知标题',
    MODIFY COLUMN message VARCHAR(256) NOT NULL COMMENT '通知正文',
    MODIFY COLUMN created_at DATETIME(3) NOT NULL COMMENT '创建时间',
    MODIFY COLUMN expires_at DATETIME(3) NOT NULL COMMENT '过期时间，过期后不再展示';
