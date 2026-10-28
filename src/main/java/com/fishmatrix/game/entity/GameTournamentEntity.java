package com.fishmatrix.game.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fishmatrix.game.domain.BracketType;
import com.fishmatrix.game.domain.TournamentFormat;
import com.fishmatrix.game.domain.TournamentKind;
import com.fishmatrix.game.domain.TournamentStatus;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 赛事与约战：同一套编排承载多人赛事和一对一约战。
 *
 * <p>game_pool 存逗号分隔的 gameId，空值表示固定剧本；主键由业务生成（UUID），
 * 因此使用 {@link IdType#INPUT}。</p>
 */
@Data
@TableName("game_tournament")
public class GameTournamentEntity {

    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    private String name;

    private String gameId;

    private String gamePool;

    private String blindLayout;

    private TournamentKind kind;

    private TournamentFormat format;

    private BracketType bracketType;

    private TournamentStatus status;

    private String creatorUserId;

    private String creatorNickname;

    private Integer targetPlayers;

    private LocalDateTime inviteDeadline;

    private Integer currentRound;

    private Integer totalRounds;

    private String championUserId;

    private String championNickname;

    private String runnerUpUserId;

    private String runnerUpNickname;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    private LocalDateTime deletedAt;
}
