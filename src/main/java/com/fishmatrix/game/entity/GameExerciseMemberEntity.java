package com.fishmatrix.game.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/** 集群巡检对抗成员：复合主键 (room_id, user_id)。 */
@Data
@TableName("game_exercise_member")
public class GameExerciseMemberEntity {

    private String roomId;

    private String userId;

    private String nickname;

    private String team;

    private boolean ready;

    private boolean bot;

    private Integer score;

    private Integer kills;

    private Integer deaths;

    private Integer assists;

    private LocalDateTime joinedAt;

    private LocalDateTime leftAt;
}

