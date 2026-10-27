package com.fishmatrix.game.model;

/**
 * 盲棋阵营。红方先手。
 */
public enum BlindColor {
    RED,
    BLACK;

    public BlindColor opponent() {
        return this == RED ? BLACK : RED;
    }
}
