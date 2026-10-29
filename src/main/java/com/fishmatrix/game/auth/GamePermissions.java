package com.fishmatrix.game.auth;

import com.fishmatrix.game.model.GameException;
import com.fishmatrix.game.model.GameIdentity;
import java.util.Set;

/**
 * 比赛域管理权限码，与 tf-user 的权限目录保持一致：
 * {@code game.leaderboard.manage}（排行榜治理）、{@code game.record.manage}（比赛记录治理）。
 */
public final class GamePermissions {

    public static final String LEADERBOARD_MANAGE = "game.leaderboard.manage";
    public static final String RECORD_MANAGE = "game.record.manage";

    public static final Set<String> ALL = Set.of(LEADERBOARD_MANAGE, RECORD_MANAGE);

    private GamePermissions() {
    }

    /**
     * 管理接口的守卫：拦截器上的 {@code @RequirePermission} 是入口校验，
     * 业务层再校验一次，避免绕过 Web 层直接调 Service。
     */
    public static void require(GameIdentity user, String permission) {
        if (user == null) {
            throw GameException.forbidden("请先登录");
        }
        if (!user.hasPermission(permission)) {
            throw new GameException(
                    org.springframework.http.HttpStatus.FORBIDDEN,
                    "PERMISSION_DENIED",
                    "没有执行这个操作的权限");
        }
    }
}
