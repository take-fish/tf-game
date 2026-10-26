package com.fishmatrix.game.service;

import com.fishmatrix.game.config.GameProperties;
import com.fishmatrix.game.model.MatchRecord;
import com.fishmatrix.game.model.NotificationView;
import com.fishmatrix.game.model.TournamentRecord;
import com.fishmatrix.game.store.NotificationRepository;
import com.fishmatrix.game.ws.GameSessionRegistry;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 全站通知：
 * - FINAL_START：最终决赛双方就绪时弹窗，点击跳转观战。
 * - CHAMPION：赛事结束后的顶部横幅。
 * 通知同时落库（轮询兜底）并通过 WebSocket 广播。
 */
@Service
public class GameNotificationService {

    public static final String TYPE_FINAL_START = "FINAL_START";
    public static final String TYPE_CHAMPION = "CHAMPION";

    private final NotificationRepository repository;
    private final GameSessionRegistry registry;
    private final GameProperties properties;

    public GameNotificationService(
            NotificationRepository repository,
            GameSessionRegistry registry,
            GameProperties properties) {
        this.repository = repository;
        this.registry = registry;
        this.properties = properties;
    }

    public void publishFinalStart(TournamentRecord tournament, MatchRecord match) {
        if (match == null || match.player1UserId() == null || match.player2UserId() == null) {
            return;
        }
        if (repository.exists(TYPE_FINAL_START, tournament.id(), match.id())) {
            return;
        }
        NotificationView notification = build(
                TYPE_FINAL_START, tournament.id(), match.id(),
                "The final begins - wuziqi！",
                "决赛开始：" + match.player1Nickname() + " vs " + match.player2Nickname() + "，点击进入观战");
        saveAndBroadcast(notification);
    }

    public void publishChampion(TournamentRecord tournament) {
        if (tournament.championUserId() == null) {
            return;
        }
        if (repository.exists(TYPE_CHAMPION, tournament.id(), null)) {
            return;
        }
        String nickname = tournament.championNickname() == null
                ? tournament.championUserId() : tournament.championNickname();
        NotificationView notification = build(
                TYPE_CHAMPION, tournament.id(), null,
                "The champion is " + nickname + "！！！",
                "热力五子棋冠军：" + nickname);
        saveAndBroadcast(notification);
    }

    public List<NotificationView> recent(long after, int limit) {
        long now = System.currentTimeMillis();
        if (after <= 0) {
            return repository.latest(now, limit);
        }
        return repository.recent(after, now, limit);
    }

    private NotificationView build(String type, String tournamentId, String matchId,
                                   String title, String message) {
        long now = System.currentTimeMillis();
        return new NotificationView(
                UUID.randomUUID().toString(), type, tournamentId, matchId,
                title, message, now, now + properties.notificationTtlSeconds() * 1000L);
    }

    private void saveAndBroadcast(NotificationView notification) {
        repository.insert(notification);
        registry.broadcastAll(Map.of(
                "type", "notification",
                "notification", notification));
    }
}
