# tf-game

FishMatrix（摸鱼矩阵）游戏服务：五子棋赛事编排、实时对战、观战、比赛聊天与综合排行榜 —— Spring Boot 3 + JDK 17。

- 端口：`8104`
- 健康检查：`GET /actuator/health`、`GET /api/v1/game/health`
- 存储：MySQL（`fm_game` / 测试 `fm_test_game`）+ Redis（WS ticket、在线/观战会话）
- 鉴权：`tf-common` 统一校验网关签名身份头；WebSocket 使用一次性 ticket，不把长期 token 放进 URL
- 迁移：Flyway `src/main/resources/db/migration`，业务容器只拿 DML 账号，DDL 由 `db-migrate-game` 执行

## 赛事规则

| 参赛人数 | 编排 |
|---|---|
| 2 / 4 / 8 / 16 | 随机单败淘汰树（决赛、半决赛、1/8…） |
| 3–5 | 单循环积分赛，每胜 +3，按积分/胜场/种子排序 |
| 6 以上非 2 的幂 | 瑞士轮 3 轮（BO1），积分前三进入阶梯决赛 |
| 阶梯决赛 | 第 3 名挑战第 2 名，胜者挑战第 1 名（决赛） |

- 创建时选择 `BO1` / `BO3` / `BO5`；`BO3`/`BO5` 每小局结束休息 60 秒，休息期间双方可点击“立即开始”，也可观战其他比赛。
- 邀请 5 分钟未响应视为拒绝；接受人数不足 2 人时赛事取消。
- 五子棋：15×15、黑先无禁手、每局换先；每步 30 秒超时判负，断线 60 秒判负，支持认输。
- 最终决赛首局真正开始时，向全站推送 `The final begins - {赛事名称}！`；赛事结束时推送 `The champion of {赛事名称} is {nickname}！！！`。赛事名称取创建时填写的名称。
- 删除为软删除：创建者或 `ADMIN` 可删除未处于 `RUNNING` 的赛事；删除后大厅、邀请、详情与运行中调度都不再返回，历史数据保留在库中（`deleted_at`）。

## REST API

| 方法/路径 | 说明 |
|---|---|
| `GET /api/v1/game/leaderboard?gameId=overall&limit=10` | 综合排行榜（gameId 目前忽略） |
| `POST /api/v1/game/scores` | 提交打砖块分数，返回 `{bestScore, rank}`，每 100 分折算 1 积分 |
| `GET /api/v1/game/tournaments?limit=20` | 赛事列表 |
| `GET /api/v1/game/tournaments/invitations` | 我的待处理邀请 |
| `GET /api/v1/game/tournaments/{id}` | 赛事详情（选手、对阵、比分、当前小局） |
| `POST /api/v1/game/tournaments` | 创建赛事 `{name, format, players:[{userId,nickname}]}` |
| `POST /api/v1/game/tournaments/{id}/accept` | 接受邀请 |
| `POST /api/v1/game/tournaments/{id}/decline` | 拒绝邀请 |
| `POST /api/v1/game/tournaments/{id}/cancel` | 创建者取消邀请中赛事 |
| `DELETE /api/v1/game/tournaments/{id}` | 创建者或 `ADMIN` 软删除非进行中赛事；返回 `{id, deleted:true}` |
| `GET /api/v1/game/matches/active` | 我参与的进行中比赛 |
| `GET /api/v1/game/matches/{id}` | 比赛详情（含 `currentRound`、落子、休息截止） |
| `GET /api/v1/game/matches/{id}/chat?channel=PUBLIC\|PLAYER_DM&limit=50` | 比赛聊天历史 |
| `POST /api/v1/game/matches/{id}/chat` | 发送聊天 `{channel,toUserId,content}` |
| `GET /api/v1/game/notifications?after=&limit=` | 全站通知（决赛/冠军），REST 轮询兜底 |
| `POST /api/v1/game/ws-ticket` | 获取一次性 WebSocket ticket |

排行榜字段：`rank/userId/nickname/score/points/matches/wins/losses/championships/bestScore`。五子棋每场胜 +3、负 +1、冠军 +10；打砖块只奖励最高分增量。

## WebSocket `/ws/game?ticket=...`

- 入站：`ping`、`refresh_lobby`、`watch_match`、`leave_match`、`match_ready`、`place_stone`、`resign`、`chat`
- 出站：`game_ready`、`tournament_update`、`game_invitation`、`tournament_cancelled`、`tournament_deleted`、`match_update`、`match_ready`、`stone`、`match_rest`、`match_result`、`game_chat`、`notification`、`pong`、`left_match`、`error`
- `game_ready` 在连接建立时自动下发，包含在线用户、赛事、邀请与我的进行中比赛。
- `stone` 广播给双方选手与已 `watch_match` 的观战者；公开聊天所有观战者可读，选手私聊仅双方可见。
- ticket TTL 60 秒，Redis `GETDEL` 一次性消费；断线重连由客户端重新申请 ticket。

## 数据表

`game_player_stat`（综合排行榜）、`game_tournament`（含软删除字段 `deleted_at`）、`game_tournament_player`、`game_match`、`game_round`、`game_move`、`game_chat_message`、`game_notification`。

迁移：`V1__baseline.sql` 建基线，`V2__tournament_soft_delete.sql` 为赛事表增加 `deleted_at` 与索引；生产自动更新不会执行 Flyway，发布后需手动运行 `db-migrate-game`。

## 配置

| 环境变量 | 默认 | 说明 |
|---|---|---|
| `GAME_SCHEDULING_ENABLED` | `true` | 休息到期、走子超时、断线判负的定时补偿 |
| `GAME_MIN_PLAYERS` / `GAME_MAX_PLAYERS` | `2` / `16` | 创建赛事人数范围 |
| `GAME_INVITE_TIMEOUT_SECONDS` | `300` | 邀请有效期 |
| `GAME_REST_SECONDS` | `60` | 小局休息时长 |
| `GAME_MOVE_TIMEOUT_SECONDS` | `30` | 每步限时 |
| `GAME_DISCONNECT_TIMEOUT_SECONDS` | `60` | 断线判负 |
| `GAME_WS_TICKET_TTL_SECONDS` | `60` | WS ticket 有效期 |
| `GAME_NOTIFICATION_TTL_SECONDS` | `600` | 通知保留时长 |
| `GAME_MAX_CHAT_LENGTH` | `1000` | 单条聊天长度 |
| `GAME_ALLOWED_ORIGINS` | `*` | WebSocket 允许来源（内网部署） |

## 本地开发

```bash
mvn spring-boot:run    # 启动开发服务
mvn -B test            # 运行测试（H2，不依赖 MySQL/Redis/Nacos）
```

## 构建

```bash
mvn -B -DskipTests package   # 产物 target/tf-game.jar
java -jar target/tf-game.jar
```

## 分支模型

- `master`：保护分支，只接受 PR（CI 必须通过，squash 合并），合并后自动发版
- `test`：联调分支，开发分支直接合入（不走 PR），可直接推送
- 开发流：`feature/*` 开发 → 直接合入 `test` 联调 → 验证 OK 后从开发分支开 PR 合 `master` → 自动发版
