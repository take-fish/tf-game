package com.fishmatrix.game.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fishmatrix.game.domain.NotificationType;
import java.time.LocalDateTime;
import lombok.Data;

/** 全站比赛通知：决赛开始、冠军产生等，带过期时间。 */
@Data
@TableName("game_notification")
public class GameNotificationEntity {

    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    private NotificationType type;

    private String tournamentId;

    private String matchId;

    private String title;

    private String message;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    private LocalDateTime expiresAt;
}

