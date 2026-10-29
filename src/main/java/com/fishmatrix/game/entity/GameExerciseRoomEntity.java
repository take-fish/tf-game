package com.fishmatrix.game.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fishmatrix.game.domain.ExerciseMode;
import com.fishmatrix.game.domain.ExerciseSource;
import com.fishmatrix.game.domain.ExerciseStatus;
import java.time.LocalDateTime;
import lombok.Data;

/** 集群巡检对抗房间：state_json 每秒快照，用于服务重启后恢复。 */
@Data
@TableName("game_exercise_room")
public class GameExerciseRoomEntity {

    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    private String roomCode;

    private ExerciseMode mode;

    private ExerciseSource source;

    private ExerciseStatus status;

    private String matchId;

    private String ownerUserId;

    private Integer maxPlayers;

    private Integer durationSeconds;

    private String stateJson;

    private Integer stateVersion;

    private String winnerUserId;

    private String winnerTeam;

    private String finishReason;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;
}

