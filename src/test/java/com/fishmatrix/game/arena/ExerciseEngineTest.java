package com.fishmatrix.game.arena;

import static org.assertj.core.api.Assertions.assertThat;

import com.fishmatrix.game.arena.ExerciseEngine.ExercisePlayer;
import com.fishmatrix.game.arena.ExerciseEngine.ExerciseState;
import com.fishmatrix.game.arena.ExerciseEngine.PlayerSeed;
import com.fishmatrix.game.config.ExerciseProperties;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExerciseEngineTest {

    private final ExerciseEngine engine = new ExerciseEngine(new ExerciseProperties(
            true, 40, 80, 1, 180, 50, 4, 30, 380));

    @Test
    void movementIsServerAuthoritativeAndStopsWhenInputGoesStale() {
        ExerciseState state = engine.create(
                "r1", ExerciseEngine.MODE_FFA, List.of(seed("u1")), 1_000, 181_000, 1);
        ExercisePlayer actor = state.actors.get(0);

        engine.input(state, "u1", input(-1.0, 0.0, null, false), 1_100);
        engine.tick(state, 1_300);
        assertThat(actor.x).isEqualTo(76.0);
        assertThat(actor.y).isEqualTo(120.0);

        engine.tick(state, 1_500);
        assertThat(actor.x).isEqualTo(32.0);
        assertThat(actor.y).isEqualTo(120.0);

        // 输入超过 500ms 未刷新，服务端停止移动，客户端无法自行位移。
        engine.tick(state, 1_700);
        assertThat(actor.x).isEqualTo(32.0);
        assertThat(actor.y).isEqualTo(120.0);
    }

    @Test
    void hitsKillAndAssistAwardScores() {
        ExerciseState state = engine.create(
                "r2", ExerciseEngine.MODE_FFA,
                List.of(seed("u1"), seed("u2"), seed("u3")), 1_000, 181_000, 2);
        ExercisePlayer shooter = state.actors.get(0);
        ExercisePlayer target = state.actors.get(1);
        shooter.x = 300;
        shooter.y = 360;
        shooter.aim = 0;
        target.x = 400;
        target.y = 360;
        target.hp = 68;
        target.damageAt.put("u3", 2_250L);
        state.actors.get(2).x = 540;
        state.actors.get(2).y = 360;

        engine.input(state, "u1", input(0.0, 0.0, null, true), 2_000);
        engine.input(state, "u1", input(0.0, 0.0, null, true), 2_300);

        assertThat(shooter.hits).isEqualTo(2);
        assertThat(shooter.kills).isEqualTo(1);
        assertThat(shooter.score).isEqualTo(7);
        assertThat(target.alive).isFalse();
        assertThat(target.deaths).isEqualTo(1);
        ExercisePlayer helper = state.actors.get(2);
        assertThat(helper.assists).isEqualTo(1);
        assertThat(helper.score).isEqualTo(2);
    }

    @Test
    void teamModeDecidesWinnerByTeamScoreAtTimeout() {
        ExerciseState state = engine.create(
                "r3", ExerciseEngine.MODE_TEAM,
                List.of(new PlayerSeed("u1", "甲", "A", false),
                        new PlayerSeed("u2", "乙", "B", false)),
                1_000, 5_000, 3);
        state.actors.get(0).score = 7;
        state.actors.get(1).score = 3;

        engine.tick(state, 5_000);

        assertThat(state.finished).isTrue();
        assertThat(state.winnerTeam).isEqualTo("A");
        assertThat(state.winnerUserId).isEqualTo("u1");
        assertThat(state.finishReason).contains("7 : 3");
    }

    @Test
    void pickupAndCapturedPointAwardSupportScores() {
        ExerciseState state = engine.create(
                "r4", ExerciseEngine.MODE_FFA, List.of(seed("u1")), 1_000, 181_000, 4);
        ExercisePlayer actor = state.actors.get(0);
        actor.x = 640;
        actor.y = 140;

        engine.tick(state, 4_000);
        assertThat(actor.score).isEqualTo(2);

        engine.tick(state, 7_000);
        assertThat(actor.score).isEqualTo(3);
        assertThat(state.points.get(0).ownerUserId).isEqualTo("u1");

        engine.tick(state, 10_000);
        assertThat(actor.score).isEqualTo(4);
    }

    @Test
    void scanRevealsDistantActorsOnlyForTwoSeconds() {
        ExerciseState state = engine.create(
                "r5", ExerciseEngine.MODE_FFA,
                List.of(seed("u1"), seed("u2")), 1_000, 181_000, 5);
        ExercisePlayer viewer = state.actors.get(0);
        viewer.x = 200;
        viewer.y = 360;
        ExercisePlayer other = state.actors.get(1);
        other.x = 900;
        other.y = 360;

        assertThat(engine.visibleActors(state, "u1", 2_000)).hasSize(1);

        engine.input(state, "u1", input(0.0, 0.0, "scan", false), 2_000);
        assertThat(engine.visibleActors(state, "u1", 2_000)).hasSize(2);
        assertThat(engine.visibleActors(state, "u1", 4_001)).hasSize(1);
    }

    @Test
    void trainingEndsWithHumanWinnerAndBotsDoNotScore() {
        ExerciseState state = engine.create(
                "r6", ExerciseEngine.MODE_TRAINING,
                List.of(seed("u1"),
                        new PlayerSeed("bot-1", "训练靶 1", null, true),
                        new PlayerSeed("bot-2", "训练靶 2", null, true),
                        new PlayerSeed("bot-3", "训练靶 3", null, true)),
                1_000, 5_000, 6);
        assertThat(state.actors).hasSize(4);
        assertThat(state.actors.stream().filter(actor -> actor.bot).count()).isEqualTo(3);

        engine.tick(state, 5_000);

        assertThat(state.finished).isTrue();
        assertThat(state.winnerUserId).isEqualTo("u1");
        assertThat(state.winnerTeam).isNull();
    }

    private static PlayerSeed seed(String userId) {
        return new PlayerSeed(userId, "选手" + userId, null, false);
    }

    private static ExerciseInput input(double moveX, double moveY, String skill, boolean fire) {
        return new ExerciseInput(1, moveX, moveY, 0.0, fire, skill, null, null);
    }
}
