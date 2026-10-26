package com.fishmatrix.game.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fishmatrix.game.config.GameProperties;
import com.fishmatrix.game.model.MatchRecord;
import com.fishmatrix.game.model.NotificationView;
import com.fishmatrix.game.model.TournamentRecord;
import com.fishmatrix.game.store.NotificationRepository;
import com.fishmatrix.game.ws.GameSessionRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class GameNotificationServiceTest {

    private NotificationRepository repository;
    private GameSessionRegistry registry;
    private GameNotificationService service;

    @BeforeEach
    void setUp() {
        repository = mock(NotificationRepository.class);
        registry = mock(GameSessionRegistry.class);
        service = new GameNotificationService(
                repository, registry,
                new GameProperties(2, 16, 300, 60, 30, 60, 60, 600, 1000, "*"));
    }

    @Test
    void finalStartUsesTournamentName() {
        when(repository.exists(GameNotificationService.TYPE_FINAL_START, "t1", "m1"))
                .thenReturn(false);
        service.publishFinalStart(tournament("周五摸鱼杯", null), finalMatch());
        NotificationView notification = savedNotification();

        assertThat(notification.title()).isEqualTo("The final begins - 周五摸鱼杯！");
        assertThat(notification.message()).contains("周五摸鱼杯");
        verify(registry).broadcastAll(any());
    }

    @Test
    void championUsesTournamentNameAndNickname() {
        when(repository.exists(GameNotificationService.TYPE_CHAMPION, "t1", null))
                .thenReturn(false);
        service.publishChampion(tournament("周五摸鱼杯", "Alice"));
        NotificationView notification = savedNotification();

        assertThat(notification.title())
                .isEqualTo("The champion of 周五摸鱼杯 is Alice！！！");
        assertThat(notification.message()).isEqualTo("周五摸鱼杯 冠军：Alice");
        verify(registry).broadcastAll(any());
    }

    @Test
    void blankTournamentNameFallsBackToDefault() {
        when(repository.exists(GameNotificationService.TYPE_CHAMPION, "t1", null))
                .thenReturn(false);
        service.publishChampion(tournament("  ", "Alice"));

        assertThat(savedNotification().title())
                .isEqualTo("The champion of 热力五子棋 is Alice！！！");
    }

    private NotificationView savedNotification() {
        ArgumentCaptor<NotificationView> captor = ArgumentCaptor.forClass(NotificationView.class);
        verify(repository).insert(captor.capture());
        return captor.getValue();
    }

    private TournamentRecord tournament(String name, String championNickname) {
        return new TournamentRecord(
                "t1", name, "heatmap-gomoku", "BO1", "SINGLE_ELIMINATION",
                "FINISHED", "u1", "Creator", 2, 0L, 0, 0,
                championNickname == null ? null : "u2", championNickname,
                1_000L, 2_000L, 3_000L);
    }

    private MatchRecord finalMatch() {
        return new MatchRecord(
                "m1", "t1", 1, 1, "FINAL", "READY",
                "u1", "Alice", "u2", "Bob", 0, 0,
                null, null, 1_000L, null, null);
    }
}
