package com.fishmatrix.game.model;

import com.fishmatrix.common.error.ApiException;
import org.springframework.http.HttpStatus;

/**
 * 比赛业务异常，统一由 tf-common 的全局异常处理器输出。
 */
public class GameException extends ApiException {

    public GameException(HttpStatus status, String code, String message) {
        super(status, code, message);
    }

    public static GameException badRequest(String code, String message) {
        return new GameException(HttpStatus.BAD_REQUEST, code, message);
    }

    public static GameException forbidden(String message) {
        return new GameException(HttpStatus.FORBIDDEN, "GAME_FORBIDDEN", message);
    }

    public static GameException notFound(String message) {
        return new GameException(HttpStatus.NOT_FOUND, "GAME_NOT_FOUND", message);
    }

    public static GameException conflict(String code, String message) {
        return new GameException(HttpStatus.CONFLICT, code, message);
    }

    public static GameException unavailable(String message) {
        return new GameException(HttpStatus.SERVICE_UNAVAILABLE, "GAME_UNAVAILABLE", message);
    }
}
