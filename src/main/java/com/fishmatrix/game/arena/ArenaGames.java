package com.fishmatrix.game.arena;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 演练剧本目录。
 * - 赛事默认使用 RANDOM：每一小局由系统从 1v1 可用的剧本池中随机抽取。
 * - 五子棋保留原 gameId；两个实时竞技场各有独立引擎；盲棋同样是 1v1 玩法。
 */
public final class ArenaGames {

    public static final String GOMOKU = "heatmap-gomoku";
    public static final String HEATMAP = "log-hotspot-arena";
    public static final String TOPOLOGY = "topology-arena";
    public static final String CLUSTER_PATROL = "cluster-patrol-arena";
    public static final String BLIND_XIANGQI = "blind-xiangqi";
    public static final String RANDOM = "random";

    /** 可进入赛事/约战的 1v1 剧本池；S赛创建时可按启用的子集缩小范围。 */
    public static final List<String> TOURNAMENT_POOL = List.of(
            GOMOKU, HEATMAP, TOPOLOGY, CLUSTER_PATROL, BLIND_XIANGQI);

    public static final Set<String> ALL = Set.of(
            GOMOKU, HEATMAP, TOPOLOGY, CLUSTER_PATROL, RANDOM);

    private ArenaGames() {
    }

    public static boolean isRandom(String gameId) {
        return RANDOM.equals(gameId);
    }

    public static boolean isArena(String gameId) {
        return HEATMAP.equals(gameId) || TOPOLOGY.equals(gameId) || CLUSTER_PATROL.equals(gameId);
    }

    public static String randomGame() {
        return randomGame(TOURNAMENT_POOL);
    }

    /** 从赛事启用池中抽取；池为空时回退到完整剧本池。 */
    public static String randomGame(List<String> pool) {
        List<String> candidates = pool == null || pool.isEmpty() ? TOURNAMENT_POOL : pool;
        return candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
    }

    public static String normalizeTournamentGame(String gameId) {
        if (gameId == null || gameId.isBlank() || isRandom(gameId)) {
            return RANDOM;
        }
        return gameId;
    }

    public static String label(String gameId) {
        return switch (gameId == null ? "" : gameId) {
            case RANDOM -> "系统随机剧本";
            case HEATMAP -> "日志热点排查竞赛";
            case TOPOLOGY -> "混沌工程攻防演练";
            case CLUSTER_PATROL -> "集群巡检对抗演练";
            default -> "热点区域对弈";
        };
    }

    public static String defaultName(String gameId) {
        return switch (gameId == null ? "" : gameId) {
            case RANDOM -> "系统随机演练";
            case HEATMAP -> "日志热区排查";
            case TOPOLOGY -> "混沌工程演练";
            case CLUSTER_PATROL -> "集群巡检对抗";
            default -> "热力五子棋";
        };
    }
}
