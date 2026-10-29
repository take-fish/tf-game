package com.fishmatrix.game.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fishmatrix.game.entity.GameExerciseMemberEntity;
import com.fishmatrix.game.entity.GameExerciseResultEntity;
import com.fishmatrix.game.entity.GameExerciseRoomEntity;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 巡检房间聚合（game_exercise_room / member / result）的数据访问。
 *
 * <p>房间、成员、战绩的插入走 {@link BaseMapper}；状态快照、结束、取消与成员变更
 * 都是带条件的单条 UPDATE，写在 XML 并返回影响行数。</p>
 */
@Mapper
public interface GameExerciseMapper extends BaseMapper<GameExerciseRoomEntity> {

    int updateRoomState(@Param("roomId") String roomId,
            @Param("stateJson") String stateJson,
            @Param("stateVersion") int stateVersion,
            @Param("status") String status);

    /** 复合主键表按固定列顺序写入。 */
    int insertMember(GameExerciseMemberEntity member);

    int insertResult(GameExerciseResultEntity result);

    int finishRoom(@Param("roomId") String roomId,
            @Param("winnerUserId") String winnerUserId,
            @Param("winnerTeam") String winnerTeam,
            @Param("reason") String reason,
            @Param("stateJson") String stateJson,
            @Param("stateVersion") int stateVersion,
            @Param("now") LocalDateTime now);

    int cancelRoom(@Param("roomId") String roomId,
            @Param("reason") String reason,
            @Param("now") LocalDateTime now);

    int updateMemberReady(@Param("roomId") String roomId,
            @Param("userId") String userId,
            @Param("ready") boolean ready);

    int updateMemberLeft(@Param("roomId") String roomId,
            @Param("userId") String userId,
            @Param("now") LocalDateTime now);

    int updateMemberStats(@Param("roomId") String roomId,
            @Param("userId") String userId,
            @Param("score") int score,
            @Param("kills") int kills,
            @Param("deaths") int deaths,
            @Param("assists") int assists);

    List<GameExerciseResultEntity> selectRecentResults(@Param("limit") int limit);

    List<GameExerciseRoomEntity> selectRunningRooms();

    List<GameExerciseMemberEntity> selectMembers(@Param("roomId") String roomId);
}
