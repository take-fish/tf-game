package com.fishmatrix.game.model;

import com.fishmatrix.common.auth.CurrentUser;

/**
 * 比赛域身份。userId 统一映射为 u{accountId}，与聊天、前端 identity 保持一致。
 */
public record GameIdentity(
        long accountId,
        String userId,
        String nickname,
        String role) {

    public static GameIdentity of(CurrentUser user) {
        String nickname = user.nickname() == null || user.nickname().isBlank()
                ? user.username()
                : user.nickname();
        return new GameIdentity(user.id(), user.userId(), nickname, user.role());
    }
}
