package com.fishmatrix.game.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EntityMappingTest {

    private static final Map<Class<?>, String> EXPECTED = new LinkedHashMap<>();

    private static final Map<Class<?>, String> EXPECTED_IDS = new LinkedHashMap<>();

    private static final Set<String> AUDIT_FIELDS = Set.of("createdAt", "updatedAt");

    static {
        EXPECTED.put(GameTournamentEntity.class, "game_tournament");
        EXPECTED.put(GameTournamentPlayerEntity.class, "game_tournament_player");
        EXPECTED.put(GameMatchEntity.class, "game_match");
        EXPECTED.put(GameRoundEntity.class, "game_round");
        EXPECTED.put(GameMoveEntity.class, "game_move");
        EXPECTED.put(GameBlindPieceEntity.class, "game_blind_piece");
        EXPECTED.put(GameChatMessageEntity.class, "game_chat_message");
        EXPECTED.put(GameNotificationEntity.class, "game_notification");
        EXPECTED.put(GamePlayerStatEntity.class, "game_player_stat");
        EXPECTED.put(GamePlayerGameStatEntity.class, "game_player_game_stat");
        EXPECTED.put(GameExerciseRoomEntity.class, "game_exercise_room");
        EXPECTED.put(GameExerciseMemberEntity.class, "game_exercise_member");
        EXPECTED.put(GameExerciseResultEntity.class, "game_exercise_result");

        EXPECTED_IDS.put(GameTournamentEntity.class, "id");
        EXPECTED_IDS.put(GameMatchEntity.class, "id");
        EXPECTED_IDS.put(GameRoundEntity.class, "id");
        EXPECTED_IDS.put(GameChatMessageEntity.class, "id");
        EXPECTED_IDS.put(GameNotificationEntity.class, "id");
        EXPECTED_IDS.put(GameExerciseRoomEntity.class, "id");
        EXPECTED_IDS.put(GameExerciseResultEntity.class, "id");
        EXPECTED_IDS.put(GamePlayerStatEntity.class, "user_id");
    }

    @Test
    @DisplayName("每张表都有且只有一个实体，表名与实体一一对应")
    void everyTableHasOneEntity() {
        Set<Class<?>> scanned = scanEntityClasses();
        assertThat(scanned)
                .as("entity 包下的实体必须与下面的清单完全对齐（新增表请同步补实体）")
                .containsExactlyInAnyOrderElementsOf(EXPECTED.keySet());
    }

    @Test
    @DisplayName("实体表名与 @TableName 一致")
    void tablesMatch() {
        EXPECTED.forEach((type, expectedTable) -> {
            TableName tableName = type.getAnnotation(TableName.class);
            assertThat(tableName).as("%s 缺少 @TableName", type.getSimpleName()).isNotNull();
            assertThat(tableName.value()).isEqualTo(expectedTable);
        });
    }

    @Test
    @DisplayName("字符主键用 @TableId(INPUT)，复合主键表没有 @TableId")
    void tableIds() throws NoSuchFieldException {
        for (Class<?> type : EXPECTED.keySet()) {
            String expectedColumn = EXPECTED_IDS.get(type);
            if (expectedColumn == null) {
                assertThat(hasAnnotatedId(type))
                        .as("%s 是复合主键，不应有 @TableId", type.getSimpleName())
                        .isFalse();
                continue;
            }
            Field id = idField(type);
            TableId tableId = id.getAnnotation(TableId.class);
            assertThat(tableId).as("%s 缺少 @TableId", type.getSimpleName()).isNotNull();
            assertThat(tableId.value()).as("%s 主键列名", type.getSimpleName())
                    .isEqualTo(expectedColumn);
            assertThat(tableId.type()).isEqualTo(IdType.INPUT);
        }
    }

    @Test
    @DisplayName("DATETIME(3) 列一律映射 LocalDateTime，不出现 Date/Timestamp")
    void timestampsUseLocalDateTime() {
        Set<Class<?>> expectedTimeTypes = Set.of(LocalDateTime.class);
        for (Class<?> type : EXPECTED.keySet()) {
            for (Field field : type.getDeclaredFields()) {
                if (!field.getName().endsWith("At") && !field.getName().endsWith("Time")) {
                    continue;
                }
                assertThat(field.getType())
                        .as("%s.%s 必须是 LocalDateTime", type.getSimpleName(), field.getName())
                        .isIn(expectedTimeTypes);
            }
        }
    }

    @Test
    @DisplayName("审计字段用 LocalDateTime（表结构是 DATETIME(3)）并标注了 fill")
    void auditFieldsUseLocalDateTimeWithFill() {
        int total = 0;
        for (Class<?> type : EXPECTED.keySet()) {
            for (Field field : type.getDeclaredFields()) {
                if (!AUDIT_FIELDS.contains(field.getName())) {
                    continue;
                }
                total++;
                assertThat(field.getType())
                        .as("%s.%s 必须是 LocalDateTime", type.getSimpleName(), field.getName())
                        .isEqualTo(LocalDateTime.class);
                TableField tableField = field.getAnnotation(TableField.class);
                assertThat(tableField)
                        .as("%s.%s 缺少 @TableField", type.getSimpleName(), field.getName())
                        .isNotNull();
                assertThat(tableField.fill())
                        .as("%s.%s 的 fill 不能是 DEFAULT", type.getSimpleName(), field.getName())
                        .isNotEqualTo(FieldFill.DEFAULT);
            }
        }
        assertThat(total).as("审计字段总数应为 9").isEqualTo(9);
    }

    @Test
    @DisplayName("实体只依赖 MyBatis-Plus 注解与 JDK 类型")
    void entitiesDoNotLeakToUpperLayers() {
        for (Class<?> type : EXPECTED.keySet()) {
            assertThat(type.getPackageName()).isEqualTo("com.fishmatrix.game.entity");
        }
    }

    private static Set<Class<?>> scanEntityClasses() {
        JavaClasses classes = new ClassFileImporter()
                .importPackages("com.fishmatrix.game.entity");
        return classes.stream()
                .filter(javaClass -> javaClass.getPackageName()
                        .equals("com.fishmatrix.game.entity"))
                .filter(javaClass -> javaClass.getSimpleName().endsWith("Entity"))
                .map(javaClass -> (Class<?>) javaClass.reflect())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static Field idField(Class<?> type) throws NoSuchFieldException {
        for (Field field : type.getDeclaredFields()) {
            if (field.isAnnotationPresent(TableId.class)) {
                return field;
            }
        }
        throw new NoSuchFieldException(type.getSimpleName() + " 没有 @TableId 字段");
    }

    private static boolean hasAnnotatedId(Class<?> type) {
        for (Field field : type.getDeclaredFields()) {
            if (field.isAnnotationPresent(TableId.class)) {
                return true;
            }
        }
        return false;
    }
}
