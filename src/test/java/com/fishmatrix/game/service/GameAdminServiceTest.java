package com.fishmatrix.game.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fishmatrix.common.error.ApiException;
import com.fishmatrix.game.entity.GamePlayerStatEntity;
import com.fishmatrix.game.mapper.MatchMapper;
import com.fishmatrix.game.mapper.PlayerStatMapper;
import com.fishmatrix.game.model.DisplayOrderRequest;
import com.fishmatrix.game.model.GameIdentity;
import com.fishmatrix.game.model.LeaderboardEditRequest;
import com.fishmatrix.game.model.RecordManageRequest;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

/**
 * 管理台守卫与参数校验：玩家不能碰管理接口，管理员可以；非法输入在落库前就被拒绝。
 */
@ExtendWith(MockitoExtension.class)
class GameAdminServiceTest {

    private static final String LEADERBOARD = "game.leaderboard.manage";
    private static final String RECORD = "game.record.manage";

    private static final GameIdentity ADMIN = new GameIdentity(1L, "u1", "管理员", "ADMIN");
    private static final GameIdentity PLAYER = new GameIdentity(2L, "u2", "玩家", "USER");

    @Mock
    private PlayerStatMapper stats;

    @Mock
    private MatchMapper matches;

    @Mock
    private GameRecordService records;

    @InjectMocks
    private GameAdminService admin;

    @Test
    void playerCannotReadLeaderboard() {
        assertThatThrownBy(() -> admin.leaderboard(PLAYER, null, 10))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("没有执行这个操作的权限");
        verify(stats, never()).selectAllOverall(anyInt());
    }

    @Test
    void playerCannotReadRecords() {
        assertThatThrownBy(() -> admin.records(PLAYER, 10))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("没有执行这个操作的权限");
        verify(records, never()).listForAdmin(anyInt());
    }

    @Test
    void leaderboardPermissionDoesNotCoverRecords() {
        GameIdentity operator = new GameIdentity(3L, "u3", "运营", "USER",
                List.of("USER"), java.util.Set.of(LEADERBOARD));

        assertThatThrownBy(() -> admin.records(operator, 10))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("没有执行这个操作的权限");
    }

    @Test
    void adminSeesHiddenRows() {
        GamePlayerStatEntity row = row("u9", 12, false, 4);
        when(stats.selectAllOverall(50)).thenReturn(List.of(row));

        var entries = admin.leaderboard(ADMIN, null, 50);

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).visible()).isFalse();
        assertThat(entries.get(0).displayOrder()).isEqualTo(4);
        assertThat(entries.get(0).points()).isEqualTo(12);
    }

    @Test
    void negativeNumbersAreRejected() {
        ApiException error = catchThrowableOfType(() -> admin.updateEntry(ADMIN, null, "u9",
                new LeaderboardEditRequest(null, null, null, -1, null, null, null, null, null)),
                ApiException.class);

        assertThat(error).hasMessageContaining("不能为负数");
        assertThat(error.code()).isEqualTo("GAME_INVALID_ARGUMENT");
        verify(stats, never()).updateManaged(anyString(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), anyString(), any());
    }

    @Test
    void emptyEditIsRejected() {
        assertThatThrownBy(() -> admin.updateEntry(ADMIN, null, "u9",
                new LeaderboardEditRequest(null, null, null, null, null, null, null, null, null)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("没有需要修改的字段");
    }

    @Test
    void unknownGameIsRejected() {
        assertThatThrownBy(() -> admin.leaderboard(ADMIN, "not-a-game", 10))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("未知的游戏类型");
    }

    @Test
    void updateReturnsRefreshedRow() {
        when(stats.updateManaged(eq("u9"), isNull(), isNull(), isNull(), eq(30), isNull(), isNull(),
                isNull(), isNull(), eq(8), eq("u1"), any(LocalDateTime.class))).thenReturn(1);
        when(stats.selectAllOverall(200)).thenReturn(List.of(row("u9", 30, true, 8)));

        var entry = admin.updateEntry(ADMIN, null, "u9",
                new LeaderboardEditRequest(null, null, null, 30, null, null, null, null, 8));

        assertThat(entry.userId()).isEqualTo("u9");
        assertThat(entry.points()).isEqualTo(30);
        assertThat(entry.displayOrder()).isEqualTo(8);
        assertThat(entry.rank()).isEqualTo(1L);
    }

    @Test
    void updateMissingRowIsNotFound() {
        when(stats.updateManaged(anyString(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), anyString(), any(LocalDateTime.class))).thenReturn(0);

        ApiException error = catchThrowableOfType(() -> admin.updateEntry(ADMIN, null, "missing",
                new LeaderboardEditRequest(null, null, null, 1, null, null, null, null, null)),
                ApiException.class);

        assertThat(error).hasMessageContaining("排行榜条目不存在");
        assertThat(error.status()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void reorderRejectsDuplicateRows() {
        when(stats.updateManaged(anyString(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), anyString(), any(LocalDateTime.class))).thenReturn(1);
        var request = new DisplayOrderRequest(List.of(
                new DisplayOrderRequest.Item("u9", 2),
                new DisplayOrderRequest.Item("u9", 1)));

        assertThatThrownBy(() -> admin.reorderLeaderboard(ADMIN, null, request))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("出现了多次");
    }

    @Test
    void reorderWritesOrderForEveryItem() {
        when(stats.updateManaged(anyString(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), anyString(), any(LocalDateTime.class))).thenReturn(1);
        when(stats.selectAllOverall(200)).thenReturn(List.of());

        admin.reorderLeaderboard(ADMIN, null, new DisplayOrderRequest(List.of(
                new DisplayOrderRequest.Item("u9", 2),
                new DisplayOrderRequest.Item("u7", 1))));

        verify(stats).updateManaged(eq("u9"), isNull(), isNull(), isNull(), isNull(), isNull(),
                isNull(), isNull(), isNull(), eq(2), eq("u1"), any(LocalDateTime.class));
        verify(stats).updateManaged(eq("u7"), isNull(), isNull(), isNull(), isNull(), isNull(),
                isNull(), isNull(), isNull(), eq(1), eq("u1"), any(LocalDateTime.class));
    }

    @Test
    void recordUpdateRejectsEmptyBody() {
        assertThatThrownBy(() -> admin.updateRecord(ADMIN, "m1", new RecordManageRequest(null, null)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("没有需要修改的字段");
    }

    @Test
    void recordUpdateMissingRowIsNotFound() {
        when(matches.updateRecordManaged(anyString(), any(), any(), anyString(),
                any(LocalDateTime.class))).thenReturn(0);

        assertThatThrownBy(() -> admin.updateRecord(ADMIN, "m1", new RecordManageRequest(true, null)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("比赛记录不存在");
    }

    private static GamePlayerStatEntity row(String userId, int points, boolean visible, int order) {
        GamePlayerStatEntity entity = new GamePlayerStatEntity();
        entity.setUserId(userId);
        entity.setNickname("选手" + userId);
        entity.setMatches(3);
        entity.setWins(2);
        entity.setLosses(1);
        entity.setPoints(points);
        entity.setChampionships(1);
        entity.setRunnerUps(0);
        entity.setBestScore(100L);
        entity.setVisible(visible);
        entity.setDisplayOrder(order);
        return entity;
    }
}
