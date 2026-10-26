package com.fishmatrix.game.model;

/**
 * 创建比赛时前端提交的候选选手。nickname 仅用于邀请展示，
 * 接受邀请时以登录身份为准，避免客户端伪造昵称。
 */
public record PlayerRef(String userId, String nickname) {
}
