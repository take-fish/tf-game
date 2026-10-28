package com.fishmatrix.game.controller;

import com.fishmatrix.common.auth.UserContext;
import com.fishmatrix.game.model.CreateTournamentRequest;
import com.fishmatrix.game.model.GameIdentity;
import com.fishmatrix.game.model.TournamentView;
import com.fishmatrix.game.service.TournamentService;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/game/tournaments")
public class TournamentController {

    private final TournamentService tournaments;

    @GetMapping
    public Map<String, Object> list(@RequestParam(defaultValue = "20") int limit) {
        List<TournamentView> entries = tournaments.list(limit);
        return Map.of("tournaments", entries);
    }

    @GetMapping("/invitations")
    public Map<String, Object> invitations() {
        GameIdentity user = GameIdentity.of(UserContext.require());
        return Map.of("tournaments", tournaments.invitations(user.userId()));
    }

    @GetMapping("/{id}")
    public TournamentView get(@PathVariable String id) {
        return tournaments.getTournament(id);
    }

    @PostMapping
    public TournamentView create(@RequestBody CreateTournamentRequest request) {
        GameIdentity user = GameIdentity.of(UserContext.require());
        return tournaments.create(user, request);
    }

    @PostMapping("/{id}/accept")
    public TournamentView accept(@PathVariable String id) {
        GameIdentity user = GameIdentity.of(UserContext.require());
        return tournaments.accept(user, id);
    }

    @PostMapping("/{id}/decline")
    public TournamentView decline(@PathVariable String id) {
        GameIdentity user = GameIdentity.of(UserContext.require());
        return tournaments.decline(user, id);
    }

    @PostMapping("/{id}/cancel")
    public TournamentView cancel(@PathVariable String id) {
        GameIdentity user = GameIdentity.of(UserContext.require());
        return tournaments.cancel(user, id);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable String id) {
        GameIdentity user = GameIdentity.of(UserContext.require());
        tournaments.delete(user, id);
        return Map.of("id", id, "deleted", true);
    }
}
