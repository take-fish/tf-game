package com.fishmatrix.game.service;

import com.fishmatrix.game.arena.ArenaGames;
import com.fishmatrix.game.config.GameProperties;
import com.fishmatrix.game.model.BlindLayout;
import com.fishmatrix.game.model.CreateDuelRequest;
import com.fishmatrix.game.model.CreateTournamentRequest;
import com.fishmatrix.game.model.GameException;
import com.fishmatrix.game.model.GameIds;
import com.fishmatrix.game.model.GameIdentity;
import com.fishmatrix.game.model.MatchFinishedEvent;
import com.fishmatrix.game.model.MatchRecord;
import com.fishmatrix.game.model.MatchView;
import com.fishmatrix.game.model.PlayerRef;
import com.fishmatrix.game.model.PlayerView;
import com.fishmatrix.game.model.TournamentPlayerRecord;
import com.fishmatrix.game.model.TournamentRecord;
import com.fishmatrix.game.model.TournamentView;
import com.fishmatrix.game.store.MatchRepository;
import com.fishmatrix.game.store.PlayerStatRepository;
import com.fishmatrix.game.store.TournamentRepository;
import com.fishmatrix.game.ws.GameSessionRegistry;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 赛事生命周期：
 * - 邀请 5 分钟，未响应视为拒绝；至少 2 人接受才开赛。
 * - 2 的幂随机单败；其余先积分排位（3-5 单循环，6+ 瑞士轮 3 轮），
 *   前四进入页面淘汰赛（三打四、胜者打二、决赛打一）。
 * - 赛事默认系统随机剧本；页面淘汰赛只有高顺位选手拥有首局剧本选择权。
 * - 只在赛事结束时给冠亚军发积分（冠军 30 / 亚军 10），比赛过程不计积分。
 */
@Service
public class TournamentService {

    private static final Logger log = LoggerFactory.getLogger(TournamentService.class);

    public static final String GAME_ID = GameIds.HEATMAP_GOMOKU;
    public static final String KIND_TOURNAMENT = "TOURNAMENT";
    public static final String KIND_DUEL = "DUEL";
    private static final Pattern USER_ID = Pattern.compile("u\\d{1,20}");
    private static final Set<String> FORMATS = Set.of("BO1", "BO3", "BO5");

    private final TournamentRepository tournaments;
    private final MatchRepository matches;
    private final PlayerStatRepository stats;
    private final GameSessionRegistry registry;
    private final GameNotificationService notifications;
    private final GameProperties properties;

    public TournamentService(
            TournamentRepository tournaments,
            MatchRepository matches,
            PlayerStatRepository stats,
            GameSessionRegistry registry,
            GameNotificationService notifications,
            GameProperties properties) {
        this.tournaments = tournaments;
        this.matches = matches;
        this.stats = stats;
        this.registry = registry;
        this.notifications = notifications;
        this.properties = properties;
    }

    @Transactional
    public TournamentView create(GameIdentity creator, CreateTournamentRequest request) {
        if (request == null || request.players() == null) {
            throw GameException.badRequest("GAME_TOURNAMENT_INVALID", "请选择参赛选手");
        }
        String format = request.format() == null ? "BO1" : request.format().strip().toUpperCase();
        if (!FORMATS.contains(format)) {
            throw GameException.badRequest("GAME_TOURNAMENT_INVALID", "赛制只支持 BO1 / BO3 / BO5");
        }
        String gameId = resolveGameId(request.gameId(), ArenaGames.RANDOM,
                "GAME_TOURNAMENT_INVALID");
        List<String> gamePool = resolveGamePool(gameId, request.gamePool());
        BlindLayout blindLayout = resolveBlindLayout(
                gameId, gamePool, request.blindLayout(), "GAME_TOURNAMENT_INVALID");
        String name = request.name() == null || request.name().isBlank()
                ? defaultTournamentName(gameId) : request.name().strip();
        if (name.codePointCount(0, name.length()) > 32) {
            throw GameException.badRequest("GAME_TOURNAMENT_INVALID", "赛事名称不能超过 32 个字符");
        }
        List<PlayerRef> refs = normalizePlayers(request.players());
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
                id, name, gameId, KIND_TOURNAMENT, format, "TBD", "INVITING",
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

        TournamentView view = view(tournament);
        if (!invitedIds.isEmpty()) {
            registry.broadcastUsers(invitedIds, Map.of(
                    "type", "game_invitation",
                    "tournament", view));
        }
        broadcastTournament(tournament);
        return view;
    }

    /**
     * 一对一约战：创建者自动接受，只邀请一名当前在线成员；复用同一套 BO 与落子引擎，
     * 但全程不进入演练大厅、不计排行榜、不产生全站播报。
     */
    @Transactional
    public TournamentView createDuel(GameIdentity creator, CreateDuelRequest request) {
        if (request == null || request.opponentUserId() == null
                || !USER_ID.matcher(request.opponentUserId()).matches()) {
            throw GameException.badRequest("GAME_DUEL_INVALID", "请选择约战对手");
        }
        String opponentId = request.opponentUserId();
        if (opponentId.equals(creator.userId())) {
            throw GameException.badRequest("GAME_DUEL_INVALID", "不能和自己约战");
        }
        String format = request.format() == null ? "BO1" : request.format().strip().toUpperCase();
        if (!FORMATS.contains(format)) {
            throw GameException.badRequest("GAME_DUEL_INVALID", "赛制只支持 BO1 / BO3 / BO5");
        }
        String gameId = resolveGameId(request.gameId(), ArenaGames.GOMOKU, "GAME_DUEL_INVALID");
        BlindLayout blindLayout = resolveBlindLayout(
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
                id, name, gameId, KIND_DUEL, format, "TBD", "INVITING",
                creator.userId(), creator.nickname(), 2,
                now + properties.inviteTimeoutSeconds() * 1000L,
                0, 0, null, null, null, null, now, null, null,
                blindLayout == null ? null : blindLayout.name(), List.of());
        tournaments.insert(duel);
        tournaments.insertPlayer(new TournamentPlayerRecord(
                id, creator.userId(), creator.nickname(), 1, "ACCEPTED", now, now));
        tournaments.insertPlayer(new TournamentPlayerRecord(
                id, opponentId, opponentNickname, 2, "INVITED", now, null));

        TournamentView view = view(duel);
        registry.broadcastUsers(Set.of(opponentId), Map.of(
                "type", "duel_invitation",
                "duel", view));
        registry.broadcastUsers(Set.of(creator.userId()), Map.of(
                "type", "duel_update",
                "duel", view));
        log.info("约战 {} 发起：{} → {}（{}）", id, creator.userId(), opponentId, format);
        return view;
    }

    public List<TournamentView> listDuelsForUser(String userId, int limit) {
        return tournaments.listDuelsForUser(userId, limit).stream()
                .map(this::view)
                .toList();
    }

    public TournamentView getDuel(GameIdentity user, String id) {
        TournamentRecord duel = tournaments.findById(id)
                .orElseThrow(() -> GameException.notFound("约战不存在"));
        if (!KIND_DUEL.equals(duel.kind())
                || tournaments.findPlayer(id, user.userId()).isEmpty()) {
            throw GameException.notFound("约战不存在");
        }
        return view(duel);
    }

    public TournamentView getTournament(String id) {
        TournamentRecord tournament = tournaments.findById(id)
                .orElseThrow(() -> GameException.notFound("赛事不存在"));
        if (!KIND_TOURNAMENT.equals(tournament.kind())) {
            throw GameException.notFound("赛事不存在");
        }
        return view(tournament);
    }

    public List<TournamentView> list(int limit) {
        return tournaments.listRecent(limit).stream()
                .map(this::view)
                .toList();
    }

    /**
     * 页面淘汰赛首局剧本选择：只有对阵记录中的高顺位选手可以改选，
     * 且必须在小局开始前完成；系统随机剧目则由服务端重新抽取。
     */
    @Transactional
    public MatchView chooseGame(GameIdentity user, String matchId, String gameId) {
        MatchRecord match = matches.findById(matchId)
                .orElseThrow(() -> GameException.notFound("比赛不存在"));
        TournamentRecord tournament = tournaments.findById(match.tournamentId())
                .orElseThrow(() -> GameException.notFound("赛事不存在"));
        if (!"RUNNING".equals(tournament.status())) {
            throw GameException.conflict("GAME_TOURNAMENT_CLOSED", "赛事尚未开始或已结束");
        }
        if (match.startedAt() != null
                || (!BracketPlanner.STATUS_READY.equals(match.status())
                && !BracketPlanner.STATUS_PENDING.equals(match.status()))) {
            throw GameException.conflict("GAME_MATCH_STARTED", "比赛已开始，不能改选剧本");
        }
        if (match.gameChooserUserId() == null
                || !match.gameChooserUserId().equals(user.userId())) {
            throw GameException.forbidden("只有高顺位选手拥有本场首局剧本选择权");
        }
        String normalized = resolveGameId(gameId, ArenaGames.RANDOM, "GAME_GAME_INVALID");
        boolean series = ArenaGames.isRandom(tournament.gameId());
        List<String> pool = enabledPool(tournament);
        String selected;
        if (ArenaGames.isRandom(normalized)) {
            selected = ArenaGames.randomGame(pool);
        } else {
            if (series && !pool.contains(normalized)) {
                throw GameException.badRequest("GAME_GAME_INVALID",
                        "该游戏未在 S赛启用池中");
            }
            selected = normalized;
        }
        if (matches.updateFirstGame(matchId, selected) == 0) {
            throw GameException.conflict("GAME_MATCH_STARTED", "比赛已开始，不能改选剧本");
        }
        MatchRecord updated = matches.findById(matchId).orElse(match);
        broadcastTournament(tournament);
        log.info("赛事 {} 比赛 {} 首局剧本由 {} 改选为 {}",
                tournament.id(), matchId, user.userId(), selected);
        return matchView(updated, tournament);
    }

    public TournamentView get(String id) {
        TournamentRecord tournament = tournaments.findById(id)
                .orElseThrow(() -> GameException.notFound("赛事不存在"));
        return view(tournament);
    }

    public List<TournamentView> invitations(String userId) {
        long now = System.currentTimeMillis();
        return tournaments.pendingInvitations(userId, now).stream()
                .map(this::view)
                .toList();
    }

    @Transactional
    public TournamentView accept(GameIdentity user, String tournamentId) {
        return respondInvitation(user, tournamentId, KIND_TOURNAMENT, true);
    }

    @Transactional
    public TournamentView decline(GameIdentity user, String tournamentId) {
        return respondInvitation(user, tournamentId, KIND_TOURNAMENT, false);
    }

    @Transactional
    public TournamentView acceptDuel(GameIdentity user, String tournamentId) {
        return respondInvitation(user, tournamentId, KIND_DUEL, true);
    }

    @Transactional
    public TournamentView declineDuel(GameIdentity user, String tournamentId) {
        return respondInvitation(user, tournamentId, KIND_DUEL, false);
    }

    private TournamentView respondInvitation(GameIdentity user, String tournamentId,
                                             String kind, boolean accept) {
        TournamentRecord tournament = requireInviting(tournamentId, kind);
        int updated = accept
                ? tournaments.accept(tournamentId, user.userId(), user.nickname(),
                        System.currentTimeMillis())
                : tournaments.decline(tournamentId, user.userId(), System.currentTimeMillis());
        if (updated == 0) {
            throw GameException.conflict("GAME_INVITATION_CLOSED", "邀请已失效或已响应");
        }
        startIfReady(tournament);
        return get(tournamentId);
    }

    @Transactional
    public TournamentView cancel(GameIdentity user, String tournamentId) {
        return cancel(user, tournamentId, KIND_TOURNAMENT);
    }

    @Transactional
    public TournamentView cancelDuel(GameIdentity user, String tournamentId) {
        return cancel(user, tournamentId, KIND_DUEL);
    }

    private TournamentView cancel(GameIdentity user, String tournamentId, String kind) {
        TournamentRecord tournament = tournaments.findById(tournamentId)
                .orElseThrow(() -> GameException.notFound(KIND_DUEL.equals(kind)
                        ? "约战不存在" : "赛事不存在"));
        if (!kind.equals(tournament.kind())) {
            throw GameException.notFound(KIND_DUEL.equals(kind) ? "约战不存在" : "赛事不存在");
        }
        if (!tournament.creatorUserId().equals(user.userId()) && !"ADMIN".equals(user.role())) {
            throw GameException.forbidden(KIND_DUEL.equals(kind)
                    ? "只有发起者可以取消约战" : "只有创建者可以取消赛事");
        }
        if (!"INVITING".equals(tournament.status())) {
            throw GameException.conflict("GAME_TOURNAMENT_STARTED",
                    KIND_DUEL.equals(kind) ? "约战已开始，不能取消" : "赛事已开始，不能取消");
        }
        tournaments.cancel(tournamentId, System.currentTimeMillis());
        TournamentRecord cancelled = tournaments.findById(tournamentId).orElse(tournament);
        announceCancelled(cancelled);
        return get(tournamentId);
    }

    /** 软删除：记录从大厅、邀请与详情中消失；进行中的赛事必须先结束。 */
    @Transactional
    public void delete(GameIdentity user, String tournamentId) {
        TournamentRecord tournament = tournaments.findById(tournamentId)
                .orElseThrow(() -> GameException.notFound("赛事不存在"));
        if (!KIND_TOURNAMENT.equals(tournament.kind())) {
            throw GameException.notFound("赛事不存在");
        }
        if (!tournament.creatorUserId().equals(user.userId()) && !"ADMIN".equals(user.role())) {
            throw GameException.forbidden("只有创建者可以删除赛事");
        }
        if ("RUNNING".equals(tournament.status())) {
            throw GameException.conflict("GAME_TOURNAMENT_RUNNING", "赛事进行中，不能删除");
        }
        if (tournaments.softDelete(tournamentId, System.currentTimeMillis()) == 0) {
            throw GameException.notFound("赛事不存在");
        }
        registry.broadcastAll(Map.of(
                "type", "tournament_deleted",
                "tournamentId", tournamentId));
        log.info("赛事 {} 已删除（{}）", tournamentId, tournament.status());
    }

    /** 邀请超时：未响应视为拒绝，再决定开赛或取消。 */
    @Scheduled(fixedDelay = 1000)
    @Transactional
    public void tickInvitations() {
        long now = System.currentTimeMillis();
        for (TournamentRecord tournament : tournaments.listInvitingExpired(now)) {
            try {
                tournaments.expireInvitations(tournament.id(), now);
                List<TournamentPlayerRecord> accepted = tournaments.acceptedPlayers(tournament.id());
                if (accepted.size() < properties.minPlayers()) {
                    tournaments.cancel(tournament.id(), now);
                    TournamentRecord cancelled = tournaments.findById(tournament.id())
                            .orElse(tournament);
                    announceCancelled(cancelled);
                    log.info("{} {} 因接受人数不足 {} 取消",
                            KIND_DUEL.equals(tournament.kind()) ? "约战" : "赛事",
                            tournament.id(), accepted.size());
                } else {
                    startIfReady(tournament);
                }
            } catch (Exception exception) {
                log.warn("处理邀请超时失败 tournament={}", tournament.id(), exception);
            }
        }
    }

    /**
     * 编排补偿：多场比赛几乎同时结束时，事件监听可能都看不到对方未提交的数据；
     * 定时任务按已提交状态补推进轮次、补激活对阵。
     */
    @Scheduled(fixedDelay = 2000)
    @Transactional
    public void tickProgress() {
        for (TournamentRecord tournament : tournaments.listRunning()) {
            try {
                if (BracketPlanner.STAGE_LEAGUE.equals(tournament.bracketType())) {
                    advanceLeague(tournament);
                } else {
                    activatePendingMatches(tournament);
                }
            } catch (Exception exception) {
                log.warn("推进赛事失败 tournament={}", tournament.id(), exception);
            }
        }
    }

    /** 所有邀请已响应后开赛；不足 2 人取消。 */
    @Transactional
    public void startIfReady(TournamentRecord tournament) {
        if (!"INVITING".equals(tournament.status())) {
            return;
        }
        List<TournamentPlayerRecord> waiting = tournaments.allAcceptedOrInvited(tournament.id());
        if (waiting.stream().anyMatch(player -> "INVITED".equals(player.status()))) {
            return;
        }
        List<TournamentPlayerRecord> accepted = tournaments.acceptedPlayers(tournament.id());
        if (accepted.size() < properties.minPlayers()) {
            tournaments.cancel(tournament.id(), System.currentTimeMillis());
            TournamentRecord cancelled = tournaments.findById(tournament.id()).orElse(tournament);
            announceCancelled(cancelled);
            return;
        }

        int seed = 1;
        for (TournamentPlayerRecord player : accepted) {
            tournaments.updateSeed(tournament.id(), player.userId(), seed);
            seed += 1;
        }
        accepted = tournaments.acceptedPlayers(tournament.id());
        int count = accepted.size();
        long now = System.currentTimeMillis();

        List<MatchRecord> planned;
        String bracketType;
        int totalRounds;
        if (BracketPlanner.isPowerOfTwo(count)) {
            bracketType = BracketPlanner.STAGE_KNOCKOUT;
            totalRounds = BracketPlanner.knockoutRounds(count);
            planned = BracketPlanner.planKnockout(tournament.id(), accepted, now);
        } else {
            bracketType = BracketPlanner.STAGE_LEAGUE;
            int leagueRounds = BracketPlanner.leagueRounds(count);
            totalRounds = leagueRounds + BracketPlanner.pagePlayoffRounds(Math.min(4, count));
            planned = count <= 5
                    ? BracketPlanner.planRoundRobin(tournament.id(), accepted, now)
                    : BracketPlanner.planSwissFirstRound(tournament.id(), accepted, now);
        }

        if (tournaments.start(tournament.id(), bracketType, totalRounds, now) == 0) {
            return;
        }
        for (MatchRecord match : planned) {
            matches.insertMatch(match);
            ensureFirstGame(tournament, match);
        }
        TournamentRecord started = tournaments.findById(tournament.id()).orElse(tournament);
        if (KIND_DUEL.equals(started.kind())) {
            broadcastDuelStarted(started);
        } else {
            broadcastTournament(started);
        }
        log.info("{} {} 开赛：{} 人，{}，{} 轮",
                KIND_DUEL.equals(started.kind()) ? "约战" : "赛事",
                started.id(), count, bracketType, totalRounds);
    }

    @EventListener
    @Transactional
    public void onMatchFinished(MatchFinishedEvent event) {
        MatchRecord match = matches.findById(event.matchId()).orElse(null);
        if (match == null) {
            return;
        }
        TournamentRecord tournament = tournaments.findById(match.tournamentId()).orElse(null);
        if (tournament == null || !"RUNNING".equals(tournament.status())) {
            return;
        }
        boolean finalMatch = isFinalMatch(tournament, match);

        if (KIND_TOURNAMENT.equals(tournament.kind())
                && !BracketPlanner.STATUS_BYE.equals(match.status())
                && match.winnerUserId() != null) {
            String actualGameId = match.firstGameId() == null
                    ? tournament.gameId() : match.firstGameId();
            recordLeaderboard(actualGameId, match, finalMatch);
        }

        if (match.nextMatchId() != null && match.winnerUserId() != null) {
            boolean slot1 = BracketPlanner.STAGE_KNOCKOUT.equals(match.stage())
                    && BracketPlanner.nextSlotIsPlayer1(match.slotNo());
            String winnerNickname = match.winnerUserId().equals(match.player1UserId())
                    ? match.player1Nickname() : match.player2Nickname();
            matches.fillNextSlot(match.nextMatchId(), match.winnerUserId(), winnerNickname, slot1);
            matches.findById(match.nextMatchId()).ifPresent(next -> {
                if (BracketPlanner.STATUS_PENDING.equals(next.status())
                        && next.player1UserId() != null && next.player2UserId() != null) {
                    matches.updateStatus(next.id(), BracketPlanner.STATUS_READY);
                }
            });
        }

        if (finalMatch) {
            finishTournament(tournament, match);
            return;
        }

        if (BracketPlanner.STAGE_LEAGUE.equals(match.stage())) {
            advanceLeague(tournament);
        } else {
            activatePendingMatches(tournament);
        }
        broadcastTournament(tournaments.findById(tournament.id()).orElse(tournament));
    }

    /** 积分赛一轮全部结束后：生成/激活下一轮，或取前四进入页面淘汰赛。 */
    @Transactional
    public void advanceLeague(TournamentRecord tournament) {
        if (!"RUNNING".equals(tournament.status())
                || !BracketPlanner.STAGE_LEAGUE.equals(tournament.bracketType())) {
            return;
        }
        int current = tournament.currentRound();
        int total = matches.countRoundMatches(tournament.id(), BracketPlanner.STAGE_LEAGUE, current);
        int finished = matches.countRoundFinished(tournament.id(), BracketPlanner.STAGE_LEAGUE, current);
        if (total == 0 || finished < total) {
            return;
        }
        int qualifierCount = Math.min(4, tournaments.acceptedPlayers(tournament.id()).size());
        int leagueRounds = Math.max(1, tournament.totalRounds()
                - BracketPlanner.pagePlayoffRounds(qualifierCount));

        if (current < leagueRounds) {
            int nextRound = current + 1;
            if (tournaments.advanceRound(tournament.id(), current, nextRound) == 0) {
                return;
            }
            List<TournamentPlayerRecord> players = tournaments.acceptedPlayers(tournament.id());
            if (players.size() >= 6) {
                generateSwissRound(tournament, players, nextRound);
            } else {
                matches.activateRound(tournament.id(), BracketPlanner.STAGE_LEAGUE, nextRound);
            }
            broadcastTournament(tournaments.findById(tournament.id()).orElse(tournament));
            return;
        }

        List<TournamentPlayerRecord> qualifiers = standings(tournament.id()).stream()
                .limit(qualifierCount)
                .map(Standing::player)
                .toList();
        if (qualifiers.size() < 3) {
            finishWithoutLadder(tournament, qualifiers);
            return;
        }
        int firstRound = current + 1;
        int totalRounds = current + BracketPlanner.pagePlayoffRounds(qualifiers.size());
        if (tournaments.startPagePlayoff(
                tournament.id(), totalRounds, firstRound, current) == 0) {
            return;
        }
        for (MatchRecord match : BracketPlanner.planPagePlayoff(
                tournament.id(), qualifiers, firstRound, System.currentTimeMillis())) {
            matches.insertMatch(match);
            ensureFirstGame(tournament, match);
        }
        TournamentRecord pagePlayoff = tournaments.findById(tournament.id()).orElse(tournament);
        broadcastTournament(pagePlayoff);
        log.info("赛事 {} 进入页面淘汰赛：{} / {} / {} / {}",
                pagePlayoff.id(), qualifiers.get(0).nickname(),
                qualifiers.size() > 1 ? qualifiers.get(1).nickname() : "-",
                qualifiers.size() > 2 ? qualifiers.get(2).nickname() : "-",
                qualifiers.size() > 3 ? qualifiers.get(3).nickname() : "-");
    }

    /** 把已具备双方、仍为 PENDING 的淘汰/页面淘汰对阵激活；并同步当前轮次展示。 */
    @Transactional
    public void activatePendingMatches(TournamentRecord tournament) {
        List<MatchRecord> all = matches.listByTournament(tournament.id());
        boolean changed = false;
        for (MatchRecord match : all) {
            if (BracketPlanner.STATUS_BYE.equals(match.status())
                    || "FINISHED".equals(match.status())) {
                continue;
            }
            if (match.firstGameId() == null) {
                ensureFirstGame(tournament, match);
                changed = true;
            }
            if (BracketPlanner.STATUS_PENDING.equals(match.status())
                    && match.player1UserId() != null && match.player2UserId() != null) {
                matches.updateStatus(match.id(), BracketPlanner.STATUS_READY);
                changed = true;
            }
        }
        if (BracketPlanner.STAGE_KNOCKOUT.equals(tournament.bracketType())
                || BracketPlanner.STAGE_PAGE_PLAYOFF.equals(tournament.bracketType())) {
            int activeRound = all.stream()
                    .filter(match -> !BracketPlanner.STATUS_BYE.equals(match.status())
                            && !"FINISHED".equals(match.status()))
                    .mapToInt(MatchRecord::roundNo)
                    .min()
                    .orElse(tournament.totalRounds());
            if (activeRound != tournament.currentRound()) {
                tournaments.updateCurrentRound(tournament.id(), activeRound);
                changed = true;
            }
        }
        if (changed) {
            broadcastTournament(tournaments.findById(tournament.id()).orElse(tournament));
        }
    }

    public TournamentView view(TournamentRecord tournament) {
        List<PlayerView> players = tournaments.listPlayers(tournament.id()).stream()
                .map(player -> new PlayerView(
                        player.userId(), player.nickname(), player.seed(), player.status()))
                .toList();
        List<MatchView> views = matches.listByTournament(tournament.id()).stream()
                .map(match -> matchView(match, tournament))
                .toList();
        return new TournamentView(
                tournament.id(), tournament.name(), tournament.gameId(), tournament.kind(),
                tournament.format(),
                tournament.bracketType(), tournament.status(), tournament.creatorUserId(),
                tournament.creatorNickname(), tournament.targetPlayers(), tournament.inviteDeadline(),
                tournament.currentRound(), tournament.totalRounds(), tournament.championUserId(),
                tournament.championNickname(), tournament.runnerUpUserId(),
                tournament.runnerUpNickname(), tournament.createdAt(), tournament.startedAt(),
                tournament.finishedAt(), players, views, tournament.blindLayout(),
                tournament.gamePool());
    }

    private MatchView matchView(MatchRecord match, TournamentRecord tournament) {
        String effectiveGameId = match.firstGameId() == null
                ? tournament.gameId() : match.firstGameId();
        return new MatchView(
                match.id(), match.tournamentId(), effectiveGameId, tournament.kind(),
                match.roundNo(), match.slotNo(), match.stage(), match.status(),
                match.player1UserId(), match.player1Nickname(),
                match.player2UserId(), match.player2Nickname(), match.player1Score(),
                match.player2Score(), match.winnerUserId(), match.nextMatchId(),
                match.firstGameId(), match.gameChooserUserId(),
                match.createdAt(), match.startedAt(), match.finishedAt(), null,
                tournament.blindLayout());
    }

    private void recordLeaderboard(String gameId, MatchRecord match, boolean champion) {
        String winnerId = match.winnerUserId();
        boolean winnerIsPlayer1 = winnerId.equals(match.player1UserId());
        String winnerNickname = winnerIsPlayer1 ? match.player1Nickname() : match.player2Nickname();
        String loserId = winnerIsPlayer1 ? match.player2UserId() : match.player1UserId();
        String loserNickname = winnerIsPlayer1 ? match.player2Nickname() : match.player1Nickname();
        stats.recordMatch(winnerId, winnerNickname, true, champion);
        stats.recordGameMatch(gameId, winnerId, winnerNickname, true, champion);
        if (loserId != null) {
            stats.recordMatch(loserId, loserNickname, false, false);
            stats.recordGameMatch(gameId, loserId, loserNickname, false, false);
        }
    }

    private void finishTournament(TournamentRecord tournament, MatchRecord finalMatch) {
        String championId = finalMatch.winnerUserId();
        if (championId == null) {
            championId = finalMatch.player1UserId();
        }
        boolean championIsPlayer1 = championId != null
                && championId.equals(finalMatch.player1UserId());
        String championNickname = championIsPlayer1
                ? finalMatch.player1Nickname() : finalMatch.player2Nickname();
        String runnerUpId = championIsPlayer1
                ? finalMatch.player2UserId() : finalMatch.player1UserId();
        String runnerUpNickname = championIsPlayer1
                ? finalMatch.player2Nickname() : finalMatch.player1Nickname();
        long now = System.currentTimeMillis();
        tournaments.finish(tournament.id(), championId, championNickname,
                runnerUpId, runnerUpNickname, now);
        if (KIND_TOURNAMENT.equals(tournament.kind())) {
            if (championId != null) {
                stats.recordTitle(championId, championNickname, true);
            }
            if (runnerUpId != null) {
                stats.recordTitle(runnerUpId, runnerUpNickname, false);
            }
        }
        TournamentRecord finished = tournaments.findById(tournament.id()).orElse(tournament);
        if (KIND_TOURNAMENT.equals(finished.kind())) {
            notifications.publishChampion(finished);
        }
        broadcastTournament(finished);
        log.info("{} {} 结束，冠军 {}（{}），亚军 {}（{}）",
                KIND_DUEL.equals(finished.kind()) ? "约战" : "赛事",
                finished.id(), championNickname, championId, runnerUpNickname, runnerUpId);
    }

    private void finishWithoutLadder(TournamentRecord tournament,
                                     List<TournamentPlayerRecord> ranked) {
        if (ranked.isEmpty()) {
            tournaments.cancel(tournament.id(), System.currentTimeMillis());
            broadcastTournament(tournament);
            return;
        }
        TournamentPlayerRecord champion = ranked.get(0);
        TournamentPlayerRecord runnerUp = ranked.size() > 1 ? ranked.get(1) : null;
        long now = System.currentTimeMillis();
        tournaments.finish(tournament.id(), champion.userId(), champion.nickname(),
                runnerUp == null ? null : runnerUp.userId(),
                runnerUp == null ? null : runnerUp.nickname(), now);
        if (KIND_TOURNAMENT.equals(tournament.kind())) {
            stats.recordTitle(champion.userId(), champion.nickname(), true);
            if (runnerUp != null) {
                stats.recordTitle(runnerUp.userId(), runnerUp.nickname(), false);
            }
        }
        TournamentRecord finished = tournaments.findById(tournament.id()).orElse(tournament);
        if (KIND_TOURNAMENT.equals(finished.kind())) {
            notifications.publishChampion(finished);
        }
        broadcastTournament(finished);
    }

    private List<Standing> standings(String tournamentId) {
        Map<String, int[]> counters = new HashMap<>();
        List<TournamentPlayerRecord> players = tournaments.acceptedPlayers(tournamentId);
        for (TournamentPlayerRecord player : players) {
            counters.put(player.userId(), new int[]{0, 0});
        }
        for (MatchRecord match : matches.listByStage(tournamentId, BracketPlanner.STAGE_LEAGUE)) {
            boolean decided = BracketPlanner.STATUS_BYE.equals(match.status())
                    || "FINISHED".equals(match.status());
            if (!decided || match.winnerUserId() == null) {
                continue;
            }
            int[] wins = counters.get(match.winnerUserId());
            if (wins != null) {
                wins[0] += 3;
                wins[1] += 1;
            }
        }
        return players.stream()
                .map(player -> {
                    int[] value = counters.getOrDefault(player.userId(), new int[]{0, 0});
                    return new Standing(player, value[0], value[1]);
                })
                .sorted(Comparator.comparingInt((Standing value) -> value.points()).reversed()
                        .thenComparing(Comparator.comparingInt(
                                (Standing value) -> value.wins()).reversed())
                        .thenComparingInt(value -> value.player().seed()))
                .toList();
    }

    /** 瑞士轮下一轮：按积分排序，尽量避开已经交手过的对手；奇数人数最低分轮空。 */
    private void generateSwissRound(TournamentRecord tournament,
                                    List<TournamentPlayerRecord> players, int roundNo) {
        List<Standing> ordered = standings(tournament.id());
        List<TournamentPlayerRecord> remaining = new ArrayList<>(
                ordered.stream().map(Standing::player).toList());
        if (remaining.isEmpty()) {
            return;
        }
        List<MatchRecord> played = matches.listByStage(tournament.id(), BracketPlanner.STAGE_LEAGUE);
        Set<String> playedPairs = new HashSet<>();
        Set<String> byeIds = new HashSet<>();
        for (MatchRecord match : played) {
            if (BracketPlanner.STATUS_BYE.equals(match.status()) && match.winnerUserId() != null) {
                byeIds.add(match.winnerUserId());
            } else if (match.player1UserId() != null && match.player2UserId() != null) {
                playedPairs.add(pairKey(match.player1UserId(), match.player2UserId()));
            }
        }

        List<MatchRecord> planned = new ArrayList<>();
        int slot = 1;
        if (remaining.size() % 2 == 1) {
            TournamentPlayerRecord byePlayer = remaining.stream()
                    .filter(player -> !byeIds.contains(player.userId()))
                    .reduce((first, second) -> second)
                    .orElse(remaining.get(remaining.size() - 1));
            remaining.remove(byePlayer);
            planned.add(BracketPlanner.bye(
                    tournament.id(), roundNo, slot++, byePlayer, System.currentTimeMillis()));
        }

        Set<String> paired = new HashSet<>();
        for (int i = 0; i < remaining.size(); i += 1) {
            TournamentPlayerRecord first = remaining.get(i);
            if (paired.contains(first.userId())) {
                continue;
            }
            int partnerIndex = -1;
            for (int j = i + 1; j < remaining.size(); j += 1) {
                TournamentPlayerRecord candidate = remaining.get(j);
                if (!paired.contains(candidate.userId())
                        && !playedPairs.contains(pairKey(first.userId(), candidate.userId()))) {
                    partnerIndex = j;
                    break;
                }
            }
            if (partnerIndex < 0) {
                for (int j = i + 1; j < remaining.size(); j += 1) {
                    if (!paired.contains(remaining.get(j).userId())) {
                        partnerIndex = j;
                        break;
                    }
                }
            }
            if (partnerIndex < 0) {
                continue;
            }
            TournamentPlayerRecord second = remaining.get(partnerIndex);
            paired.add(first.userId());
            paired.add(second.userId());
            planned.add(new MatchRecord(
                    UUID.randomUUID().toString(), tournament.id(), roundNo, slot++,
                    BracketPlanner.STAGE_LEAGUE, BracketPlanner.STATUS_READY,
                    first.userId(), first.nickname(), second.userId(), second.nickname(),
                    0, 0, null, null, System.currentTimeMillis(), null, null));
        }
        for (MatchRecord match : planned) {
            matches.insertMatch(match);
        }
    }

    /** 新开对阵的首局剧本：S赛从启用池逐场抽取，锦标赛固定配置值。 */
    private static String resolveGame(TournamentRecord tournament) {
        return ArenaGames.isRandom(tournament.gameId())
                ? ArenaGames.randomGame(enabledPool(tournament)) : tournament.gameId();
    }

    /** 历史 S赛没有 game_pool 字段，回退为全部 1v1 剧本。 */
    private static List<String> enabledPool(TournamentRecord tournament) {
        List<String> pool = tournament.gamePool();
        return pool == null || pool.isEmpty() ? ArenaGames.TOURNAMENT_POOL : pool;
    }

    private void ensureFirstGame(TournamentRecord tournament, MatchRecord match) {
        if (match.firstGameId() != null || BracketPlanner.STATUS_BYE.equals(match.status())) {
            return;
        }
        matches.initFirstGame(match.id(), resolveGame(tournament),
                match.gameChooserUserId());
    }

    private static String pairKey(String first, String second) {
        return first.compareTo(second) < 0 ? first + "|" + second : second + "|" + first;
    }

    private static boolean isFinalMatch(TournamentRecord tournament, MatchRecord match) {
        if (!BracketPlanner.STAGE_KNOCKOUT.equals(match.stage())
                && !BracketPlanner.STAGE_LADDER.equals(match.stage())
                && !BracketPlanner.STAGE_PAGE_PLAYOFF.equals(match.stage())) {
            return false;
        }
        return match.roundNo() == tournament.totalRounds();
    }

    private TournamentRecord requireInviting(String tournamentId, String kind) {
        TournamentRecord tournament = tournaments.findById(tournamentId)
                .orElseThrow(() -> GameException.notFound(KIND_DUEL.equals(kind)
                        ? "约战不存在" : "赛事不存在"));
        if (!kind.equals(tournament.kind())) {
            throw GameException.notFound(KIND_DUEL.equals(kind) ? "约战不存在" : "赛事不存在");
        }
        if (!"INVITING".equals(tournament.status())) {
            throw GameException.conflict("GAME_TOURNAMENT_CLOSED",
                    KIND_DUEL.equals(kind) ? "约战邀请已结束" : "赛事邀请已结束");
        }
        return tournament;
    }

    private static String resolveGameId(String raw, String defaultGameId, String errorCode) {
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
    private static List<String> resolveGamePool(String gameId, List<String> raw) {
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
    private static BlindLayout resolveBlindLayout(String gameId, List<String> gamePool,
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

    private static String defaultTournamentName(String gameId) {
        return GameIds.BLIND_XIANGQI.equals(gameId)
                ? "盲棋" : ArenaGames.defaultName(gameId);
    }

    private List<PlayerRef> normalizePlayers(List<PlayerRef> input) {
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

    private void broadcastTournament(TournamentRecord tournament) {
        if (KIND_DUEL.equals(tournament.kind())) {
            broadcastDuel(tournament, Map.of(
                    "type", "duel_update",
                    "duel", view(tournament)));
            return;
        }
        registry.broadcastAll(Map.of(
                "type", "tournament_update",
                "tournament", view(tournament)));
    }

    /** 约战只通知双方，避免任何第三方感知到对局。 */
    private void broadcastDuel(TournamentRecord tournament, Map<String, Object> frame) {
        Set<String> users = tournaments.listPlayers(tournament.id()).stream()
                .map(TournamentPlayerRecord::userId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        registry.broadcastUsers(users, frame);
    }

    private void broadcastDuelStarted(TournamentRecord tournament) {
        TournamentView view = view(tournament);
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", "duel_started");
        frame.put("duel", view);
        if (!view.matches().isEmpty()) {
            frame.put("match", view.matches().get(0));
        }
        broadcastDuel(tournament, frame);
    }

    private void announceCancelled(TournamentRecord tournament) {
        if (KIND_DUEL.equals(tournament.kind())) {
            broadcastDuel(tournament, Map.of(
                    "type", "duel_update",
                    "duel", view(tournament)));
            return;
        }
        broadcastTournament(tournament);
        registry.broadcastAll(Map.of(
                "type", "tournament_cancelled",
                "tournamentId", tournament.id()));
    }

    private record Standing(TournamentPlayerRecord player, int points, int wins) {
    }
}
