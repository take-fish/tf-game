package com.fishmatrix.game.service;

import com.fishmatrix.game.arena.ArenaGames;
import com.fishmatrix.game.model.BlindLayout;
import com.fishmatrix.game.model.GameException;
import com.fishmatrix.game.model.GameIds;
import com.fishmatrix.game.model.MatchRecord;
import com.fishmatrix.game.model.PlayerRef;
import com.fishmatrix.game.model.TournamentRecord;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 赛事参数与规则校验：无状态，供创建、编排与查询共用。
 */
final class TournamentRules {

    static final Pattern USER_ID = Pattern.compile("u\\d{1,20}");
    static final Set<String> FORMATS = Set.of("BO1", "BO3", "BO5");

    private TournamentRules() {
    }

    static String resolveGameId(String raw, String defaultGameId, String errorCode) {
        if (raw == null || raw.isBlank()) {
            return defaultGameId;
        }
        String gameId = raw.strip().toLowerCase(Locale.ROOT);
        if (!GameIds.ALL.contains(gameId) && !ArenaGames.ALL.contains(gameId)) {
            throw GameException.badRequest(errorCode, "不支持的游戏类型");
        }
        return gameId;
    }

    /**
     * S赛启用池缺省为全部 1v1 游戏；显式传入时必须非空、去重且都在剧本池内。
     * 固定剧本的锦标赛不保存随机池。
     */
    static List<String> resolveGamePool(String gameId, List<String> raw) {
        if (!ArenaGames.isRandom(gameId)) {
            return List.of();
        }
        if (raw == null) {
            return ArenaGames.TOURNAMENT_POOL;
        }
        LinkedHashSet<String> pool = new LinkedHashSet<>();
        for (String entry : raw) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            String normalized = entry.strip().toLowerCase(Locale.ROOT);
            if (!ArenaGames.TOURNAMENT_POOL.contains(normalized)) {
                throw GameException.badRequest("GAME_TOURNAMENT_INVALID",
                        "随机池包含不支持的游戏");
            }
            pool.add(normalized);
        }
        if (pool.isEmpty()) {
            throw GameException.badRequest("GAME_TOURNAMENT_INVALID",
                    "S赛至少需要启用一个游戏");
        }
        return List.copyOf(pool);
    }

    /** 只有盲棋参与时才解析摆法；S赛抽到盲棋时沿用赛事级摆法，缺省标准点位。 */
    static BlindLayout resolveBlindLayout(String gameId, List<String> gamePool,
                                         String raw, String errorCode) {
        boolean blindInPlay = GameIds.BLIND_XIANGQI.equals(gameId)
                || (ArenaGames.isRandom(gameId) && gamePool.contains(GameIds.BLIND_XIANGQI));
        if (!blindInPlay) {
            return null;
        }
        BlindLayout layout = BlindLayout.parse(raw);
        if (layout == null) {
            throw GameException.badRequest(errorCode,
                    "盲棋摆法只支持 STANDARD_SHUFFLE / FULL_RANDOM / HALF_RANDOM");
        }
        return layout;
    }

    static String defaultTournamentName(String gameId) {
        return GameIds.BLIND_XIANGQI.equals(gameId)
                ? "盲棋" : ArenaGames.defaultName(gameId);
    }

    static List<PlayerRef> normalizePlayers(List<PlayerRef> input) {
        List<PlayerRef> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (PlayerRef ref : input) {
            if (ref == null || ref.userId() == null || !USER_ID.matcher(ref.userId()).matches()) {
                throw GameException.badRequest("GAME_PLAYER_INVALID", "选手身份不合法");
            }
            if (!seen.add(ref.userId())) {
                throw GameException.badRequest("GAME_PLAYER_INVALID", "选手不能重复");
            }
            String nickname = ref.nickname() == null ? "" : ref.nickname().strip();
            if (nickname.isEmpty()) {
                nickname = ref.userId();
            }
            if (nickname.codePointCount(0, nickname.length()) > 32) {
                throw GameException.badRequest("GAME_PLAYER_INVALID", "选手昵称不能超过 32 个字符");
            }
            result.add(new PlayerRef(ref.userId(), nickname));
        }
        return result;
    }

    /** 新开对阵的首局剧本：S赛从启用池逐场抽取，锦标赛固定配置值。 */
    static String resolveGame(TournamentRecord tournament) {
        return ArenaGames.isRandom(tournament.gameId())
                ? ArenaGames.randomGame(enabledPool(tournament)) : tournament.gameId();
    }

    /** 历史 S赛没有 game_pool 字段，回退为全部 1v1 剧本。 */
    static List<String> enabledPool(TournamentRecord tournament) {
        List<String> pool = tournament.gamePool();
        return pool == null || pool.isEmpty() ? ArenaGames.TOURNAMENT_POOL : pool;
    }

    static String pairKey(String first, String second) {
        return first.compareTo(second) < 0 ? first + "|" + second : second + "|" + first;
    }

    static boolean isFinalMatch(TournamentRecord tournament, MatchRecord match) {
        if (!BracketPlanner.STAGE_KNOCKOUT.equals(match.stage())
                && !BracketPlanner.STAGE_LADDER.equals(match.stage())
                && !BracketPlanner.STAGE_PAGE_PLAYOFF.equals(match.stage())) {
            return false;
        }
        return match.roundNo() == tournament.totalRounds();
    }
}
