package com.fishmatrix.game.converter;

import com.fishmatrix.game.domain.NotificationType;
import com.fishmatrix.game.entity.GameNotificationEntity;
import com.fishmatrix.game.model.NotificationView;
import java.util.ArrayList;
import java.util.List;

/** 全站通知实体与对外视图的转换。 */
public final class NotificationConverters {

    private NotificationConverters() {
    }

    public static GameNotificationEntity toEntity(NotificationView notification) {
        if (notification == null) {
            return null;
        }
        GameNotificationEntity entity = new GameNotificationEntity();
        entity.setId(notification.id());
        entity.setType(notification.type() == null
                ? null : NotificationType.valueOf(notification.type()));
        entity.setTournamentId(notification.tournamentId());
        entity.setMatchId(notification.matchId());
        entity.setTitle(notification.title());
        entity.setMessage(notification.message());
        entity.setCreatedAt(SqlTime.toDateTime(notification.createdAt()));
        entity.setExpiresAt(SqlTime.toDateTime(notification.expiresAt()));
        return entity;
    }

    public static NotificationView toView(GameNotificationEntity entity) {
        if (entity == null) {
            return null;
        }
        return new NotificationView(
                entity.getId(),
                entity.getType() == null ? null : entity.getType().name(),
                entity.getTournamentId(),
                entity.getMatchId(),
                entity.getTitle(),
                entity.getMessage(),
                SqlTime.toMillis(entity.getCreatedAt()),
                SqlTime.toMillis(entity.getExpiresAt()));
    }

    public static List<NotificationView> views(List<GameNotificationEntity> entities) {
        List<NotificationView> views = new ArrayList<>(entities.size());
        for (GameNotificationEntity entity : entities) {
            views.add(toView(entity));
        }
        return views;
    }
}
