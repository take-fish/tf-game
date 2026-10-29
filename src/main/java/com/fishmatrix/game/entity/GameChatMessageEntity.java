package com.fishmatrix.game.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fishmatrix.game.domain.ChatChannel;
import java.time.LocalDateTime;
import lombok.Data;

/** 比赛聊天消息：公开频道与选手私聊，约战消息不落库。 */
@Data
@TableName("game_chat_message")
public class GameChatMessageEntity {

    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    private String tournamentId;

    private String matchId;

    private ChatChannel channel;

    private String fromUserId;

    private String fromNickname;

    private String toUserId;

    private String content;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}

