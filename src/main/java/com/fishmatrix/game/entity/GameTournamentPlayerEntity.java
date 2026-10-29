package com.fishmatrix.game.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.fishmatrix.game.domain.TournamentPlayerStatus;
import java.time.LocalDateTime;
import lombok.Data;

/** 赛事报名与邀请状态：复合主键 (tournament_id, user_id)。 */
@Data
@TableName("game_tournament_player")
public class GameTournamentPlayerEntity {

    private String tournamentId;

    private String userId;

    private String nickname;

    private Integer seed;

    private TournamentPlayerStatus status;

    private LocalDateTime invitedAt;

    private LocalDateTime respondedAt;
}

