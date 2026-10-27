package com.fishmatrix.game.arena;

import com.fishmatrix.game.config.ArenaProperties;
import com.fishmatrix.game.model.GameException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import org.springframework.stereotype.Component;

/**
 * 日志热点排查竞赛：双方在同一张热度网格上认领、复核、固化，并按占地持续得分。
 */
@Component
public class HeatmapEngine implements ArenaEngine<HeatmapEngine.HeatmapState> {

    private static final int ROWS = 8;
    private static final int COLS = 12;
    private static final int CELLS = ROWS * COLS;
    private static final double INITIAL_ENERGY = 30;
    private static final double MAX_ENERGY = 100;
    private static final int MAX_HP = 6;

    private final ArenaProperties properties;

    public HeatmapEngine(ArenaProperties properties) {
        this.properties = properties;
    }

    @Override
    public String gameId() {
        return ArenaGames.HEATMAP;
    }

    @Override
    public Class<HeatmapState> stateType() {
        return HeatmapState.class;
    }

    @Override
    public long roundMillis() {
        return properties.heatmapRoundSeconds() * 1000L;
    }

    @Override
    public HeatmapState create(ArenaContext context) {
        HeatmapState state = new HeatmapState();
        state.gameId = ArenaGames.HEATMAP;
        state.players = new ArrayList<>(context.players());
        state.startedAt = context.startedAt();
        state.endsAt = context.endsAt();
        state.serverTime = context.startedAt();
        state.seed = context.seed();
        state.rows = ROWS;
        state.cols = COLS;
        state.lastIncomeAt = context.startedAt();
        state.lastHeatShiftAt = context.startedAt();

        SplittableRandom random = new SplittableRandom(context.seed());
        double centerX = (COLS - 1) / 2.0;
        double centerY = (ROWS - 1) / 2.0;
        double maxDistance = Math.sqrt(centerX * centerX + centerY * centerY);
        for (int row = 0; row < ROWS; row++) {
            for (int col = 0; col < COLS; col++) {
                double dx = col - centerX;
                double dy = row - centerY;
                double center = 1 - Math.min(1, Math.sqrt(dx * dx + dy * dy) / maxDistance);
                int heat = 1 + (int) Math.round(center * 1.6 + random.nextDouble() * 0.8);
                Cell cell = new Cell();
                cell.index = row * COLS + col;
                cell.row = row;
                cell.col = col;
                cell.heat = Math.max(1, Math.min(3, heat));
                cell.hp = 3;
                state.cells.add(cell);
            }
        }
        for (String player : state.players) {
            state.scores.put(player, 0.0);
            state.energy.put(player, INITIAL_ENERGY);
        }
        return state;
    }

    @Override
    public void apply(HeatmapState state, ArenaAction action, String userId, long now) {
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
            case "CLAIM" -> claim(state, userId, action.cell(), now);
            case "RAID" -> raid(state, userId, action.cell(), now);
            case "FORTIFY" -> fortify(state, userId, action.cell(), now);
            case "SKILL" -> skill(state, userId, action, now);
            default -> throw GameException.badRequest(
                    "GAME_ARENA_ACTION_INVALID", "未知的竞技场动作");
        }
    }

    private void claim(HeatmapState state, String userId, Integer index, long now) {
        Cell cell = cell(state, index);
        if (cell.owner != null) {
            throw GameException.conflict("GAME_ARENA_INVALID_TARGET", "只能认领未归属的格子");
        }
        int progress = cell.progress.getOrDefault(userId, 0) + 50;
        if (progress >= 100) {
            cell.owner = userId;
            cell.hp = 3;
            cell.progress.clear();
            addEnergy(state, userId, 2);
            state.addEvent(now, "CLAIM", userId, "标记排查完成 #" + cell.index);
        } else {
            cell.progress.put(userId, progress);
        }
    }

    private void raid(HeatmapState state, String userId, Integer index, long now) {
        Cell cell = cell(state, index);
        if (cell.owner == null) {
            throw GameException.conflict("GAME_ARENA_INVALID_TARGET", "中立格不能复核");
        }
        if (userId.equals(cell.owner)) {
            throw GameException.conflict("GAME_ARENA_INVALID_TARGET", "不能复核自己的排查结果");
        }
        cell.hp -= 1;
        if (cell.hp <= 0) {
            String previous = cell.owner;
            cell.owner = userId;
            cell.hp = 3;
            cell.progress.clear();
            addEnergy(state, userId, 2);
            state.addEvent(now, "RAID", userId,
                    "复核推翻 " + previous + " 的排查结果 #" + cell.index);
        }
    }

    private void fortify(HeatmapState state, String userId, Integer index, long now) {
        Cell cell = cell(state, index);
        if (!userId.equals(cell.owner)) {
            throw GameException.conflict("GAME_ARENA_INVALID_TARGET", "只能固化自己的格子");
        }
        spendEnergy(state, userId, 8);
        cell.hp = Math.min(MAX_HP, cell.hp + 2);
        state.serverTime = now;
    }

    private void skill(HeatmapState state, String userId, ArenaAction action, long now) {
        String skill = action.skill() == null ? "" : action.skill().strip().toLowerCase();
        switch (skill) {
            case "sweep" -> {
                checkCooldown(state, userId, "sweep", now, 20_000);
                spendEnergy(state, userId, 30);
                for (Cell cell : area(state, action.cell())) {
                    if (cell.owner == null) {
                        int progress = cell.progress.getOrDefault(userId, 0) + 50;
                        if (progress >= 100) {
                            cell.owner = userId;
                            cell.hp = 3;
                            cell.progress.clear();
                        } else {
                            cell.progress.put(userId, progress);
                        }
                    } else if (userId.equals(cell.owner)) {
                        cell.hp = Math.min(MAX_HP, cell.hp + 1);
                    } else {
                        cell.hp -= 1;
                        if (cell.hp <= 0) {
                            cell.owner = userId;
                            cell.hp = 3;
                        }
                    }
                }
                state.addEvent(now, "SKILL", userId, "执行批量巡检 3×3");
            }
            case "reinforce" -> {
                List<Cell> area = area(state, action.cell());
                if (area.stream().noneMatch(cell -> userId.equals(cell.owner))) {
                    throw GameException.conflict(
                            "GAME_ARENA_INVALID_TARGET", "范围内没有可固化的己方格子");
                }
                checkCooldown(state, userId, "reinforce", now, 20_000);
                spendEnergy(state, userId, 25);
                for (Cell cell : area) {
                    if (userId.equals(cell.owner)) {
                        cell.hp = Math.min(MAX_HP, cell.hp + 2);
                    }
                }
                state.addEvent(now, "SKILL", userId, "执行基线加固 3×3");
            }
            case "burst" -> {
                Cell cell = cell(state, action.cell());
                if (cell.owner == null || userId.equals(cell.owner)) {
                    throw GameException.conflict(
                            "GAME_ARENA_INVALID_TARGET", "定向深挖只能选择对方格子");
                }
                checkCooldown(state, userId, "burst", now, 30_000);
                spendEnergy(state, userId, 40);
                cell.owner = userId;
                cell.hp = 3;
                cell.progress.clear();
                state.addEvent(now, "SKILL", userId, "执行定向深挖 #" + cell.index);
            }
            default -> throw GameException.badRequest(
                    "GAME_ARENA_ACTION_INVALID", "未知的排查技能");
        }
    }

    @Override
    public void tick(HeatmapState state, long now) {
        if (state.finished) {
            return;
        }
        state.serverTime = now;
        int guard = 0;
        while (now - state.lastIncomeAt >= 1000 && guard++ < 600) {
            state.lastIncomeAt += 1000;
            for (String player : state.players) {
                state.scores.merge(player, income(state, player), Double::sum);
                addEnergy(state, player, 1);
            }
        }
        guard = 0;
        while (now - state.lastHeatShiftAt >= 30_000 && guard++ < 20) {
            state.lastHeatShiftAt += 30_000;
            shiftHeat(state, now);
        }
        if (now >= state.endsAt) {
            String winner = scoreLeader(state);
            state.finish(now, winner, winner == null ? "排查积分持平" : "排查积分领先");
        }
    }

    private void shiftHeat(HeatmapState state, long now) {
        SplittableRandom random = new SplittableRandom(state.seed * 31 + state.heatShiftIndex);
        state.heatShiftIndex += 1;
        int first = random.nextInt(CELLS);
        int second = random.nextInt(CELLS);
        int third = random.nextInt(CELLS);
        Cell a = state.cells.get(first);
        Cell b = state.cells.get(second);
        Cell c = state.cells.get(third);
        int heatA = a.heat;
        a.heat = b.heat;
        b.heat = c.heat;
        c.heat = heatA;
        if (a.heat != b.heat || b.heat != c.heat) {
            state.addEvent(now, "HEAT_SHIFT", null,
                    "日志热点漂移 #" + first + " / #" + second + " / #" + third);
        }
    }

    private double income(HeatmapState state, String player) {
        double total = 0;
        for (Cell cell : state.cells) {
            if (!player.equals(cell.owner)) {
                continue;
            }
            int adjacent = 0;
            if (cell.col > 0 && player.equals(ownerAt(state, cell.row, cell.col - 1))) {
                adjacent++;
            }
            if (cell.col < COLS - 1 && player.equals(ownerAt(state, cell.row, cell.col + 1))) {
                adjacent++;
            }
            if (cell.row > 0 && player.equals(ownerAt(state, cell.row - 1, cell.col))) {
                adjacent++;
            }
            if (cell.row < ROWS - 1 && player.equals(ownerAt(state, cell.row + 1, cell.col))) {
                adjacent++;
            }
            total += cell.heat * Math.min(1.5, 1 + adjacent * 0.1);
        }
        return total;
    }

    @Override
    public boolean finished(HeatmapState state) {
        return state.finished;
    }

    @Override
    public String winnerUserId(HeatmapState state) {
        return state.winnerUserId;
    }

    @Override
    public String finishReason(HeatmapState state) {
        return state.finishReason;
    }

    private String scoreLeader(HeatmapState state) {
        String leader = null;
        double best = -1;
        int bestCells = -1;
        boolean tied = false;
        for (String player : state.players) {
            double score = state.scores.getOrDefault(player, 0.0);
            int cells = ownedCells(state, player);
            if (score > best + 1e-6 || (Math.abs(score - best) <= 1e-6 && cells > bestCells)) {
                leader = player;
                best = score;
                bestCells = cells;
                tied = false;
            } else if (Math.abs(score - best) <= 1e-6 && cells == bestCells) {
                tied = true;
            }
        }
        return tied ? null : leader;
    }

    private int ownedCells(HeatmapState state, String player) {
        int count = 0;
        for (Cell cell : state.cells) {
            if (player.equals(cell.owner)) {
                count++;
            }
        }
        return count;
    }

    private String ownerAt(HeatmapState state, int row, int col) {
        return state.cells.get(row * COLS + col).owner;
    }

    private List<Cell> area(HeatmapState state, Integer index) {
        Cell center = cell(state, index);
        List<Cell> cells = new ArrayList<>();
        for (int row = Math.max(0, center.row - 1);
                row <= Math.min(ROWS - 1, center.row + 1); row++) {
            for (int col = Math.max(0, center.col - 1);
                    col <= Math.min(COLS - 1, center.col + 1); col++) {
                cells.add(state.cells.get(row * COLS + col));
            }
        }
        return cells;
    }

    private static Cell cell(HeatmapState state, Integer index) {
        if (index == null || index < 0 || index >= CELLS) {
            throw GameException.badRequest("GAME_ARENA_INVALID_TARGET", "格子坐标无效");
        }
        return state.cells.get(index);
    }

    private static void addEnergy(HeatmapState state, String userId, double amount) {
        state.energy.merge(userId, amount, (left, right) -> Math.min(MAX_ENERGY, left + right));
    }

    private static void spendEnergy(HeatmapState state, String userId, double amount) {
        double energy = state.energy.getOrDefault(userId, 0.0);
        if (energy + 1e-6 < amount) {
            throw GameException.conflict("GAME_ARENA_ENERGY", "排查配额不足");
        }
        state.energy.put(userId, energy - amount);
    }

    private static void checkCooldown(HeatmapState state, String userId,
                                      String skill, long now, long cooldownMillis) {
        String key = userId + "|" + skill;
        Long readyAt = state.cooldowns.get(key);
        if (readyAt != null && readyAt > now) {
            throw GameException.conflict("GAME_ARENA_COOLDOWN", "技能仍在冷却");
        }
        state.cooldowns.put(key, now + cooldownMillis);
    }

    public static class HeatmapState extends ArenaState {
        public int rows;
        public int cols;
        public long lastIncomeAt;
        public long lastHeatShiftAt;
        public int heatShiftIndex;
        public List<Cell> cells = new ArrayList<>();
        public Map<String, Double> scores = new LinkedHashMap<>();
        public Map<String, Double> energy = new LinkedHashMap<>();
        public Map<String, Long> cooldowns = new LinkedHashMap<>();
    }

    public static class Cell {
        public int index;
        public int row;
        public int col;
        public int heat;
        public int hp;
        public String owner;
        public Map<String, Integer> progress = new LinkedHashMap<>();
    }
}
