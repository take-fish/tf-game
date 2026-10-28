package com.fishmatrix.game.converter;

import com.fishmatrix.game.domain.ChatChannel;
import com.fishmatrix.game.entity.GameChatMessageEntity;
import com.fishmatrix.game.model.ChatMessageView;
import java.util.ArrayList;
import java.util.List;

/** 聊天消息实体与对外视图的转换。 */
public final class ChatConverters {

    private ChatConverters() {
    }

    public static GameChatMessageEntity toEntity(ChatMessageView message) {
        if (message == null) {
            return null;
        }
        GameChatMessageEntity entity = new GameChatMessageEntity();
        entity.setId(message.id());
        entity.setTournamentId(message.tournamentId());
        entity.setMatchId(message.matchId());
        entity.setChannel(
                message.channel() == null ? null : ChatChannel.valueOf(message.channel()));
        entity.setFromUserId(message.fromUserId());
        entity.setFromNickname(message.fromNickname());
        entity.setToUserId(message.toUserId());
        entity.setContent(message.content());
        entity.setCreatedAt(SqlTime.toDateTime(message.createdAt()));
        return entity;
    }

    public static ChatMessageView toView(GameChatMessageEntity entity) {
        if (entity == null) {
            return null;
        }
        return new ChatMessageView(
                entity.getId(),
                entity.getTournamentId(),
                entity.getMatchId(),
                entity.getChannel() == null ? null : entity.getChannel().name(),
                entity.getFromUserId(),
                entity.getFromNickname(),
                entity.getToUserId(),
                entity.getContent(),
                SqlTime.toMillis(entity.getCreatedAt()));
    }

    public static List<ChatMessageView> views(List<GameChatMessageEntity> entities) {
        List<ChatMessageView> views = new ArrayList<>(entities.size());
        for (GameChatMessageEntity entity : entities) {
            views.add(toView(entity));
        }
        return views;
    }
}
