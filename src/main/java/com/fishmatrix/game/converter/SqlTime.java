package com.fishmatrix.game.converter;

import java.sql.Timestamp;
import java.time.LocalDateTime;

/**
 * JDBC 与毫秒时间戳之间的转换。
 *
 * <p>业务模型统一使用 epoch millis，数据库存 DATETIME(3)。实体侧改用 {@link LocalDateTime}，
 * 这里的换算仍走 {@link Timestamp}：{@code new Timestamp(millis)} 与
 * {@code Timestamp#getTime()} 的时区口径和迁移前完全一致，保证对外 JSON 的毫秒时间戳不变。</p>
 */
public final class SqlTime {

    private SqlTime() {
    }

    public static Timestamp ts(long millis) {
        return new Timestamp(millis);
    }

    public static Timestamp ts(Long millis) {
        return millis == null ? null : new Timestamp(millis);
    }

    /** epoch millis → DATETIME(3) 的 {@link LocalDateTime} 视图。 */
    public static LocalDateTime toDateTime(long millis) {
        return ts(millis).toLocalDateTime();
    }

    /** 可空版本的 {@link #toDateTime(long)}。 */
    public static LocalDateTime toDateTime(Long millis) {
        Timestamp value = ts(millis);
        return value == null ? null : value.toLocalDateTime();
    }

    /** DATETIME(3) → epoch millis；空值按旧口径返回 0。 */
    public static long toMillis(LocalDateTime value) {
        return value == null ? 0L : Timestamp.valueOf(value).getTime();
    }

    /** DATETIME(3) → epoch millis；空值保持 null。 */
    public static Long toNullableMillis(LocalDateTime value) {
        return value == null ? null : Timestamp.valueOf(value).getTime();
    }
}
