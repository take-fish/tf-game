package com.fishmatrix.game.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fishmatrix.game.entity.GameMatchEntity;
import com.fishmatrix.game.entity.GameMoveEntity;
import com.fishmatrix.game.entity.GameRoundEntity;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 比赛聚合（game_match / game_round / game_move）的数据访问。
 *
 * <p>单行读写优先走 {@link BaseMapper}；带条件更新、排序、LIMIT 与 GREATEST 的语句
 * 统一放在 {@code mapper/MatchMapper.xml}。</p>
 */
@Mapper
public interface MatchMapper extends BaseMapper<GameMatchEntity> {

    List<GameMatchEntity> selectByTournament(@Param("tournamentId") String tournamentId);

    /** 复合主键表按固定列顺序写入。 */
    int insertRound(GameRoundEntity round);

    int insertMove(GameMoveEntity move);

    List<GameMatchEntity> selectByStage(@Param("tournamentId") String tournamentId,
            @Param("stage") String stage);

    /** 我参与的、可继续操作的对局，按创建时间倒序。 */
    List<GameMatchEntity> selectActiveByUser(@Param("userId") String userId);

    /** 积分赛单轮全部结束后，把下一轮从 PENDING 激活为 READY。 */
    int activateRound(@Param("tournamentId") String tournamentId,
            @Param("stage") String stage,
            @Param("roundNo") int roundNo);

    int countRoundMatches(@Param("tournamentId") String tournamentId,
            @Param("stage") String stage,
            @Param("roundNo") int roundNo);

    int countRoundFinished(@Param("tournamentId") String tournamentId,
            @Param("stage") String stage,
            @Param("roundNo") int roundNo);

    int countLeagueMatches(@Param("tournamentId") String tournamentId);

    int countLeagueFinished(@Param("tournamentId") String tournamentId);

    /** 休息时间已到的小局，由定时任务自动开始下一局。 */
    List<GameRoundEntity> selectDueRest(@Param("now") LocalDateTime now);

    /** 正在对局的小局，用于每步超时判定。 */
    List<GameRoundEntity> selectRunningRounds();

    int updateStatus(@Param("matchId") String matchId, @Param("status") String status);

    int updateFirstGame(@Param("matchId") String matchId, @Param("gameId") String gameId);

    int initFirstGame(@Param("matchId") String matchId,
            @Param("gameId") String gameId,
            @Param("chooserUserId") String chooserUserId);

    int start(@Param("matchId") String matchId, @Param("now") LocalDateTime now);

    int finish(@Param("matchId") String matchId,
            @Param("winnerUserId") String winnerUserId,
            @Param("player1Score") int player1Score,
            @Param("player2Score") int player2Score,
            @Param("now") LocalDateTime now);

    /** 条件结束比赛：只有 RUNNING 才能被结算，避免并发重复加分。 */
    int finishIfRunning(@Param("matchId") String matchId,
            @Param("winnerUserId") String winnerUserId,
            @Param("player1Score") int player1Score,
            @Param("player2Score") int player2Score,
            @Param("now") LocalDateTime now);

    /** 未开赛或进行中的比赛都可因认输结束。 */
    int finishActive(@Param("matchId") String matchId,
            @Param("winnerUserId") String winnerUserId,
            @Param("player1Score") int player1Score,
            @Param("player2Score") int player2Score,
            @Param("now") LocalDateTime now);

    int finishRoundIfRunning(@Param("roundId") String roundId,
            @Param("winnerUserId") String winnerUserId,
            @Param("now") LocalDateTime now);

    int restAfterGame(@Param("roundId") String roundId,
            @Param("winnerUserId") String winnerUserId,
            @Param("restUntil") LocalDateTime restUntil,
            @Param("now") LocalDateTime now);

    int updateScore(@Param("matchId") String matchId,
            @Param("player1Score") int player1Score,
            @Param("player2Score") int player2Score);

    /** 把上一轮胜者填入下一轮比赛的 player1 / player2 位置。 */
    int fillNextSlot(@Param("nextMatchId") String nextMatchId,
            @Param("userId") String userId,
            @Param("nickname") String nickname,
            @Param("slot1") boolean slot1);

    /** 竞技场每秒保存一次权威状态；只允许 RUNNING 小局写入。 */
    int updateArenaState(@Param("roundId") String roundId,
            @Param("stateJson") String stateJson,
            @Param("stateVersion") int stateVersion,
            @Param("actionCount") int actionCount);

    /** 服务重启后恢复仍在对局的竞技场小局。 */
    List<GameRoundEntity> selectRunningArenaRounds(@Param("gomokuGameId") String gomokuGameId);

    GameRoundEntity selectRound(@Param("roundId") String roundId);

    GameRoundEntity selectLatestRound(@Param("matchId") String matchId);

    List<GameRoundEntity> selectRounds(@Param("matchId") String matchId);

    List<GameRoundEntity> selectRoundsByStatus(@Param("status") String status);

    List<GameMoveEntity> selectMoves(@Param("roundId") String roundId);

    /** 乐观更新：只有 move_count 与预期一致时才切换回合，避免并发落子覆盖。 */
    int updateTurnAndCount(@Param("roundId") String roundId,
            @Param("expectedCount") int expectedCount,
            @Param("nextCount") int nextCount,
            @Param("nextTurnUserId") String nextTurnUserId,
            @Param("now") LocalDateTime now);

    int updateRoundStatus(@Param("roundId") String roundId,
            @Param("status") String status,
            @Param("winnerUserId") String winnerUserId,
            @Param("restUntil") LocalDateTime restUntil,
            @Param("finishedAt") LocalDateTime finishedAt);

    int updateReadyUserIds(@Param("roundId") String roundId,
            @Param("readyUserIds") String readyUserIds);

    /** 条件更新：休息中的小局只允许被一个调用方推进到下一局。 */
    int finishRestRound(@Param("roundId") String roundId, @Param("now") LocalDateTime now);
}
