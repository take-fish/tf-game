package com.fishmatrix.game.store;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;

/**
 * JDBC 与毫秒时间戳之间的转换。业务模型统一使用 epoch millis，数据库存 DATETIME(3)。
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

    public static long millis(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? 0L : value.getTime();
    }

    public static Long nullableMillis(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.getTime();
    }

    public static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }
}
