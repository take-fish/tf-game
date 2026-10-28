package com.fishmatrix.game.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fishmatrix.game.entity.GameNotificationEntity;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 全站通知（game_notification）的数据访问。插入走 {@link BaseMapper}，
 * 去重判断与按时间窗轮询写在 XML。
 */
@Mapper
public interface GameNotificationMapper extends BaseMapper<GameNotificationEntity> {

    /** 同一场比赛的同类通知只发一次，避免决赛/冠军重复弹窗。 */
    boolean exists(@Param("type") String type,
            @Param("tournamentId") String tournamentId,
            @Param("matchId") String matchId);

    /** 轮询兜底：只返回尚未过期的通知，按时间升序。 */
    List<GameNotificationEntity> selectRecent(@Param("after") LocalDateTime after,
            @Param("now") LocalDateTime now,
            @Param("limit") int limit);

    List<GameNotificationEntity> selectLatest(@Param("now") LocalDateTime now,
            @Param("limit") int limit);
}
