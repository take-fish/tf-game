package com.fishmatrix.game.service;

import com.fishmatrix.game.arena.ArenaGames;
import com.fishmatrix.game.config.GameProperties;
import com.fishmatrix.game.model.BlindLayout;
import com.fishmatrix.game.model.CreateDuelRequest;
import com.fishmatrix.game.model.CreateTournamentRequest;
import com.fishmatrix.game.model.GameException;
import com.fishmatrix.game.model.GameIds;
import com.fishmatrix.game.model.GameIdentity;
import com.fishmatrix.game.model.PlayerRef;
import com.fishmatrix.game.model.TournamentPlayerRecord;
import com.fishmatrix.game.model.TournamentRecord;
import com.fishmatrix.game.model.TournamentView;
import com.fishmatrix.game.store.TournamentRepository;
import com.fishmatrix.game.ws.GameSessionRegistry;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 赛事与约战的创建：参数校验、初始记录写入与邀请推送。
 */
@Slf4j
@RequiredArgsConstructor
@Service
class TournamentCreationService {

    private final TournamentRepository tournaments;
    private final GameSessionRegistry registry;
    private final GameProperties properties;
    private final TournamentViewAssembler assembler;
    private final TournamentBroadcaster broadcaster;

    @Transactional
    TournamentView create(GameIdentity creator, CreateTournamentRequest request) {
        if (request == null || request.players() == null) {
            throw GameException.badRequest("GAME_TOURNAMENT_INVALID", "请选择参赛选手");
        }
        String format = request.format() == null ? "BO1" : request.format().strip().toUpperCase();
        if (!TournamentRules.FORMATS.contains(format)) {
            throw GameException.badRequest("GAME_TOURNAMENT_INVALID", "赛制只支持 BO1 / BO3 / BO5");
        }
        String gameId = TournamentRules.resolveGameId(
                request.gameId(), ArenaGames.RANDOM, "GAME_TOURNAMENT_INVALID");
        List<String> gamePool = TournamentRules.resolveGamePool(gameId, request.gamePool());
        BlindLayout blindLayout = TournamentRules.resolveBlindLayout(
                gameId, gamePool, request.blindLayout(), "GAME_TOURNAMENT_INVALID");
        String name = request.name() == null || request.name().isBlank()
                ? TournamentRules.defaultTournamentName(gameId) : request.name().strip();
        if (name.codePointCount(0, name.length()) > 32) {
            throw GameException.badRequest("GAME_TOURNAMENT_INVALID", "赛事名称不能超过 32 个字符");
        }
        List<PlayerRef> refs = TournamentRules.normalizePlayers(request.players());
        if (refs.size() < properties.minPlayers()) {
            throw GameException.badRequest("GAME_TOURNAMENT_INVALID",
                    "至少需要 " + properties.minPlayers() + " 名选手");
        }
        if (refs.size() > properties.maxPlayers()) {
            throw GameException.badRequest("GAME_TOURNAMENT_INVALID",
                    "最多支持 " + properties.maxPlayers() + " 名选手");
        }

        long now = System.currentTimeMillis();
        String id = UUID.randomUUID().toString();
        TournamentRecord tournament = new TournamentRecord(
                id, name, gameId, TournamentService.KIND_TOURNAMENT, format, "TBD", "INVITING",
                creator.userId(), creator.nickname(), refs.size(),
                now + properties.inviteTimeoutSeconds() * 1000L,
                0, 0, null, null, null, null, now, null, null,
                blindLayout == null ? null : blindLayout.name(), gamePool);
        tournaments.insert(tournament);

        Set<String> invitedIds = new LinkedHashSet<>();
        int inviteOrder = 1;
        for (PlayerRef ref : refs) {
            boolean self = ref.userId().equals(creator.userId());
            tournaments.insertPlayer(new TournamentPlayerRecord(
                    id, ref.userId(), ref.nickname(), 0,
                    self ? "ACCEPTED" : "INVITED",
                    now, self ? now : null));
            if (!self) {
                invitedIds.add(ref.userId());
            } else {
                // 自建赛时创建者作为 1 号种子，其余按邀请顺序。
                tournaments.updateSeed(id, ref.userId(), inviteOrder);
            }
            inviteOrder += 1;
        }

        TournamentView view = assembler.view(tournament);
        if (!invitedIds.isEmpty()) {
            registry.broadcastUsers(invitedIds, Map.of(
                    "type", "game_invitation",
                    "tournament", view));
        }
        broadcaster.broadcastTournament(tournament);
        return view;
    }

    /**
     * 一对一约战：创建者自动接受，只邀请一名当前在线成员；复用同一套 BO 与落子引擎，
     * 但全程不进入演练大厅、不计排行榜、不产生全站播报。
     */
    @Transactional
    TournamentView createDuel(GameIdentity creator, CreateDuelRequest request) {
        if (request == null || request.opponentUserId() == null
                || !TournamentRules.USER_ID.matcher(request.opponentUserId()).matches()) {
            throw GameException.badRequest("GAME_DUEL_INVALID", "请选择约战对手");
        }
        String opponentId = request.opponentUserId();
        if (opponentId.equals(creator.userId())) {
            throw GameException.badRequest("GAME_DUEL_INVALID", "不能和自己约战");
        }
        String format = request.format() == null ? "BO1" : request.format().strip().toUpperCase();
        if (!TournamentRules.FORMATS.contains(format)) {
            throw GameException.badRequest("GAME_DUEL_INVALID", "赛制只支持 BO1 / BO3 / BO5");
        }
        String gameId = TournamentRules.resolveGameId(
                request.gameId(), ArenaGames.GOMOKU, "GAME_DUEL_INVALID");
        BlindLayout blindLayout = TournamentRules.resolveBlindLayout(
                gameId, List.of(), request.blindLayout(), "GAME_DUEL_INVALID");
        String opponentNickname = request.opponentNickname() == null
                ? "" : request.opponentNickname().strip();
        if (opponentNickname.isEmpty()) {
            opponentNickname = opponentId;
        }
        if (opponentNickname.codePointCount(0, opponentNickname.length()) > 32) {
            throw GameException.badRequest("GAME_DUEL_INVALID", "对手昵称不能超过 32 个字符");
        }
        if (registry.byUser(opponentId).isEmpty()) {
            throw GameException.conflict("GAME_OPPONENT_OFFLINE", "对方当前不在线，无法发起约战");
        }
        if (tournaments.existsActiveDuelBetween(creator.userId(), opponentId, gameId)) {
            throw GameException.conflict(
                    "GAME_DUEL_ACTIVE", "你们在这个游戏下已有一场未结束的约战");
        }

        long now = System.currentTimeMillis();
        String id = UUID.randomUUID().toString();
        String name = GameIds.BLIND_XIANGQI.equals(gameId) ? "盲棋约战" : "一对一约战";
        TournamentRecord duel = new TournamentRecord(
                id, name, gameId, TournamentService.KIND_DUEL, format, "TBD", "INVITING",
                creator.userId(), creator.nickname(), 2,
                now + properties.inviteTimeoutSeconds() * 1000L,
                0, 0, null, null, null, null, now, null, null,
                blindLayout == null ? null : blindLayout.name(), List.of());
        tournaments.insert(duel);
        tournaments.insertPlayer(new TournamentPlayerRecord(
                id, creator.userId(), creator.nickname(), 1, "ACCEPTED", now, now));
        tournaments.insertPlayer(new TournamentPlayerRecord(
                id, opponentId, opponentNickname, 2, "INVITED", now, null));

        TournamentView view = assembler.view(duel);
        registry.broadcastUsers(Set.of(opponentId), Map.of(
                "type", "duel_invitation",
                "duel", view));
        registry.broadcastUsers(Set.of(creator.userId()), Map.of(
                "type", "duel_update",
                "duel", view));
        log.info("约战 {} 发起：{} → {}（{}）", id, creator.userId(), opponentId, format);
        return view;
    }
}
