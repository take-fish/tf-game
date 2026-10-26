package com.fishmatrix.game.ws;

import com.fishmatrix.game.model.GameIdentity;
import org.springframework.web.socket.WebSocketSession;

public class GameSession {

    private final WebSocketSession socket;
    private final GameIdentity identity;
    private volatile String watchingMatchId;

    public GameSession(WebSocketSession socket, GameIdentity identity) {
        this.socket = socket;
        this.identity = identity;
    }

    public WebSocketSession getSocket() {
        return socket;
    }

    public GameIdentity getIdentity() {
        return identity;
    }

    public String getUserId() {
        return identity.userId();
    }

    public String getNickname() {
        return identity.nickname();
    }

    public String getWatchingMatchId() {
        return watchingMatchId;
    }

    public void setWatchingMatchId(String watchingMatchId) {
        this.watchingMatchId = watchingMatchId;
    }
}
