package com.fishmatrix.game.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fishmatrix.game.entity.GameChatMessageEntity;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 比赛聊天（game_chat_message）的数据访问。插入走 {@link BaseMapper}，
 * 历史回溯需要“倒序取最近 N 条再正序返回”，写在 XML。
 */
@Mapper
public interface GameChatMessageMapper extends BaseMapper<GameChatMessageEntity> {

    List<GameChatMessageEntity> selectHistory(@Param("tournamentId") String tournamentId,
            @Param("matchId") String matchId,
            @Param("channel") String channel,
            @Param("userA") String userA,
            @Param("userB") String userB,
            @Param("limit") int limit);
}
