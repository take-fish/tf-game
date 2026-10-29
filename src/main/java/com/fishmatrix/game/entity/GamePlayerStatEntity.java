package com.fishmatrix.game.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/** 综合大榜：一名选手一行，points 与 championships 由赛事结算写入。 */
@Data
@TableName("game_player_stat")
public class GamePlayerStatEntity {

    @TableId(value = "user_id", type = IdType.INPUT)
    private String userId;

    private String nickname;

    private Integer matches;

    private Integer wins;

    private Integer losses;

    private Integer points;

    private Integer championships;

    private Integer runnerUps;

    private Long bestScore;

    /** 管理员可下架某条成绩；默认可见。 */
    private Boolean visible;

    /** 展示权重，越大越靠前，0 表示按战绩排序。 */
    private Integer displayOrder;

    private String managedBy;

    private LocalDateTime managedAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}

