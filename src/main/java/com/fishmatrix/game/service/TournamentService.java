package com.fishmatrix.game.service;

import com.fishmatrix.game.arena.ArenaGames;
import com.fishmatrix.game.config.GameProperties;
import com.fishmatrix.game.model.CreateDuelRequest;
import com.fishmatrix.game.model.CreateTournamentRequest;
import com.fishmatrix.game.model.GameException;
import com.fishmatrix.game.model.GameIds;
import com.fishmatrix.game.model.GameIdentity;
import com.fishmatrix.game.model.MatchFinishedEvent;
import com.fishmatrix.game.model.MatchRecord;
import com.fishmatrix.game.model.MatchView;
import com.fishmatrix.game.model.TournamentPlayerRecord;
import com.fishmatrix.game.model.TournamentRecord;
import com.fishmatrix.game.model.TournamentView;
import com.fishmatrix.game.store.MatchRepository;
import com.fishmatrix.game.store.PlayerStatRepository;
import com.fishmatrix.game.store.TournamentRepository;
import com.fishmatrix.game.ws.GameSessionRegistry;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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
@Slf4j
@Service
public class TournamentService {

    public static final String GAME_ID = GameIds.HEATMAP_GOMOKU;
    public static final String KIND_TOURNAMENT = "TOURNAMENT";
    public static final String KIND_DUEL = "DUEL";

    private final TournamentRepository tournaments;
    private final MatchRepository matches;
    private final GameProperties properties;
    private final TournamentCreationService creation;
    private final TournamentBracketService bracket;
    private final TournamentViewAssembler assembler;
    private final TournamentBroadcaster broadcaster;
    private final GameSessionRegistry registry;

    @Autowired
    public TournamentService(
            TournamentRepository tournaments,
            MatchRepository matches,
            GameProperties properties,
            TournamentCreationService creation,
            TournamentBracketService bracket,
            TournamentViewAssembler assembler,
            TournamentBroadcaster broadcaster,
            GameSessionRegistry registry) {
        this.tournaments = tournaments;
        this.matches = matches;
        this.properties = properties;
        this.creation = creation;
        this.bracket = bracket;
        this.assembler = assembler;
        this.broadcaster = broadcaster;
        this.registry = registry;
    }

    /** 单元测试用：用原始依赖就地组装各协作组件。 */
    TournamentService(
            TournamentRepository tournaments,
            MatchRepository matches,
            PlayerStatRepository stats,
            GameSessionRegistry registry,
            GameNotificationService notifications,
            GameProperties properties) {
        this.tournaments = tournaments;
        this.matches = matches;
        this.properties = properties;
        this.registry = registry;
        this.assembler = new TournamentViewAssembler(tournaments, matches);
        this.broadcaster = new TournamentBroadcaster(registry, tournaments, this.assembler);
        this.creation = new TournamentCreationService(
                tournaments, registry, properties, this.assembler, this.broadcaster);
        this.bracket = new TournamentBracketService(
                tournaments, matches, stats, notifications, properties, this.broadcaster);
    }

    @Transactional
    public TournamentView create(GameIdentity creator, CreateTournamentRequest request) {
        return creation.create(creator, request);
    }

    /**
     * 一对一约战：创建者自动接受，只邀请一名当前在线成员；复用同一套 BO 与落子引擎，
     * 但全程不进入演练大厅、不计排行榜、不产生全站播报。
     */
    @Transactional
    public TournamentView createDuel(GameIdentity creator, CreateDuelRequest request) {
        return creation.createDuel(creator, request);
    }

    public List<TournamentView> listDuelsForUser(String userId, int limit) {
        return tournaments.listDuelsForUser(userId, limit).stream()
                .map(assembler::view)
                .toList();
    }

    public TournamentView getDuel(GameIdentity user, String id) {
        TournamentRecord duel = tournaments.findById(id)
                .orElseThrow(() -> GameException.notFound("约战不存在"));
        if (!KIND_DUEL.equals(duel.kind())
                || tournaments.findPlayer(id, user.userId()).isEmpty()) {
            throw GameException.notFound("约战不存在");
        }
        return assembler.view(duel);
    }

    public TournamentView getTournament(String id) {
        TournamentRecord tournament = tournaments.findById(id)
                .orElseThrow(() -> GameException.notFound("赛事不存在"));
        if (!KIND_TOURNAMENT.equals(tournament.kind())) {
            throw GameException.notFound("赛事不存在");
        }
        return assembler.view(tournament);
    }

    public List<TournamentView> list(int limit) {
        return tournaments.listRecent(limit).stream()
                .map(assembler::view)
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
        String normalized = TournamentRules.resolveGameId(
                gameId, ArenaGames.RANDOM, "GAME_GAME_INVALID");
        boolean series = ArenaGames.isRandom(tournament.gameId());
        List<String> pool = TournamentRules.enabledPool(tournament);
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
        broadcaster.broadcastTournament(tournament);
        log.info("赛事 {} 比赛 {} 首局剧本由 {} 改选为 {}",
                tournament.id(), matchId, user.userId(), selected);
        return assembler.matchView(updated, tournament);
    }

    public TournamentView get(String id) {
        TournamentRecord tournament = tournaments.findById(id)
                .orElseThrow(() -> GameException.notFound("赛事不存在"));
        return assembler.view(tournament);
    }

    public List<TournamentView> invitations(String userId) {
        long now = System.currentTimeMillis();
        return tournaments.pendingInvitations(userId, now).stream()
                .map(assembler::view)
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
        bracket.startIfReady(tournament);
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
        broadcaster.announceCancelled(cancelled);
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
                List<TournamentPlayerRecord> accepted =
                        tournaments.acceptedPlayers(tournament.id());
                if (accepted.size() < properties.minPlayers()) {
                    tournaments.cancel(tournament.id(), now);
                    TournamentRecord cancelled = tournaments.findById(tournament.id())
                            .orElse(tournament);
                    broadcaster.announceCancelled(cancelled);
                    log.info("{} {} 因接受人数不足 {} 取消",
                            KIND_DUEL.equals(tournament.kind()) ? "约战" : "赛事",
                            tournament.id(), accepted.size());
                } else {
                    bracket.startIfReady(tournament);
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
        bracket.tickProgress();
    }

    /** 所有邀请已响应后开赛；不足 2 人取消。 */
    @Transactional
    public void startIfReady(TournamentRecord tournament) {
        bracket.startIfReady(tournament);
    }

    @EventListener
    @Transactional
    public void onMatchFinished(MatchFinishedEvent event) {
        bracket.onMatchFinished(event);
    }

    /** 积分赛一轮全部结束后：生成/激活下一轮，或取前四进入页面淘汰赛。 */
    @Transactional
    public void advanceLeague(TournamentRecord tournament) {
        bracket.advanceLeague(tournament);
    }

    /** 把已具备双方、仍为 PENDING 的淘汰/页面淘汰对阵激活；并同步当前轮次展示。 */
    @Transactional
    public void activatePendingMatches(TournamentRecord tournament) {
        bracket.activatePendingMatches(tournament);
    }

    public TournamentView view(TournamentRecord tournament) {
        return assembler.view(tournament);
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
}
