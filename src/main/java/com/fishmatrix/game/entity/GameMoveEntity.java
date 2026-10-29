package com.fishmatrix.game.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fishmatrix.game.domain.BlindColor;
import com.fishmatrix.game.domain.BlindPieceType;
import com.fishmatrix.game.domain.MoveAction;
import java.time.LocalDateTime;
import lombok.Data;

/** 五子棋与盲棋落子记录：复合主键 (round_id, move_no)。 */
@Data
@TableName("game_move")
public class GameMoveEntity {

    private String roundId;

    private Integer moveNo;

    private String userId;

    private Integer x;

    private Integer y;

    private MoveAction action;

    private Integer fromX;

    private Integer fromY;

    private BlindPieceType pieceType;

    private BlindColor pieceColor;

    private BlindPieceType capturedPieceType;

    private BlindColor capturedPieceColor;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}

