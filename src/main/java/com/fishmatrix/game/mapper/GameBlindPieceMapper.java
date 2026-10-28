package com.fishmatrix.game.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fishmatrix.game.entity.GameBlindPieceEntity;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 盲棋私有棋子（game_blind_piece）的数据访问。
 *
 * <p>批量写入走 {@link BaseMapper#insert(java.util.Collection)}；翻子/走子/吃子都是
 * 带条件的单条 UPDATE，返回影响行数供并发判断。</p>
 */
@Mapper
public interface GameBlindPieceMapper extends BaseMapper<GameBlindPieceEntity> {

    List<GameBlindPieceEntity> selectAlivePieces(@Param("roundId") String roundId);

    int reveal(@Param("roundId") String roundId, @Param("pieceId") String pieceId);

    int move(@Param("roundId") String roundId,
            @Param("pieceId") String pieceId,
            @Param("x") int x,
            @Param("y") int y);

    int capture(@Param("roundId") String roundId, @Param("pieceId") String pieceId);
}
