package com.fishmatrix.game.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fishmatrix.game.domain.RoundStatus;
import java.time.LocalDateTime;
import lombok.Data;

/** 一场比赛中的一个小局：五子棋用落子表，竞技场用 state_json 保存权威快照。 */
@Data
@TableName("game_round")
public class GameRoundEntity {

    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    private String matchId;

    private String gameId;

    private Integer gameNo;

    private RoundStatus status;

    private String blackUserId;

    private String whiteUserId;

    private String attackerUserId;

    private String turnUserId;

    private String winnerUserId;

    private Integer moveCount;

    private LocalDateTime lastMoveAt;

    private LocalDateTime restUntil;

    private String readyUserIds;

    private String stateJson;

    private Integer stateVersion;

    private LocalDateTime startedAt;

    private LocalDateTime endsAt;

    private LocalDateTime finishedAt;
}

