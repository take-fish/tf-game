# tf-game

FishMatrix（摸鱼矩阵）游戏服务：对局匹配与积分排行 —— Spring Boot 3 + JDK 17。

- 端口：`8104`
- 健康检查：`GET /actuator/health`、`GET /api/v1/game/health`
- 一期范围：`qps-breakout`（QPS 流量打砖块）积分榜；其余 4 款游戏仅前端图表骨架
- 存储：Redis ZSET `tf:game:leaderboard:qps-breakout` + 昵称 Hash，仅保留最高分
- 鉴权：`tf-common` 统一校验网关签名身份头（`fm.auth`）；分数提交忽略客户端自报的 `userId` / 昵称

## API

| 方法/路径 | 说明 |
|---|---|
| `GET /api/v1/game/leaderboard?gameId=qps-breakout&limit=10` | 积分榜 |
| `POST /api/v1/game/scores` | 提交分数（身份取自登录态，校验 gameId 与 0..1,000,000） |

## 本地开发

```bash
mvn spring-boot:run    # 启动开发服务
mvn -B test            # 运行测试（不依赖 MySQL/Redis/Nacos）
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
