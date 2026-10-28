package com.fishmatrix.game.controller;

import com.fishmatrix.common.auth.UserContext;
import com.fishmatrix.game.model.ChatMessageView;
import com.fishmatrix.game.model.ChooseGameRequest;
import com.fishmatrix.game.model.GameIdentity;
import com.fishmatrix.game.model.MatchView;
import com.fishmatrix.game.model.SendChatRequest;
import com.fishmatrix.game.service.GameChatService;
import com.fishmatrix.game.service.MatchService;
import com.fishmatrix.game.service.TournamentService;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/game/matches")
public class MatchController {

    private final MatchService matches;
    private final GameChatService chats;
    private final TournamentService tournaments;

    @GetMapping("/active")
    public Map<String, Object> active() {
        GameIdentity user = GameIdentity.of(UserContext.require());
        return Map.of("matches", matches.activeForUser(user.userId()));
    }

    @GetMapping("/{id}")
    public MatchView detail(@PathVariable String id) {
        GameIdentity user = GameIdentity.of(UserContext.require());
        return matches.detailForUser(user, id);
    }

    @GetMapping("/{id}/chat")
    public Map<String, Object> history(
            @PathVariable String id,
            @RequestParam(defaultValue = "PUBLIC") String channel,
            @RequestParam(defaultValue = "50") int limit) {
        GameIdentity user = GameIdentity.of(UserContext.require());
        MatchView match = matches.detailForUser(user, id);
        List<ChatMessageView> messages = chats.history(
                user, match.tournamentId(), id, channel, limit);
        return Map.of("messages", messages);
    }

    @PostMapping("/{id}/chat")
    public ChatMessageView send(@PathVariable String id, @RequestBody SendChatRequest request) {
        GameIdentity user = GameIdentity.of(UserContext.require());
        MatchView match = matches.detailForUser(user, id);
        return chats.send(user, new SendChatRequest(
                match.tournamentId(), id, request.channel(), request.toUserId(), request.content()));
    }

    /** 页面淘汰赛高顺位选手改选本场首局剧本。 */
    @PostMapping("/{id}/game")
    public MatchView chooseGame(@PathVariable String id, @RequestBody ChooseGameRequest request) {
        GameIdentity user = GameIdentity.of(UserContext.require());
        return tournaments.chooseGame(user, id, request == null ? null : request.gameId());
    }
}
