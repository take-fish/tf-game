package com.fishmatrix.game.controller;

import com.fishmatrix.common.auth.UserContext;
import com.fishmatrix.game.model.ChatMessageView;
import com.fishmatrix.game.model.GameIdentity;
import com.fishmatrix.game.model.MatchView;
import com.fishmatrix.game.model.SendChatRequest;
import com.fishmatrix.game.service.GameChatService;
import com.fishmatrix.game.service.MatchService;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/game/matches")
public class MatchController {

    private final MatchService matches;
    private final GameChatService chats;

    public MatchController(MatchService matches, GameChatService chats) {
        this.matches = matches;
        this.chats = chats;
    }

    @GetMapping("/active")
    public Map<String, Object> active() {
        GameIdentity user = GameIdentity.of(UserContext.require());
        return Map.of("matches", matches.activeForUser(user.userId()));
    }

    @GetMapping("/{id}")
    public MatchView detail(@PathVariable String id) {
        return matches.detail(id);
    }

    @GetMapping("/{id}/chat")
    public Map<String, Object> history(
            @PathVariable String id,
            @RequestParam(defaultValue = "PUBLIC") String channel,
            @RequestParam(defaultValue = "50") int limit) {
        GameIdentity user = GameIdentity.of(UserContext.require());
        MatchView match = matches.detail(id);
        List<ChatMessageView> messages = chats.history(
                user, match.tournamentId(), id, channel, limit);
        return Map.of("messages", messages);
    }

    @PostMapping("/{id}/chat")
    public ChatMessageView send(@PathVariable String id, @RequestBody SendChatRequest request) {
        GameIdentity user = GameIdentity.of(UserContext.require());
        MatchView match = matches.detail(id);
        return chats.send(user, new SendChatRequest(
                match.tournamentId(), id, request.channel(), request.toUserId(), request.content()));
    }
}
