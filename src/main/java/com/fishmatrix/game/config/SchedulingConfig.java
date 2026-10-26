package com.fishmatrix.game.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 邀请超时、休息开局、落子超时、断线判负依赖定时任务。
 * 测试环境可关闭，避免无数据库连接时持续刷错误日志。
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(
        prefix = "app.game",
        name = "scheduling-enabled",
        havingValue = "true",
        matchIfMissing = true)
public class SchedulingConfig {
}
