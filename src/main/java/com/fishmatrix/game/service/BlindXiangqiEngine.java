package com.fishmatrix.game.service;

import com.fishmatrix.game.model.BlindColor;
import com.fishmatrix.game.model.BlindLayout;
import com.fishmatrix.game.model.BlindPieceType;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 盲棋（暗棋象棋）规则引擎：
 * - 9×10 中国象棋棋盘，32 枚棋开局反面朝上；
 * - 每步可翻开一枚反面棋（回合结束），或移动一枚已翻开的己方棋；
 * - 已翻开的棋可以盲吃任意反面棋（不校验颜色，吃到己方也照常损失）；
 * - 反面棋占格并阻挡车、马、炮及将帅视线，同时可以作为炮架；
 * - 吃掉对方将/帅立即获胜；双方将帅都已翻开且同列无子相隔则判和；
 * - 不设“将军必须应将”，允许对攻与送将；
 * - 将/帅、士/仕、象/相不设九宫与过河限制，按棋型在任意位置移动（象仍需塞象眼）。
 *
 * <p>服务端是唯一裁判：反面棋的真实颜色与兵种只存在于服务端状态中。</p>
 */
public final class BlindXiangqiEngine {

    public static final int WIDTH = 9;
    public static final int HEIGHT = 10;

    private static final SecureRandom RANDOM = new SecureRandom();

    private static final List<BlindPieceType> ARMY = List.of(
            BlindPieceType.GENERAL,
            BlindPieceType.ADVISOR, BlindPieceType.ADVISOR,
            BlindPieceType.ELEPHANT, BlindPieceType.ELEPHANT,
            BlindPieceType.HORSE, BlindPieceType.HORSE,
            BlindPieceType.CHARIOT, BlindPieceType.CHARIOT,
            BlindPieceType.CANNON, BlindPieceType.CANNON,
            BlindPieceType.SOLDIER, BlindPieceType.SOLDIER,
            BlindPieceType.SOLDIER, BlindPieceType.SOLDIER, BlindPieceType.SOLDIER);

    private BlindXiangqiEngine() {
    }

    /** 开局摆子结果，棋子的唯一 ID 由服务层生成。 */
    public record Placement(BlindColor color, BlindPieceType type, int x, int y) {
    }

    /** 存活棋子快照；反面棋会保留真实颜色与兵种，但不会下发给客户端。 */
    public record Piece(
            String id,
            BlindColor color,
            BlindPieceType type,
            int x,
            int y,
            boolean revealed) {

        public Piece reveal() {
            return new Piece(id, color, type, x, y, true);
        }

        public Piece moveTo(int newX, int newY) {
            return new Piece(id, color, type, newX, newY, revealed);
        }
    }

    private record Army(BlindColor color, BlindPieceType type) {
    }

    public static boolean inBounds(int x, int y) {
        return x >= 0 && x < WIDTH && y >= 0 && y < HEIGHT;
    }

    /** 按选定摆法生成 32 枚棋的初始位置与真实身份。 */
    public static List<Placement> generateLayout(BlindLayout layout) {
        BlindLayout effective = layout == null ? BlindLayout.STANDARD_SHUFFLE : layout;
        return switch (effective) {
            case STANDARD_SHUFFLE -> standardShuffle();
            case FULL_RANDOM -> fullRandom();
            case HALF_RANDOM -> halfRandom();
        };
    }

    /** 查找指定坐标上的存活棋子，没有则返回 null。 */
    public static Piece pieceAt(List<Piece> pieces, int x, int y) {
        for (Piece piece : pieces) {
            if (piece.x() == x && piece.y() == y) {
                return piece;
            }
        }
        return null;
    }

    /**
     * 校验一枚已翻开的己方棋子能否走到目标格。
     * 目标格为空、敌方已翻开棋或任意反面棋时可走；己方已翻开棋不可走。
     */
    public static boolean canMove(List<Piece> pieces, BlindColor color,
                                  int fromX, int fromY, int toX, int toY) {
        if (!inBounds(fromX, fromY) || !inBounds(toX, toY)) {
            return false;
        }
        if (fromX == toX && fromY == toY) {
            return false;
        }
        Piece from = pieceAt(pieces, fromX, fromY);
        if (from == null || !from.revealed() || from.color() != color) {
            return false;
        }
        Piece target = pieceAt(pieces, toX, toY);
        if (target != null && target.revealed() && target.color() == color) {
            return false;
        }

        int dx = toX - fromX;
        int dy = toY - fromY;
        return switch (from.type()) {
            case GENERAL -> Math.abs(dx) + Math.abs(dy) == 1;
            case ADVISOR -> Math.abs(dx) == 1 && Math.abs(dy) == 1;
            case ELEPHANT -> Math.abs(dx) == 2 && Math.abs(dy) == 2
                    && pieceAt(pieces, fromX + dx / 2, fromY + dy / 2) == null;
            case HORSE -> horseCanMove(pieces, fromX, fromY, dx, dy);
            case CHARIOT -> (dx == 0 || dy == 0)
                    && piecesBetween(pieces, fromX, fromY, toX, toY) == 0;
            case CANNON -> cannonCanMove(pieces, fromX, fromY, toX, toY, target);
            case SOLDIER -> soldierCanMove(color, fromX, fromY, dx, dy);
        };
    }

    /**
     * 双方将/帅都已翻开、位于同一列且中间没有任何存活棋子时判和。
     * 任意反面棋也会挡住视线。
     */
    public static boolean generalsFacing(List<Piece> pieces) {
        Piece red = findGeneral(pieces, BlindColor.RED);
        Piece black = findGeneral(pieces, BlindColor.BLACK);
        if (red == null || black == null || !red.revealed() || !black.revealed()) {
            return false;
        }
        if (red.x() != black.x()) {
            return false;
        }
        int fromY = Math.min(red.y(), black.y()) + 1;
        int toY = Math.max(red.y(), black.y());
        for (int y = fromY; y < toY; y += 1) {
            if (pieceAt(pieces, red.x(), y) != null) {
                return false;
            }
        }
        return true;
    }

    private static Piece findGeneral(List<Piece> pieces, BlindColor color) {
        for (Piece piece : pieces) {
            if (piece.color() == color && piece.type() == BlindPieceType.GENERAL) {
                return piece;
            }
        }
        return null;
    }

    private static boolean horseCanMove(List<Piece> pieces, int x, int y, int dx, int dy) {
        if (Math.abs(dx) == 2 && Math.abs(dy) == 1) {
            return pieceAt(pieces, x + dx / 2, y) == null;
        }
        if (Math.abs(dx) == 1 && Math.abs(dy) == 2) {
            return pieceAt(pieces, x, y + dy / 2) == null;
        }
        return false;
    }

    private static boolean cannonCanMove(List<Piece> pieces, int fromX, int fromY,
                                         int toX, int toY, Piece target) {
        if (fromX != toX && fromY != toY) {
            return false;
        }
        int screens = piecesBetween(pieces, fromX, fromY, toX, toY);
        return target == null ? screens == 0 : screens == 1;
    }

    private static boolean soldierCanMove(BlindColor color, int fromX, int fromY, int dx, int dy) {
        int forward = color == BlindColor.RED ? -1 : 1;
        if (dx == 0 && dy == forward) {
            return true;
        }
        return crossedRiver(color, fromY) && Math.abs(dx) == 1 && dy == 0;
    }

    private static int piecesBetween(List<Piece> pieces, int fromX, int fromY, int toX, int toY) {
        int stepX = Integer.signum(toX - fromX);
        int stepY = Integer.signum(toY - fromY);
        int x = fromX + stepX;
        int y = fromY + stepY;
        int count = 0;
        while (x != toX || y != toY) {
            if (pieceAt(pieces, x, y) != null) {
                count += 1;
            }
            x += stepX;
            y += stepY;
        }
        return count;
    }

    private static boolean crossedRiver(BlindColor color, int y) {
        return color == BlindColor.RED ? y <= 4 : y >= 5;
    }

    private static List<Placement> standardShuffle() {
        List<Army> army = shuffledArmy();
        List<int[]> cells = new ArrayList<>(standardCells());
        Collections.shuffle(cells, RANDOM);
        List<Placement> result = new ArrayList<>(army.size());
        for (int i = 0; i < army.size(); i += 1) {
            Army piece = army.get(i);
            int[] cell = cells.get(i);
            result.add(new Placement(piece.color(), piece.type(), cell[0], cell[1]));
        }
        return result;
    }

    private static List<Placement> fullRandom() {
        List<Army> army = shuffledArmy();
        List<int[]> cells = new ArrayList<>(allCells());
        Collections.shuffle(cells, RANDOM);
        List<Placement> result = new ArrayList<>(army.size());
        for (int i = 0; i < army.size(); i += 1) {
            Army piece = army.get(i);
            int[] cell = cells.get(i);
            result.add(new Placement(piece.color(), piece.type(), cell[0], cell[1]));
        }
        return result;
    }

    private static List<Placement> halfRandom() {
        List<Placement> result = new ArrayList<>(32);
        for (BlindColor color : BlindColor.values()) {
            List<BlindPieceType> types = new ArrayList<>(ARMY);
            Collections.shuffle(types, RANDOM);
            List<int[]> cells = new ArrayList<>(halfCells(color));
            Collections.shuffle(cells, RANDOM);
            for (int i = 0; i < types.size(); i += 1) {
                int[] cell = cells.get(i);
                result.add(new Placement(color, types.get(i), cell[0], cell[1]));
            }
        }
        return result;
    }

    private static List<Army> shuffledArmy() {
        List<Army> army = new ArrayList<>(32);
        for (BlindColor color : BlindColor.values()) {
            for (BlindPieceType type : ARMY) {
                army.add(new Army(color, type));
            }
        }
        Collections.shuffle(army, RANDOM);
        return army;
    }

    private static List<int[]> standardCells() {
        List<int[]> cells = new ArrayList<>(32);
        for (int x = 0; x < WIDTH; x += 1) {
            cells.add(new int[]{x, 0});
        }
        cells.add(new int[]{1, 2});
        cells.add(new int[]{7, 2});
        for (int x = 0; x < WIDTH; x += 2) {
            cells.add(new int[]{x, 3});
        }
        for (int x = 0; x < WIDTH; x += 1) {
            cells.add(new int[]{x, 9});
        }
        cells.add(new int[]{1, 7});
        cells.add(new int[]{7, 7});
        for (int x = 0; x < WIDTH; x += 2) {
            cells.add(new int[]{x, 6});
        }
        return cells;
    }

    private static List<int[]> allCells() {
        List<int[]> cells = new ArrayList<>(WIDTH * HEIGHT);
        for (int y = 0; y < HEIGHT; y += 1) {
            for (int x = 0; x < WIDTH; x += 1) {
                cells.add(new int[]{x, y});
            }
        }
        return cells;
    }

    private static List<int[]> halfCells(BlindColor color) {
        List<int[]> cells = new ArrayList<>(WIDTH * 5);
        int fromY = color == BlindColor.RED ? 5 : 0;
        int toY = color == BlindColor.RED ? 9 : 4;
        for (int y = fromY; y <= toY; y += 1) {
            for (int x = 0; x < WIDTH; x += 1) {
                cells.add(new int[]{x, y});
            }
        }
        return cells;
    }
}
