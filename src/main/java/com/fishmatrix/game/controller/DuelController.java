package com.fishmatrix.game.controller;

import com.fishmatrix.common.auth.UserContext;
import com.fishmatrix.game.model.CreateDuelRequest;
import com.fishmatrix.game.model.GameIdentity;
import com.fishmatrix.game.model.TournamentView;
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

/**
 * 一对一约战入口。约战只对双方可见，不进入演练大厅，也没有聊天与观战。
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/game/duels")
public class DuelController {

    private final TournamentService duels;

    @GetMapping
    public Map<String, Object> list(@RequestParam(defaultValue = "20") int limit) {
        GameIdentity user = GameIdentity.of(UserContext.require());
        List<TournamentView> entries = duels.listDuelsForUser(user.userId(), limit);
        return Map.of("duels", entries);
    }

    @GetMapping("/{id}")
    public TournamentView get(@PathVariable String id) {
        GameIdentity user = GameIdentity.of(UserContext.require());
        return duels.getDuel(user, id);
    }

    @PostMapping
    public TournamentView create(@RequestBody CreateDuelRequest request) {
        GameIdentity user = GameIdentity.of(UserContext.require());
        return duels.createDuel(user, request);
    }

    @PostMapping("/{id}/accept")
    public TournamentView accept(@PathVariable String id) {
        GameIdentity user = GameIdentity.of(UserContext.require());
        return duels.acceptDuel(user, id);
    }

    @PostMapping("/{id}/decline")
    public TournamentView decline(@PathVariable String id) {
        GameIdentity user = GameIdentity.of(UserContext.require());
        return duels.declineDuel(user, id);
    }

    @PostMapping("/{id}/cancel")
    public TournamentView cancel(@PathVariable String id) {
        GameIdentity user = GameIdentity.of(UserContext.require());
        return duels.cancelDuel(user, id);
    }
}
