package com.fishmatrix.game.controller;

import com.fishmatrix.common.auth.UserContext;
import com.fishmatrix.game.auth.WsTicketService;
import com.fishmatrix.game.model.GameIdentity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/game")
public class WsTicketController {

    private final WsTicketService tickets;

    public WsTicketController(WsTicketService tickets) {
        this.tickets = tickets;
    }

    @PostMapping("/ws-ticket")
    public WsTicketService.Ticket issue() {
        return tickets.issue(GameIdentity.of(UserContext.require()));
    }
}
