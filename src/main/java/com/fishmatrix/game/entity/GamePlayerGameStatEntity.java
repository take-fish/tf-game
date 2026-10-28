package com.fishmatrix.game.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/** 分游戏榜：按 game_id 分别累计场次、积分与冠军次数，复合主键 (game_id, user_id)。 */
@Data
@TableName("game_player_game_stat")
public class GamePlayerGameStatEntity {

    private String gameId;

    private String userId;

    private String nickname;

    private Integer matches;

    private Integer wins;

    private Integer losses;

    private Integer points;

    private Integer championships;

    /** 管理员可下架某条成绩；默认可见。 */
    private Boolean visible;

    /** 展示权重，越大越靠前，0 表示按战绩排序。 */
    private Integer displayOrder;

    private String managedBy;

    private LocalDateTime managedAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}

