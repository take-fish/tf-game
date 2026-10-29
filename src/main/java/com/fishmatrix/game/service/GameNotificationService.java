package com.fishmatrix.game.service;

import com.fishmatrix.game.config.GameProperties;
import com.fishmatrix.game.converter.NotificationConverters;
import com.fishmatrix.game.converter.SqlTime;
import com.fishmatrix.game.mapper.GameNotificationMapper;
import com.fishmatrix.game.model.MatchRecord;
import com.fishmatrix.game.model.NotificationView;
import com.fishmatrix.game.model.TournamentRecord;
import com.fishmatrix.game.ws.GameSessionRegistry;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 全站通知：
 * - FINAL_START：最终决赛双方就绪时弹窗，点击跳转观战。
 * - CHAMPION：赛事结束后的顶部横幅。
 * 通知同时落库（轮询兜底）并通过 WebSocket 广播。
 */
@RequiredArgsConstructor
@Service
public class GameNotificationService {

    public static final String TYPE_FINAL_START = "FINAL_START";
    public static final String TYPE_CHAMPION = "CHAMPION";

    private final GameNotificationMapper notifications;
    private final GameSessionRegistry registry;
    private final GameProperties properties;

    public void publishFinalStart(TournamentRecord tournament, MatchRecord match) {
        if (match == null || match.player1UserId() == null || match.player2UserId() == null) {
            return;
        }
        if (notifications.exists(TYPE_FINAL_START, tournament.id(), match.id())) {
            return;
        }
        String name = tournamentName(tournament);
        NotificationView notification = build(
                TYPE_FINAL_START, tournament.id(), match.id(),
                "The final begins - " + name + "！",
                name + " 决赛开始：" + match.player1Nickname() + " vs "
                        + match.player2Nickname() + "，点击进入观战");
        saveAndBroadcast(notification);
    }

    public void publishChampion(TournamentRecord tournament) {
        if (tournament.championUserId() == null) {
            return;
        }
        if (notifications.exists(TYPE_CHAMPION, tournament.id(), null)) {
            return;
        }
        String nickname = tournament.championNickname() == null
                ? tournament.championUserId() : tournament.championNickname();
        String name = tournamentName(tournament);
        NotificationView notification = build(
                TYPE_CHAMPION, tournament.id(), null,
                "The champion of " + name + " is " + nickname + "！！！",
                name + " 冠军：" + nickname);
        saveAndBroadcast(notification);
    }

    public List<NotificationView> recent(long after, int limit) {
        long now = System.currentTimeMillis();
        int safeLimit = Math.min(50, Math.max(1, limit));
        if (after <= 0) {
            return NotificationConverters.views(
                    notifications.selectLatest(SqlTime.toDateTime(now), safeLimit));
        }
        return NotificationConverters.views(notifications.selectRecent(
                SqlTime.toDateTime(after), SqlTime.toDateTime(now), safeLimit));
    }

    /** 播报名称取创建赛事时填写的名称；历史数据缺失时才回退到默认名称。 */
    private String tournamentName(TournamentRecord tournament) {
        String name = tournament.name();
        return name == null || name.isBlank() ? "热力五子棋" : name.trim();
    }

    private NotificationView build(String type, String tournamentId, String matchId,
                                   String title, String message) {
        long now = System.currentTimeMillis();
        return new NotificationView(
                UUID.randomUUID().toString(), type, tournamentId, matchId,
                title, message, now, now + properties.notificationTtlSeconds() * 1000L);
    }

    private void saveAndBroadcast(NotificationView notification) {
        notifications.insert(NotificationConverters.toEntity(notification));
        registry.broadcastAll(Map.of(
                "type", "notification",
                "notification", notification));
    }
}
