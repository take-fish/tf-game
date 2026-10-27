package com.fishmatrix.game.arena;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fishmatrix.game.config.ArenaProperties;
import com.fishmatrix.game.model.GameException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HeatmapEngineTest {

    private HeatmapEngine engine;
    private HeatmapEngine.HeatmapState state;
    private long now;

    @BeforeEach
    void setUp() {
        engine = new HeatmapEngine(new ArenaProperties(
                true, 200, 1, 10, 20, 120, 90));
        now = 1_700_000_000_000L;
        state = engine.create(new ArenaContext(
                ArenaGames.HEATMAP, List.of("u1", "u2"), null,
                now, now + 120_000, 42));
    }

    @Test
    void gridAndInitialResourcesAreSeededDeterministically() {
        assertThat(state.cells).hasSize(96);
        assertThat(state.scores).containsKeys("u1", "u2");
        assertThat(state.energy.get("u1")).isEqualTo(30);
        assertThat(state.cells).allSatisfy(cell -> {
            assertThat(cell.heat).isBetween(1, 3);
            assertThat(cell.hp).isEqualTo(3);
        });
    }

    @Test
    void twoClaimsCaptureCellAndGrantEnergy() {
        engine.apply(state, action("CLAIM", 0), "u1", now + 100);
        assertThat(state.cells.get(0).owner).isNull();
        assertThat(state.cells.get(0).progress.get("u1")).isEqualTo(50);

        engine.apply(state, action("CLAIM", 0), "u1", now + 200);
        assertThat(state.cells.get(0).owner).isEqualTo("u1");
        assertThat(state.energy.get("u1")).isEqualTo(32);
    }

    @Test
    void threeRaidsFlipEnemyCell() {
        state.cells.get(0).owner = "u1";
        state.cells.get(0).hp = 3;

        for (int i = 0; i < 3; i++) {
            engine.apply(state, action("RAID", 0), "u2", now + 100 + i);
        }

        assertThat(state.cells.get(0).owner).isEqualTo("u2");
        assertThat(state.cells.get(0).hp).isEqualTo(3);
    }

    @Test
    void skillRespectsCooldown() {
        engine.apply(state, new ArenaAction("SKILL", null, "sweep", 11, null),
                "u1", now + 100);
        assertThatThrownBy(() -> engine.apply(
                state, new ArenaAction("SKILL", null, "sweep", 11, null),
                "u1", now + 200))
                .isInstanceOf(GameException.class)
                .hasMessage("技能仍在冷却");
    }

    @Test
    void incomeAccruesEverySecondAndRoundFinishesOnTime() {
        state.cells.get(0).owner = "u1";

        engine.tick(state, now + 3_500);
        assertThat(state.scores.get("u1")).isGreaterThan(0);

        engine.tick(state, now + 120_000);
        assertThat(state.finished).isTrue();
        assertThat(state.finishedAt).isEqualTo(now + 120_000);
    }

    @Test
    void outsiderCannotAct() {
        assertThatThrownBy(() -> engine.apply(state, action("CLAIM", 0), "u3", now + 100))
                .isInstanceOf(GameException.class)
                .hasMessage("只有参赛选手可以操作本局");
    }

    private static ArenaAction action(String type, int cell) {
        return new ArenaAction(type, null, null, cell, null);
    }
}
