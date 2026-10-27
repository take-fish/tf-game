package com.fishmatrix.game.arena;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fishmatrix.game.config.ArenaProperties;
import com.fishmatrix.game.model.GameException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TopologyEngineTest {

    private TopologyEngine engine;
    private TopologyEngine.TopologyState state;
    private long now;

    @BeforeEach
    void setUp() {
        engine = new TopologyEngine(new ArenaProperties(
                true, 200, 1, 10, 20, 120, 90));
        now = 1_700_000_000_000L;
        state = engine.create(new ArenaContext(
                ArenaGames.TOPOLOGY, List.of("u1", "u2"), "u1",
                now, now + 90_000, 7));
    }

    @Test
    void topologyHasGatewayServicesAndDependencies() {
        assertThat(state.nodes).hasSize(10);
        assertThat(state.edges).hasSize(13);
        assertThat(state.attackerUserId).isEqualTo("u1");
        assertThat(state.defenderUserId).isEqualTo("u2");
    }

    @Test
    void onlyAttackerCanInjectAndOnlyDefenderCanDefend() {
        assertThatThrownBy(() -> engine.apply(
                state, new ArenaAction("ATTACK", "user", "traffic", null, null),
                "u2", now + 100))
                .isInstanceOf(GameException.class)
                .hasMessage("当前身份不能注入故障");

        assertThatThrownBy(() -> engine.apply(
                state, new ArenaAction("SCALE", "user", null, null, null),
                "u1", now + 100))
                .isInstanceOf(GameException.class)
                .hasMessage("当前身份不能执行稳态保障操作");
    }

    @Test
    void trafficInjectionRaisesTargetLoad() {
        double before = node("user").load;
        engine.apply(state, new ArenaAction("ATTACK", "user", "traffic", null, null),
                "u1", now + 100);
        assertThat(state.energy.get("u1")).isEqualTo(25);

        engine.tick(state, now + 300);
        assertThat(node("user").load).isGreaterThan(before);
    }

    @Test
    void defenderScalingRaisesCapacity() {
        engine.apply(state, new ArenaAction("SCALE", "gateway", null, null, null),
                "u2", now + 100);
        engine.tick(state, now + 300);

        assertThat(node("gateway").capacity).isEqualTo(140);
        assertThat(state.credits.get("u2")).isEqualTo(25);
    }

    @Test
    void sustainedGatewayOverloadWinsForAttacker() {
        state.injected.put("gateway", 500.0);
        engine.tick(state, now + 100);
        engine.tick(state, now + 4_000);

        assertThat(state.finished).isTrue();
        assertThat(state.winnerUserId).isEqualTo("u1");
        assertThat(state.finishReason).isEqualTo("核心网关过载");
    }

    @Test
    void defenderSurvivesWhenGatewayHoldsUntilTimeUp() {
        engine.tick(state, now + 90_000);

        assertThat(state.finished).isTrue();
        assertThat(state.winnerUserId).isEqualTo("u2");
        assertThat(state.finishReason).isEqualTo("稳态保障成功");
    }

    @Test
    void slowCallMustTargetService() {
        assertThatThrownBy(() -> engine.apply(
                state, new ArenaAction("ATTACK", "gateway", "slow", null, null),
                "u1", now + 100))
                .isInstanceOf(GameException.class)
                .hasMessage("慢调用注入只能选择服务节点");
    }

    @Test
    void cooldownRejectionDoesNotSpendResources() {
        engine.apply(state, new ArenaAction("ATTACK", "user", "traffic", null, null),
                "u1", now + 100);
        assertThatThrownBy(() -> engine.apply(
                state, new ArenaAction("ATTACK", "user", "traffic", null, null),
                "u1", now + 200))
                .isInstanceOf(GameException.class)
                .hasMessage("动作仍在冷却");
        assertThat(state.energy.get("u1")).isEqualTo(25);

        engine.apply(state, new ArenaAction("SCALE", "gateway", null, null, null),
                "u2", now + 100);
        assertThatThrownBy(() -> engine.apply(
                state, new ArenaAction("SCALE", "gateway", null, null, null),
                "u2", now + 200))
                .isInstanceOf(GameException.class)
                .hasMessage("动作仍在冷却");
        assertThat(state.credits.get("u2")).isEqualTo(25);
    }

    private TopologyEngine.NodeState node(String id) {
        return state.nodes.stream().filter(item -> item.id.equals(id)).findFirst().orElseThrow();
    }
}
