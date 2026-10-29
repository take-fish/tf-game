package com.fishmatrix.game.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.fishmatrix.game.domain.BlindColor;
import com.fishmatrix.game.domain.BlindPieceType;
import lombok.Data;

/** 盲棋私有棋子：真实颜色与兵种只存服务端，公开视图不下发。 */
@Data
@TableName("game_blind_piece")
public class GameBlindPieceEntity {

    private String roundId;

    private String pieceId;

    private BlindColor color;

    private BlindPieceType pieceType;

    private Integer x;

    private Integer y;

    private boolean revealed;

    private boolean alive;
}

