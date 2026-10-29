package com.fishmatrix.game.arena;

import com.fishmatrix.game.config.ArenaProperties;
import com.fishmatrix.game.model.GameException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 混沌工程攻防演练：攻击方注入流量，防守方扩容、切流、限流，核心网关过载 3 秒即失守。
 * 背景流量脚本对双方防守局完全一致，保证换边公平。
 */
@RequiredArgsConstructor
@Component
public class TopologyEngine implements ArenaEngine<TopologyEngine.TopologyState> {

    private static final double INITIAL_RESOURCE = 40;
    private static final double MAX_RESOURCE = 120;
    private static final double RESOURCE_PER_SECOND = 1.2;
    private static final double ROUTE_WEIGHT = 0.22;
    private static final long BREACH_HOLD_MILLIS = 3000;

    private final ArenaProperties properties;

    @Override
    public String gameId() {
        return ArenaGames.TOPOLOGY;
    }

    @Override
    public Class<TopologyState> stateType() {
        return TopologyState.class;
    }

    @Override
    public long roundMillis() {
        return properties.topologyRoundSeconds() * 1000L;
    }

    @Override
    public TopologyState create(ArenaContext context) {
        TopologyState state = new TopologyState();
        state.gameId = ArenaGames.TOPOLOGY;
        state.players = new ArrayList<>(context.players());
        state.startedAt = context.startedAt();
        state.endsAt = context.endsAt();
        state.serverTime = context.startedAt();
        state.seed = context.seed();
        state.attackerUserId = context.attackerUserId();
        state.defenderUserId = context.players().stream()
                .filter(player -> !player.equals(context.attackerUserId()))
                .findFirst().orElse(null);
        state.lastRegenAt = context.startedAt();
        state.lastTickAt = context.startedAt();

        addNode(state, "gateway", "核心网关", "CORE", 100);
        addNode(state, "web", "Web 前端", "SERVICE", 60);
        addNode(state, "user", "用户中台", "SERVICE", 60);
        addNode(state, "game", "演练中心", "SERVICE", 60);
        addNode(state, "chat", "消息中台", "SERVICE", 60);
        addNode(state, "reader", "文档服务", "SERVICE", 60);
        addNode(state, "music", "音频服务", "SERVICE", 60);
        addNode(state, "mysql", "MySQL", "MIDDLEWARE", 90);
        addNode(state, "redis", "Redis", "MIDDLEWARE", 70);
        addNode(state, "nacos", "Nacos", "MIDDLEWARE", 50);

        for (String service : List.of("web", "user", "game", "chat", "reader", "music")) {
            addEdge(state, service + "->gateway", service, "gateway", ROUTE_WEIGHT, "ROUTE");
        }
        addEdge(state, "user->mysql", "user", "mysql", 0.5, "DEPEND");
        addEdge(state, "game->mysql", "game", "mysql", 0.4, "DEPEND");
        addEdge(state, "reader->mysql", "reader", "mysql", 0.35, "DEPEND");
        addEdge(state, "chat->redis", "chat", "redis", 0.4, "DEPEND");
        addEdge(state, "music->redis", "music", "redis", 0.3, "DEPEND");
        addEdge(state, "web->nacos", "web", "nacos", 0.08, "DEPEND");
        addEdge(state, "user->nacos", "user", "nacos", 0.05, "DEPEND");

        for (String player : state.players) {
            state.energy.put(player, INITIAL_RESOURCE);
            state.credits.put(player, INITIAL_RESOURCE);
        }
        return state;
    }

    @Override
    public void apply(TopologyState state, ArenaAction action, String userId, long now) {
        state.serverTime = now;
        if (state.finished) {
            throw GameException.conflict("GAME_ARENA_FINISHED", "本局已经结束");
        }
        if (!state.players.contains(userId)) {
            throw GameException.forbidden("只有参赛选手可以操作本局");
        }
        if (action == null || action.type() == null) {
            throw GameException.badRequest("GAME_ARENA_ACTION_INVALID", "缺少动作类型");
        }
        String type = action.type().strip().toUpperCase();
        switch (type) {
            case "ATTACK" -> attack(state, userId, action, now);
            case "SCALE" -> scale(state, userId, action, now);
            case "SHIFT" -> shift(state, userId, action, now);
            case "THROTTLE" -> throttle(state, userId, action, now);
            default -> throw GameException.badRequest(
                    "GAME_ARENA_ACTION_INVALID", "未知的攻防动作");
        }
    }

    private void attack(TopologyState state, String userId, ArenaAction action, long now) {
        if (!userId.equals(state.attackerUserId)) {
            throw GameException.forbidden("当前身份不能注入故障");
        }
        String skill = action.skill() == null ? "" : action.skill().strip().toLowerCase();
        NodeState target = node(state, action.target());
        if ("traffic".equals(skill)) {
            if ("MIDDLEWARE".equals(target.kind)) {
                throw GameException.conflict(
                        "GAME_ARENA_INVALID_TARGET", "流量注入只能选择网关或服务节点");
            }
            checkCooldown(state, userId, "ATTACK|traffic", now, 4_000);
            spend(state.energy, userId, 15, "故障注入能量不足");
            inject(state, target.id, 35);
            state.addEvent(now, "ATTACK", userId, "流量注入 → " + target.label);
        } else if ("slow".equals(skill)) {
            if (!"SERVICE".equals(target.kind)) {
                throw GameException.conflict(
                        "GAME_ARENA_INVALID_TARGET", "慢调用注入只能选择服务节点");
            }
            checkCooldown(state, userId, "ATTACK|slow", now, 6_000);
            spend(state.energy, userId, 20, "故障注入能量不足");
            inject(state, target.id, 20);
            EdgeState dependency = state.edges.stream()
                    .filter(edge -> "DEPEND".equals(edge.kind) && edge.from.equals(target.id))
                    .findFirst().orElse(null);
            String dependencyLabel = "";
            if (dependency != null) {
                inject(state, dependency.to, 15);
                dependencyLabel = " → " + node(state, dependency.to).label;
            }
            state.addEvent(now, "ATTACK", userId,
                    "慢调用注入 → " + target.label + dependencyLabel);
        } else {
            throw GameException.badRequest("GAME_ARENA_ACTION_INVALID", "未知的故障注入方式");
        }
    }

    private void scale(TopologyState state, String userId, ArenaAction action, long now) {
        requireDefender(state, userId);
        NodeState target = node(state, action.target());
        checkCooldown(state, userId, "SCALE|" + target.id, now, 10_000);
        spend(state.credits, userId, 15, "弹性扩容配额不足");
        state.scaleUntil.put(target.id, now + 15_000);
        state.scaleBonus.put(target.id, 0.4);
        state.addEvent(now, "DEFEND", userId, "弹性扩容 → " + target.label);
    }

    private void shift(TopologyState state, String userId, ArenaAction action, long now) {
        requireDefender(state, userId);
        EdgeState edge = edge(state, action.target());
        if (!"ROUTE".equals(edge.kind)) {
            throw GameException.conflict(
                    "GAME_ARENA_INVALID_TARGET", "只能切换服务到核心网关的流量");
        }
        checkCooldown(state, userId, "SHIFT|" + edge.id, now, 8_000);
        spend(state.credits, userId, 12, "流量调度配额不足");
        state.shiftUntil.put(edge.id, now + 8_000);
        state.addEvent(now, "DEFEND", userId, "流量切换 → " + edge.id);
    }

    private void throttle(TopologyState state, String userId, ArenaAction action, long now) {
        requireDefender(state, userId);
        NodeState target = node(state, action.target());
        if ("MIDDLEWARE".equals(target.kind)) {
            throw GameException.conflict(
                    "GAME_ARENA_INVALID_TARGET", "限流保护只能选择网关或服务节点");
        }
        checkCooldown(state, userId, "THROTTLE|" + target.id, now, 15_000);
        spend(state.credits, userId, 25, "限流保护配额不足");
        state.throttleUntil.put(target.id, now + 4_000);
        state.addEvent(now, "DEFEND", userId, "限流保护 → " + target.label);
    }

    @Override
    public void tick(TopologyState state, long now) {
        if (state.finished) {
            return;
        }
        state.serverTime = now;
        long dt = Math.max(0, Math.min(2000, now - state.lastTickAt));
        state.lastTickAt = now;
        int guard = 0;
        while (now - state.lastRegenAt >= 1000 && guard++ < 600) {
            state.lastRegenAt += 1000;
            for (String player : state.players) {
                state.energy.merge(player, RESOURCE_PER_SECOND,
                        (left, right) -> Math.min(MAX_RESOURCE, left + right));
                state.credits.merge(player, RESOURCE_PER_SECOND,
                        (left, right) -> Math.min(MAX_RESOURCE, left + right));
            }
        }
        double decay = Math.pow(0.8, dt / 1000.0);
        state.injected.replaceAll((id, value) -> value * decay);
        state.injected.values().removeIf(value -> value < 0.05);

        recalculateLoads(state, now);
        NodeState gateway = node(state, "gateway");
        if (gateway.load + 1e-6 >= gateway.capacity) {
            if (state.overloadedSince == 0) {
                state.overloadedSince = now;
                state.addEvent(now, "ALERT", null, "核心网关负载超过 100%");
            } else if (now - state.overloadedSince >= BREACH_HOLD_MILLIS) {
                state.finish(now, state.attackerUserId, "核心网关过载");
                state.addEvent(now, "RESULT", state.attackerUserId, "核心网关不可用");
            }
        } else {
            state.overloadedSince = 0;
        }
        if (!state.finished && now >= state.endsAt) {
            state.finish(now, state.defenderUserId, "稳态保障成功");
            state.addEvent(now, "RESULT", state.defenderUserId, "撑满演练时长");
        }
    }

    private void recalculateLoads(TopologyState state, long now) {
        double elapsed = Math.max(0, (now - state.startedAt) / 1000.0);
        double serviceFraction = 0.25 + step(elapsed, 30) * 0.15 + step(elapsed, 60) * 0.15;
        double middlewareFraction = 0.20 + step(elapsed, 30) * 0.12 + step(elapsed, 60) * 0.13;

        Map<String, NodeState> nodes = new LinkedHashMap<>();
        for (NodeState node : state.nodes) {
            nodes.put(node.id, node);
            node.capacity = node.baseCapacity
                    * (1 + (until(state.scaleUntil, node.id, now) ? 0.4 : 0));
        }
        for (NodeState node : state.nodes) {
            double fraction = "MIDDLEWARE".equals(node.kind)
                    ? middlewareFraction : serviceFraction;
            node.load = node.capacity * fraction + state.injected.getOrDefault(node.id, 0.0);
        }

        for (EdgeState edge : state.edges) {
            if ("DEPEND".equals(edge.kind)) {
                NodeState from = nodes.get(edge.from);
                NodeState to = nodes.get(edge.to);
                if (to.load < to.capacity) {
                    to.load += from.load * edge.weight;
                }
            }
        }

        for (NodeState node : state.nodes) {
            double pressure = 0;
            for (EdgeState edge : state.edges) {
                if ("DEPEND".equals(edge.kind) && edge.from.equals(node.id)) {
                    NodeState dependency = nodes.get(edge.to);
                    if (dependency.load > dependency.capacity) {
                        pressure += (dependency.load - dependency.capacity) * edge.weight;
                    }
                }
            }
            node.load += pressure;
            if (until(state.throttleUntil, node.id, now)) {
                node.load *= 0.5;
            }
        }

        NodeState gateway = nodes.get("gateway");
        double gatewayLoad = gateway.capacity * serviceFraction
                + state.injected.getOrDefault("gateway", 0.0);
        for (EdgeState edge : state.edges) {
            if ("ROUTE".equals(edge.kind)) {
                NodeState service = nodes.get(edge.from);
                double factor = until(state.shiftUntil, edge.id, now) ? 0.65 : 1.0;
                gatewayLoad += service.load * edge.weight * factor;
            }
        }
        for (NodeState node : state.nodes) {
            if (!"CORE".equals(node.kind) && !"MIDDLEWARE".equals(node.kind)
                    && node.load > node.capacity) {
                gatewayLoad += (node.load - node.capacity) * 0.4;
            }
        }
        if (until(state.throttleUntil, "gateway", now)) {
            gatewayLoad *= 0.5;
        }
        gateway.load = gatewayLoad;

        for (NodeState node : state.nodes) {
            node.utilization = node.capacity <= 0 ? 0 : node.load / node.capacity;
            node.status = node.utilization >= 0.95 ? "RED"
                    : node.utilization >= 0.7 ? "YELLOW" : "GREEN";
        }
        for (EdgeState edge : state.edges) {
            if ("ROUTE".equals(edge.kind)) {
                edge.shifted = until(state.shiftUntil, edge.id, now);
            }
        }
    }

    private static double step(double elapsedSeconds, int atSeconds) {
        return elapsedSeconds >= atSeconds ? 1 : 0;
    }

    private static boolean until(Map<String, Long> map, String key, long now) {
        Long value = map.get(key);
        return value != null && value > now;
    }

    @Override
    public boolean finished(TopologyState state) {
        return state.finished;
    }

    @Override
    public String winnerUserId(TopologyState state) {
        return state.winnerUserId;
    }

    @Override
    public String finishReason(TopologyState state) {
        return state.finishReason;
    }

    private static void requireDefender(TopologyState state, String userId) {
        if (!userId.equals(state.defenderUserId)) {
            throw GameException.forbidden("当前身份不能执行稳态保障操作");
        }
    }

    private static NodeState node(TopologyState state, String id) {
        if (id == null || id.isBlank()) {
            throw GameException.badRequest("GAME_ARENA_INVALID_TARGET", "请选择节点");
        }
        return state.nodes.stream()
                .filter(node -> node.id.equals(id))
                .findFirst()
                .orElseThrow(() -> GameException.badRequest(
                        "GAME_ARENA_INVALID_TARGET", "节点不存在"));
    }

    private static EdgeState edge(TopologyState state, String id) {
        if (id == null || id.isBlank()) {
            throw GameException.badRequest("GAME_ARENA_INVALID_TARGET", "请选择连线");
        }
        return state.edges.stream()
                .filter(edge -> edge.id.equals(id))
                .findFirst()
                .orElseThrow(() -> GameException.badRequest(
                        "GAME_ARENA_INVALID_TARGET", "连线不存在"));
    }

    private static void inject(TopologyState state, String nodeId, double amount) {
        state.injected.merge(nodeId, amount, Double::sum);
    }

    private static void spend(Map<String, Double> resource, String userId,
                              double amount, String message) {
        double value = resource.getOrDefault(userId, 0.0);
        if (value + 1e-6 < amount) {
            throw GameException.conflict("GAME_ARENA_ENERGY", message);
        }
        resource.put(userId, value - amount);
    }

    private static void checkCooldown(TopologyState state, String userId,
                                      String key, long now, long cooldownMillis) {
        String cooldownKey = userId + "|" + key;
        Long readyAt = state.cooldowns.get(cooldownKey);
        if (readyAt != null && readyAt > now) {
            throw GameException.conflict("GAME_ARENA_COOLDOWN", "动作仍在冷却");
        }
        state.cooldowns.put(cooldownKey, now + cooldownMillis);
    }

    private static void addNode(TopologyState state, String id, String label,
                                 String kind, double capacity) {
        NodeState node = new NodeState();
        node.id = id;
        node.label = label;
        node.kind = kind;
        node.baseCapacity = capacity;
        node.capacity = capacity;
        node.load = capacity * 0.25;
        node.utilization = 0.25;
        node.status = "GREEN";
        state.nodes.add(node);
    }

    private static void addEdge(TopologyState state, String id, String from,
                                String to, double weight, String kind) {
        EdgeState edge = new EdgeState();
        edge.id = id;
        edge.from = from;
        edge.to = to;
        edge.weight = weight;
        edge.kind = kind;
        state.edges.add(edge);
    }

    public static class TopologyState extends ArenaState {
        public String attackerUserId;
        public String defenderUserId;
        public List<NodeState> nodes = new ArrayList<>();
        public List<EdgeState> edges = new ArrayList<>();
        public Map<String, Double> energy = new LinkedHashMap<>();
        public Map<String, Double> credits = new LinkedHashMap<>();
        public Map<String, Long> cooldowns = new LinkedHashMap<>();
        public Map<String, Double> injected = new LinkedHashMap<>();
        public Map<String, Double> scaleBonus = new LinkedHashMap<>();
        public Map<String, Long> scaleUntil = new LinkedHashMap<>();
        public Map<String, Long> throttleUntil = new LinkedHashMap<>();
        public Map<String, Long> shiftUntil = new LinkedHashMap<>();
        public long lastRegenAt;
        public long lastTickAt;
        public long overloadedSince;
    }

    public static class NodeState {
        public String id;
        public String label;
        public String kind;
        public double baseCapacity;
        public double capacity;
        public double load;
        public double utilization;
        public String status;
    }

    public static class EdgeState {
        public String id;
        public String from;
        public String to;
        public double weight;
        public String kind;
        public boolean shifted;
    }
}
