package com.fishmatrix.game.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 对局的进程内会话状态：准备确认与断线计时。
 * 只在单实例内有效，重启后由数据库中的比赛/小局状态重新推导。
 */
@Component
class MatchSessionState {

    private final Map<String, Set<String>> readyUsers = new ConcurrentHashMap<>();
    private final Map<String, Long> disconnectAt = new ConcurrentHashMap<>();

    Set<String> ready(String matchId) {
        return readyUsers.computeIfAbsent(matchId, key -> ConcurrentHashMap.newKeySet());
    }

    void clearReady(String matchId) {
        readyUsers.remove(matchId);
    }

    void markDisconnect(String matchId, String userId) {
        disconnectAt.put(disconnectKey(matchId, userId), System.currentTimeMillis());
    }

    /** 连接恢复：清掉该用户在所有比赛里的断线计时。 */
    void markConnected(String userId) {
        String suffix = "|" + userId;
        disconnectAt.keySet().removeIf(key -> key.endsWith(suffix));
    }

    List<String> disconnectKeys() {
        return new ArrayList<>(disconnectAt.keySet());
    }

    Long disconnectSince(String key) {
        return disconnectAt.get(key);
    }

    void clearDisconnect(String key) {
        disconnectAt.remove(key);
    }

    /** 比赛结束时清掉全部断线计时。 */
    void clearMatchDisconnects(String matchId) {
        String prefix = matchId + "|";
        disconnectAt.keySet().removeIf(key -> key.startsWith(prefix));
    }

    static String disconnectKey(String matchId, String userId) {
        return matchId + "|" + userId;
    }
}
