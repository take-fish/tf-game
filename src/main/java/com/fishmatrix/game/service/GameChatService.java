package com.fishmatrix.game.service;

import com.fishmatrix.game.config.GameProperties;
import com.fishmatrix.game.model.ChatMessageView;
import com.fishmatrix.game.model.GameException;
import com.fishmatrix.game.model.GameIdentity;
import com.fishmatrix.game.model.MatchRecord;
import com.fishmatrix.game.model.SendChatRequest;
import com.fishmatrix.game.model.TournamentRecord;
import com.fishmatrix.game.store.ChatRepository;
import com.fishmatrix.game.store.MatchRepository;
import com.fishmatrix.game.store.TournamentRepository;
import com.fishmatrix.game.ws.GameSessionRegistry;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 比赛聊天：
 * - PUBLIC：公开房间，所有连接观战页的用户可发言、可回看。
 * - PLAYER_DM：仅比赛双方可见，落库后赛后仍可回看。
 */
@RequiredArgsConstructor
@Service
public class GameChatService {

    public static final String CHANNEL_PUBLIC = "PUBLIC";
    public static final String CHANNEL_PLAYER_DM = "PLAYER_DM";

    private final ChatRepository chatRepository;
    private final TournamentRepository tournaments;
    private final MatchRepository matches;
    private final GameSessionRegistry registry;
    private final GameProperties properties;

    public ChatMessageView send(GameIdentity user, SendChatRequest request) {
        if (request == null) {
            throw GameException.badRequest("GAME_CHAT_INVALID", "聊天请求不能为空");
        }
        String channel = normalizeChannel(request.channel());
        String content = sanitize(request.content());
        if (content.isEmpty()) {
            throw GameException.badRequest("GAME_CHAT_EMPTY", "消息内容不能为空");
        }
        if (content.codePointCount(0, content.length()) > properties.maxChatLength()) {
            throw GameException.badRequest("GAME_CHAT_TOO_LONG",
                    "消息长度不能超过 " + properties.maxChatLength() + " 个字符");
        }
        TournamentRecord tournament = requireTournament(request.tournamentId());
        rejectDuel(tournament);
        if ("FINISHED".equals(tournament.status()) || "CANCELLED".equals(tournament.status())) {
            throw GameException.conflict("GAME_CHAT_CLOSED", "赛事已结束，聊天仅供回看");
        }

        String matchId = blankToNull(request.matchId());
        String toUserId = blankToNull(request.toUserId());
        if (CHANNEL_PLAYER_DM.equals(channel)) {
            if (matchId == null) {
                throw GameException.badRequest("GAME_CHAT_INVALID", "选手私聊必须指定比赛");
            }
            MatchRecord match = requireMatch(matchId);
            requireParticipant(match, user.userId());
            String peer = requireOpponent(match, user.userId());
            if (toUserId != null && !toUserId.equals(peer)) {
                throw GameException.forbidden("只能与当前对手私聊");
            }
            toUserId = peer;
        } else {
            toUserId = null;
            if (matchId != null) {
                requireMatch(matchId);
            }
        }

        ChatMessageView message = new ChatMessageView(
                UUID.randomUUID().toString(), tournament.id(), matchId, channel,
                user.userId(), user.nickname(), toUserId, content,
                System.currentTimeMillis());
        chatRepository.insert(message);

        Map<String, Object> frame = Map.of("type", "game_chat", "message", message);
        if (CHANNEL_PLAYER_DM.equals(channel)) {
            registry.broadcastUsers(Set.of(message.fromUserId(), message.toUserId()), frame);
        } else if (matchId != null) {
            registry.broadcastMatch(matchId, participantIds(requireMatch(matchId)), frame);
        } else {
            registry.broadcastAll(frame);
        }
        return message;
    }

    public List<ChatMessageView> history(GameIdentity user, String tournamentId, String matchId,
                                         String channel, int limit) {
        TournamentRecord tournament = requireTournament(tournamentId);
        rejectDuel(tournament);
        String normalized = normalizeChannel(channel);
        String normalizedMatchId = blankToNull(matchId);
        if (CHANNEL_PLAYER_DM.equals(normalized)) {
            if (normalizedMatchId == null) {
                throw GameException.badRequest("GAME_CHAT_INVALID", "选手私聊必须指定比赛");
            }
            MatchRecord match = requireMatch(normalizedMatchId);
            requireParticipant(match, user.userId());
            String peer = requireOpponent(match, user.userId());
            return chatRepository.history(
                    tournament.id(), match.id(), CHANNEL_PLAYER_DM,
                    user.userId(), peer, limit);
        }
        if (normalizedMatchId != null) {
            requireMatch(normalizedMatchId);
        }
        return chatRepository.history(
                tournament.id(), normalizedMatchId, CHANNEL_PUBLIC, null, null, limit);
    }

    private TournamentRecord requireTournament(String id) {
        if (id == null || id.isBlank()) {
            throw GameException.badRequest("GAME_CHAT_INVALID", "缺少赛事 ID");
        }
        return tournaments.findById(id)
                .orElseThrow(() -> GameException.notFound("赛事不存在"));
    }

    /** 一对一约战不提供聊天，历史与发送一并拒绝。 */
    private static void rejectDuel(TournamentRecord tournament) {
        if (TournamentService.KIND_DUEL.equals(tournament.kind())) {
            throw GameException.forbidden("约战不提供聊天");
        }
    }

    private MatchRecord requireMatch(String id) {
        return matches.findById(id)
                .orElseThrow(() -> GameException.notFound("比赛不存在"));
    }

    private static void requireParticipant(MatchRecord match, String userId) {
        if (!userId.equals(match.player1UserId()) && !userId.equals(match.player2UserId())) {
            throw GameException.forbidden("仅参赛选手可进入选手频道");
        }
    }

    private static String otherPlayer(MatchRecord match, String userId) {
        return userId.equals(match.player1UserId()) ? match.player2UserId() : match.player1UserId();
    }

    /** 阶梯决赛的最终场在挑战者产生前只有一名选手，此时不允许进入选手私聊。 */
    private static String requireOpponent(MatchRecord match, String userId) {
        String peer = otherPlayer(match, userId);
        if (peer == null) {
            throw GameException.conflict("GAME_OPPONENT_PENDING", "对手尚未产生，暂时不能私聊");
        }
        return peer;
    }

    private static Set<String> participantIds(MatchRecord match) {
        Set<String> ids = new java.util.LinkedHashSet<>();
        if (match.player1UserId() != null) {
            ids.add(match.player1UserId());
        }
        if (match.player2UserId() != null) {
            ids.add(match.player2UserId());
        }
        return ids;
    }

    private static String normalizeChannel(String channel) {
        if (channel == null || channel.isBlank() || CHANNEL_PUBLIC.equalsIgnoreCase(channel)) {
            return CHANNEL_PUBLIC;
        }
        if (CHANNEL_PLAYER_DM.equalsIgnoreCase(channel)) {
            return CHANNEL_PLAYER_DM;
        }
        throw GameException.badRequest("GAME_CHAT_INVALID", "未知聊天频道");
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** 去除终端控制字符/ANSI 转义，保留换行与制表符。 */
    static String sanitize(String content) {
        if (content == null) {
            return "";
        }
        return content.replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", "").strip();
    }
}
