package com.fishmatrix.game.model;

import java.util.List;

/**
 * 批量调整展示顺序：按提交的 displayOrder 直接覆盖，未提交的条目保持不变。
 * id 在排行榜里是 userId，在比赛记录里是 matchId。
 */
public record DisplayOrderRequest(List<Item> items) {

    public record Item(String id, Integer displayOrder) {
    }
}
