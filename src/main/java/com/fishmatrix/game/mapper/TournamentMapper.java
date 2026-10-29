package com.fishmatrix.game.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fishmatrix.game.entity.GameTournamentEntity;
import com.fishmatrix.game.entity.GameTournamentPlayerEntity;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 赛事聚合（game_tournament / game_tournament_player）的数据访问。
 *
 * <p>软删除过滤、排序与 LIMIT 都写在 XML；插入与按主键查询走 {@link BaseMapper}，
 * 但详情必须用 {@link #selectActiveById(String)} 才能排除已删除赛事。</p>
 */
@Mapper
public interface TournamentMapper extends BaseMapper<GameTournamentEntity> {

    /** 详情：排除软删除记录。 */
    GameTournamentEntity selectActiveById(@Param("id") String id);

    /** 复合主键表按固定列顺序写入，避免 BaseMapper 依赖主键生成策略。 */
    int insertPlayer(GameTournamentPlayerEntity player);

    List<GameTournamentEntity> selectRecent(@Param("limit") int limit);

    /** 约战历史：只返回当前用户参与的一对一对局，按创建时间倒序。 */
    List<GameTournamentEntity> selectDuelsForUser(@Param("userId") String userId,
            @Param("limit") int limit);

    /** 同一对用户在同一游戏下是否已有未结束约战。 */
    boolean existsActiveDuelBetween(@Param("firstUserId") String firstUserId,
            @Param("secondUserId") String secondUserId,
            @Param("gameId") String gameId);

    List<GameTournamentEntity> selectRunning();

    List<GameTournamentEntity> selectInvitingExpired(@Param("now") LocalDateTime now);

    List<GameTournamentEntity> selectPendingInvitations(@Param("userId") String userId,
            @Param("now") LocalDateTime now);

    List<GameTournamentPlayerEntity> selectPlayers(@Param("tournamentId") String tournamentId);

    GameTournamentPlayerEntity selectPlayer(@Param("tournamentId") String tournamentId,
            @Param("userId") String userId);

    List<GameTournamentPlayerEntity> selectAcceptedPlayers(
            @Param("tournamentId") String tournamentId);

    List<GameTournamentPlayerEntity> selectAcceptedOrInvited(
            @Param("tournamentId") String tournamentId);

    int accept(@Param("tournamentId") String tournamentId,
            @Param("userId") String userId,
            @Param("nickname") String nickname,
            @Param("now") LocalDateTime now);

    int decline(@Param("tournamentId") String tournamentId,
            @Param("userId") String userId,
            @Param("now") LocalDateTime now);

    int expireInvitations(@Param("tournamentId") String tournamentId,
            @Param("now") LocalDateTime now);

    int updateSeed(@Param("tournamentId") String tournamentId,
            @Param("userId") String userId,
            @Param("seed") int seed);

    int start(@Param("tournamentId") String tournamentId,
            @Param("bracketType") String bracketType,
            @Param("totalRounds") int totalRounds,
            @Param("now") LocalDateTime now);

    int updateCurrentRound(@Param("tournamentId") String tournamentId,
            @Param("round") int round);

    /** 条件推进轮次，避免多个比赛同时结束时重复生成下一轮。 */
    int advanceRound(@Param("tournamentId") String tournamentId,
            @Param("expectedRound") int expectedRound,
            @Param("nextRound") int nextRound);

    /** 积分赛结束，原子切换到页面淘汰赛。 */
    int startPagePlayoff(@Param("tournamentId") String tournamentId,
            @Param("totalRounds") int totalRounds,
            @Param("firstRound") int firstRound,
            @Param("expectedRound") int expectedRound);

    /** 旧赛事兼容：积分赛结束切换到阶梯决赛。 */
    int startLadder(@Param("tournamentId") String tournamentId,
            @Param("totalRounds") int totalRounds,
            @Param("challengeRound") int challengeRound,
            @Param("expectedRound") int expectedRound);

    int updateBracketType(@Param("tournamentId") String tournamentId,
            @Param("bracketType") String bracketType,
            @Param("totalRounds") int totalRounds);

    int finish(@Param("tournamentId") String tournamentId,
            @Param("championUserId") String championUserId,
            @Param("championNickname") String championNickname,
            @Param("runnerUpUserId") String runnerUpUserId,
            @Param("runnerUpNickname") String runnerUpNickname,
            @Param("now") LocalDateTime now);

    int cancel(@Param("tournamentId") String tournamentId, @Param("now") LocalDateTime now);

    /** 软删除：仅隐藏记录，进行中的赛事不允许删除。 */
    int softDelete(@Param("tournamentId") String tournamentId, @Param("now") LocalDateTime now);
}
