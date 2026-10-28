package com.fishmatrix.game.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fishmatrix.game.domain.ExerciseMode;
import java.time.LocalDateTime;
import lombok.Data;

/** 集群巡检对抗单局战绩。 */
@Data
@TableName("game_exercise_result")
public class GameExerciseResultEntity {

    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    private String roomId;

    private ExerciseMode mode;

    private String userId;

    private String nickname;

    private String team;

    private Integer rankNo;

    private Integer score;

    private Integer kills;

    private Integer deaths;

    private Integer assists;

    private boolean winner;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}

