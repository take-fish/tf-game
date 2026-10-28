package com.fishmatrix.game.model;

import com.fishmatrix.common.auth.CurrentUser;
import java.util.List;
import java.util.Set;

/**
 * 比赛域身份。userId 统一映射为 u{accountId}，与聊天、前端 identity 保持一致。
 *
 * <p>角色与权限来自用户中台的 {@code /auth/me} 快照：{@code role} 用于「玩家 / 管理员」分流，
 * {@code permissions} 用于管理接口的细粒度校验（如 {@code game.leaderboard.manage}）。</p>
 */
public record GameIdentity(
        long accountId,
        String userId,
        String nickname,
        String role,
        List<String> roles,
        Set<String> permissions) {

    public static final String ROLE_ADMIN = "ADMIN";

    public GameIdentity {
        roles = roles == null ? List.of() : List.copyOf(roles);
        permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
    }

    /** 兼容只关心角色的调用方（测试与 WebSocket 用例）。 */
    public GameIdentity(long accountId, String userId, String nickname, String role) {
        this(accountId, userId, nickname, role, role == null ? List.of() : List.of(role), Set.of());
    }

    public static GameIdentity of(CurrentUser user) {
        String nickname = user.nickname() == null || user.nickname().isBlank()
                ? user.username()
                : user.nickname();
        return new GameIdentity(user.id(), user.userId(), nickname, user.role(),
                user.roles(), user.permissions());
    }

    /** 管理员身份：role 或 roles 里带 ADMIN 即为管理员。 */
    public boolean isAdmin() {
        if (ROLE_ADMIN.equalsIgnoreCase(role)) {
            return true;
        }
        return roles.stream().anyMatch(ROLE_ADMIN::equalsIgnoreCase);
    }

    /** 管理能力判定：管理员天然拥有全部管理权限，普通用户看权限快照。 */
    public boolean hasPermission(String permission) {
        return isAdmin() || (permission != null && permissions.contains(permission));
    }
}
