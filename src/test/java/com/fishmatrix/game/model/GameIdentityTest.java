package com.fishmatrix.game.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.fishmatrix.common.auth.CurrentUser;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 玩家 / 管理员分流：role 与 roles 里出现 ADMIN 即为管理员，
 * 普通用户只能拿到权限快照里显式授予的管理权限。
 */
class GameIdentityTest {

    @Test
    void adminIsRecognisedFromRole() {
        GameIdentity admin = new GameIdentity(1L, "u1", "管理员", "ADMIN");

        assertThat(admin.isAdmin()).isTrue();
        assertThat(admin.hasPermission(GamePermissionsHolder.LEADERBOARD)).isTrue();
        assertThat(admin.hasPermission(GamePermissionsHolder.RECORD)).isTrue();
    }

    @Test
    void adminIsAlsoRecognisedFromRolesList() {
        GameIdentity admin = new GameIdentity(1L, "u1", "管理员", "USER",
                List.of("USER", "admin"), Set.of());

        assertThat(admin.isAdmin()).isTrue();
    }

    @Test
    void playerNeedsExplicitPermission() {
        GameIdentity player = new GameIdentity(2L, "u2", "玩家", "USER");

        assertThat(player.isAdmin()).isFalse();
        assertThat(player.hasPermission(GamePermissionsHolder.LEADERBOARD)).isFalse();

        GameIdentity operator = new GameIdentity(3L, "u3", "运营", "USER",
                List.of("USER"), Set.of(GamePermissionsHolder.RECORD));

        assertThat(operator.isAdmin()).isFalse();
        assertThat(operator.hasPermission(GamePermissionsHolder.RECORD)).isTrue();
        assertThat(operator.hasPermission(GamePermissionsHolder.LEADERBOARD)).isFalse();
    }

    @Test
    void ofCopiesUserSnapshot() {
        CurrentUser user = new CurrentUser(9L, "alice", "爱丽丝", "USER",
                List.of("USER"), Set.of(GamePermissionsHolder.RECORD));

        GameIdentity identity = GameIdentity.of(user);

        assertThat(identity.accountId()).isEqualTo(9L);
        assertThat(identity.userId()).isEqualTo("u9");
        assertThat(identity.nickname()).isEqualTo("爱丽丝");
        assertThat(identity.role()).isEqualTo("USER");
        assertThat(identity.hasPermission(GamePermissionsHolder.RECORD)).isTrue();
        assertThat(identity.hasPermission(GamePermissionsHolder.LEADERBOARD)).isFalse();
    }

    @Test
    void blankNicknameFallsBackToUsername() {
        CurrentUser user = new CurrentUser(10L, "bob", "  ", "USER", List.of("USER"), Set.of());

        assertThat(GameIdentity.of(user).nickname()).isEqualTo("bob");
    }

    /** 测试内引用权限码，避免测试与实现常量漂移。 */
    private static final class GamePermissionsHolder {
        private static final String LEADERBOARD = "game.leaderboard.manage";
        private static final String RECORD = "game.record.manage";
    }
}
