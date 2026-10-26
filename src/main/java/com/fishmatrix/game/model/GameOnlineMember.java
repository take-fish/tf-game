package com.fishmatrix.game.model;

/**
 * 比赛域在线成员快照。昵称来自用户中台身份，前端据此展示名号而不是账号 ID。
 */
public record GameOnlineMember(String userId, String nickname) {
}
