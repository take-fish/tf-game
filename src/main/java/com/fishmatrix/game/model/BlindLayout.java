package com.fishmatrix.game.model;

import java.util.Locale;

/**
 * 盲棋开局摆法：
 * - STANDARD_SHUFFLE：按标准开局点位随机分配 32 枚棋；
 * - FULL_RANDOM：32 枚棋随机散布在 9×10 全盘；
 * - HALF_RANDOM：红黑各自在自己半场随机散布 16 枚。
 */
public enum BlindLayout {
    STANDARD_SHUFFLE,
    FULL_RANDOM,
    HALF_RANDOM;

    /** 缺省为标准点位洗牌；非法值返回 null 由调用方报错。 */
    public static BlindLayout parse(String value) {
        if (value == null || value.isBlank()) {
            return STANDARD_SHUFFLE;
        }
        try {
            return valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
}
