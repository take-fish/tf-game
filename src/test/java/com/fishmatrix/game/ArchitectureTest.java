package com.fishmatrix.game;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.baomidou.mybatisplus.annotation.TableName;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.apache.ibatis.annotations.Mapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 分层守护测试。
 *
 * <p>这些规则不是"风格偏好"，而是防止架构腐化：一旦有人为了省事让 controller 直接注 mapper、
 * 或者把实体当 DTO 返回给前端，测试立刻变红。</p>
 */
class ArchitectureTest {

    private static final JavaClasses CLASSES =
            new ClassFileImporter().importPackages("com.fishmatrix.game");

    @Test
    @DisplayName("controller 不得直接依赖 mapper")
    void controllerMustNotTouchMappers() {
        ArchRule rule = noClasses()
                .that()
                .resideInAPackage("..controller..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("..mapper..")
                .because("controller 只做收/转/发，数据访问统一由 service 负责");
        rule.check(CLASSES);
    }

    @Test
    @DisplayName("service 不得依赖 controller")
    void serviceMustNotDependOnController() {
        ArchRule rule = noClasses()
                .that()
                .resideInAPackage("..service..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("..controller..")
                .because("依赖方向只能向下：controller → service → mapper");
        rule.check(CLASSES);
    }

    @Test
    @DisplayName("entity 不得依赖上层（service/controller/model/mapper）")
    void entityMustStayDumb() {
        ArchRule rule = noClasses()
                .that()
                .resideInAPackage("..entity..")
                .and()
                .haveSimpleNameNotEndingWith("Test")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("..service..", "..controller..", "..model..", "..mapper..")
                .because("实体是数据库行的镜像，不该知道自己被谁使用");
        rule.check(CLASSES);
    }

    @Test
    @DisplayName("controller 不得把 entity 当返回值")
    void controllerMustNotReturnEntities() {
        ArchRule rule = noClasses()
                .that()
                .resideInAPackage("..controller..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("..entity..")
                .because("实体直接序列化等于把数据库结构暴露给前端");
        rule.check(CLASSES);
    }

    @Test
    @DisplayName("持久化只走 entity + mapper，store 包内不再出现 JdbcTemplate 手写仓储")
    void noJdbcRepositoryLayer() {
        ArchRule storeRule = noClasses()
                .that()
                .resideInAPackage("..store..")
                .should()
                .dependOnClassesThat()
                .haveFullyQualifiedName("org.springframework.jdbc.core.JdbcTemplate")
                .because("持久化统一走 entity + mapper，不再维护 JdbcTemplate 手写仓储");
        storeRule.check(CLASSES);

        ArchRule repositoryPackageRule = noClasses()
                .should()
                .resideInAPackage("..repository..")
                .because("持久化统一走 entity + mapper，不再维护 JdbcTemplate 手写仓储");
        repositoryPackageRule.check(CLASSES);
    }

    @Test
    @DisplayName("mapper 包下以 Mapper 结尾的类型必须是 @Mapper 接口")
    void mappersAreInterfaces() {
        ArchRule rule = classes()
                .that()
                .resideInAPackage("..mapper..")
                .and()
                .haveSimpleNameEndingWith("Mapper")
                .should()
                .beInterfaces()
                .andShould()
                .beAnnotatedWith(Mapper.class)
                .because("MyBatis 靠 @Mapper 注册接口，漏标就会注入失败");
        rule.check(CLASSES);
    }

    @Test
    @DisplayName("实体一律标注 @TableName")
    void entitiesCarryOnlyTableName() {
        ArchRule rule = classes()
                .that()
                .resideInAPackage("..entity..")
                .and()
                .haveSimpleNameEndingWith("Entity")
                .should()
                .beAnnotatedWith(TableName.class)
                .because("实体必须显式声明表名，避免靠命名推断踩到复数/前缀坑");
        rule.check(CLASSES);
    }
}
