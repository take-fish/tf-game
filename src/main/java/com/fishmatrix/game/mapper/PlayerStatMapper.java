package com.fishmatrix.game.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fishmatrix.game.entity.GamePlayerGameStatEntity;
import com.fishmatrix.game.entity.GamePlayerStatEntity;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 排行榜（game_player_stat / game_player_game_stat）的数据访问。
 *
 * <p>{@link #ensure} / {@link #ensureGame} 是“先更新、未命中再插入、重复键回退更新”的
 * 首次写入兜底，属于持久化不变式，因此留在 Mapper 的 default 方法里；
 * 增量累计与名次统计都是单条 SQL，写在 XML。</p>
 */
@Mapper
public interface PlayerStatMapper extends BaseMapper<GamePlayerStatEntity> {

    List<GamePlayerStatEntity> selectTop(@Param("limit") int limit);

    /** 分游戏榜为主键 (game_id, user_id)，按固定列顺序写入。 */
    int insertGameStat(GamePlayerGameStatEntity stat);

    List<GamePlayerGameStatEntity> selectTopByGame(@Param("gameId") String gameId,
            @Param("limit") int limit);

    Long selectBestScore(@Param("userId") String userId);

    Long selectRank(@Param("userId") String userId);

    int updateNickname(@Param("userId") String userId,
            @Param("nickname") String nickname,
            @Param("now") LocalDateTime now);

    int updateGameNickname(@Param("gameId") String gameId,
            @Param("userId") String userId,
            @Param("nickname") String nickname,
            @Param("now") LocalDateTime now);

    int addTitleStats(@Param("userId") String userId,
            @Param("nickname") String nickname,
            @Param("championships") int championships,
            @Param("runnerUps") int runnerUps,
            @Param("points") int points,
            @Param("now") LocalDateTime now);

    int addMatchStats(@Param("userId") String userId,
            @Param("nickname") String nickname,
            @Param("wins") int wins,
            @Param("losses") int losses,
            @Param("now") LocalDateTime now);

    int addGameMatchStats(@Param("gameId") String gameId,
            @Param("userId") String userId,
            @Param("nickname") String nickname,
            @Param("wins") int wins,
            @Param("losses") int losses,
            @Param("points") int points,
            @Param("championships") int championships,
            @Param("now") LocalDateTime now);

    int raiseBestScoreValue(@Param("userId") String userId,
            @Param("nickname") String nickname,
            @Param("score") long score,
            @Param("now") LocalDateTime now);

    /** 综合榜行不存在时先建零值行；并发首次写入由唯一键兜底。 */
    default void ensure(String userId, String nickname) {
        LocalDateTime now = LocalDateTime.now();
        if (updateNickname(userId, nickname, now) > 0) {
            return;
        }
        try {
            GamePlayerStatEntity entity = new GamePlayerStatEntity();
            entity.setUserId(userId);
            entity.setNickname(nickname);
            entity.setMatches(0);
            entity.setWins(0);
            entity.setLosses(0);
            entity.setPoints(0);
            entity.setChampionships(0);
            entity.setRunnerUps(0);
            entity.setBestScore(0L);
            entity.setUpdatedAt(now);
            insert(entity);
        } catch (org.springframework.dao.DuplicateKeyException ignored) {
            // 并发首次写入：另一个请求已经插入，更新昵称即可。
            updateNickname(userId, nickname, now);
        }
    }

    /** 分游戏榜行不存在时先建零值行。 */
    default void ensureGame(String gameId, String userId, String nickname) {
        LocalDateTime now = LocalDateTime.now();
        if (updateGameNickname(gameId, userId, nickname, now) > 0) {
            return;
        }
        try {
            GamePlayerGameStatEntity entity = new GamePlayerGameStatEntity();
            entity.setGameId(gameId);
            entity.setUserId(userId);
            entity.setNickname(nickname);
            entity.setMatches(0);
            entity.setWins(0);
            entity.setLosses(0);
            entity.setPoints(0);
            entity.setChampionships(0);
            entity.setUpdatedAt(now);
            insertGameStat(entity);
        } catch (org.springframework.dao.DuplicateKeyException ignored) {
            updateGameNickname(gameId, userId, nickname, now);
        }
    }

    /** 按增量累计赛事头衔；头衔与积分由 PlayerStatRules 计算后传入。 */
    default void addTitle(String userId, String nickname,
                          int championships, int runnerUps, int points) {
        ensure(userId, nickname);
        addTitleStats(userId, nickname, championships, runnerUps, points, LocalDateTime.now());
    }

    /** 按增量累计对局统计，大榜积分不随场次增加。 */
    default void addMatch(String userId, String nickname, int wins, int losses) {
        ensure(userId, nickname);
        addMatchStats(userId, nickname, wins, losses, LocalDateTime.now());
    }

    /** 按增量累计分游戏榜的场次、积分与冠军次数。 */
    default void addGameMatch(String gameId, String userId, String nickname,
                              int wins, int losses, int points, int championships) {
        ensureGame(gameId, userId, nickname);
        addGameMatchStats(gameId, userId, nickname, wins, losses, points, championships,
                LocalDateTime.now());
    }

    /** 打砖块只保留更高分：数据库侧 GREATEST 原子比较，避免并发覆盖更高历史分。 */
    default void raiseBestScore(String userId, String nickname, long score) {
        ensure(userId, nickname);
        raiseBestScoreValue(userId, nickname, score, LocalDateTime.now());
    }
}
