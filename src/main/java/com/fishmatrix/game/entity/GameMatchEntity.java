package com.fishmatrix.game.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fishmatrix.game.domain.MatchStage;
import com.fishmatrix.game.domain.MatchStatus;
import java.time.LocalDateTime;
import lombok.Data;

/** 一场 BO 对局，主键由业务生成（UUID）。 */
@Data
@TableName("game_match")
public class GameMatchEntity {

    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    private String tournamentId;

    private Integer roundNo;

    private Integer slotNo;

    private MatchStage stage;

    private MatchStatus status;

    private String player1UserId;

    private String player1Nickname;

    private String player2UserId;

    private String player2Nickname;

    private Integer player1Score;

    private Integer player2Score;

    private String winnerUserId;

    private String nextMatchId;

    private String firstGameId;

    private String gameChooserUserId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;
}

