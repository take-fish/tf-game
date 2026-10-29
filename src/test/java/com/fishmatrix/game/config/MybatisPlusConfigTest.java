package com.fishmatrix.game.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.fishmatrix.game.entity.GamePlayerStatEntity;
import com.fishmatrix.game.entity.GameTournamentEntity;
import com.fishmatrix.game.mapper.GameNotificationMapper;
import com.fishmatrix.game.mapper.TournamentMapper;
import java.time.LocalDateTime;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.cloud.nacos.discovery.enabled=false",
            "spring.cloud.nacos.config.enabled=false",
            "app.game.scheduling-enabled=false"
        })
class MybatisPlusConfigTest {

    private static final LocalDateTime EXPLICIT =
            LocalDateTime.of(2024, 1, 2, 3, 4, 5, 6_000_000);

    @Autowired
    private MetaObjectHandler metaObjectHandler;

    @Autowired
    private TournamentMapper tournamentMapper;

    @Autowired
    private GameNotificationMapper notificationMapper;

    @Test
    @DisplayName("自动填充处理器与 Mapper 都已注册")
    void beansRegistered() {
        assertThat(metaObjectHandler).isNotNull();
        assertThat(tournamentMapper).isNotNull();
        assertThat(notificationMapper).isNotNull();
    }

    @Test
    @DisplayName("insert 时补齐 createdAt，已显式赋值则不覆盖")
    void insertFillsCreatedAtOnlyWhenMissing() {
        GameTournamentEntity blank = new GameTournamentEntity();
        metaObjectHandler.insertFill(SystemMetaObject.forObject(blank));
        assertThat(blank.getCreatedAt()).isNotNull();

        GameTournamentEntity preset = new GameTournamentEntity();
        preset.setCreatedAt(EXPLICIT);
        metaObjectHandler.insertFill(SystemMetaObject.forObject(preset));
        assertThat(preset.getCreatedAt()).isEqualTo(EXPLICIT);
    }

    @Test
    @DisplayName("insert 时补齐 updatedAt，已显式赋值则不覆盖")
    void insertFillsUpdatedAtOnlyWhenMissing() {
        GamePlayerStatEntity blank = new GamePlayerStatEntity();
        metaObjectHandler.insertFill(SystemMetaObject.forObject(blank));
        assertThat(blank.getUpdatedAt()).isNotNull();

        GamePlayerStatEntity preset = new GamePlayerStatEntity();
        preset.setUpdatedAt(EXPLICIT);
        metaObjectHandler.insertFill(SystemMetaObject.forObject(preset));
        assertThat(preset.getUpdatedAt()).isEqualTo(EXPLICIT);
    }

    @Test
    @DisplayName("update 时补齐 updatedAt，已显式赋值则不覆盖")
    void updateFillsUpdatedAtOnlyWhenMissing() {
        GamePlayerStatEntity blank = new GamePlayerStatEntity();
        metaObjectHandler.updateFill(SystemMetaObject.forObject(blank));
        assertThat(blank.getUpdatedAt()).isNotNull();

        GamePlayerStatEntity preset = new GamePlayerStatEntity();
        preset.setUpdatedAt(EXPLICIT);
        metaObjectHandler.updateFill(SystemMetaObject.forObject(preset));
        assertThat(preset.getUpdatedAt()).isEqualTo(EXPLICIT);
    }
}
