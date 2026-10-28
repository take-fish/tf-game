package com.fishmatrix.game.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

class MapperLocationTest {

    @Test
    @DisplayName("application.yml 里配置的 classpath:mapper/*.xml 能解析到 XML")
    void configuredPatternResolvesXml() throws IOException {
        Resource[] resources =
                new PathMatchingResourcePatternResolver().getResources("classpath:mapper/*.xml");

        assertThat(resources).isNotEmpty();
        assertThat(resources).extracting(Resource::getFilename)
                .contains("MatchMapper.xml", "TournamentMapper.xml", "PlayerStatMapper.xml",
                        "GameExerciseMapper.xml", "GameBlindPieceMapper.xml",
                        "GameChatMessageMapper.xml", "GameNotificationMapper.xml");
    }

    @Test
    @DisplayName("每个 XML 的 namespace 都指向同包同名 Mapper 接口")
    void namespacesMatchMapperInterfaces() throws Exception {
        Resource[] resources =
                new PathMatchingResourcePatternResolver().getResources("classpath:mapper/*.xml");
        for (Resource resource : resources) {
            String filename = resource.getFilename();
            String expected = "com.fishmatrix.game.mapper."
                    + filename.substring(0, filename.length() - ".xml".length());
            String content = new String(resource.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            assertThat(content).as("%s 的 namespace 应为 %s", filename, expected)
                    .contains("namespace=\"" + expected + "\"");
        }
    }

    @Test
    @DisplayName("mapper XML 与 Mapper 接口一一对应，不残留孤儿 SQL 文件")
    void xmlFilesMatchMapperInterfaces() throws Exception {
        Resource[] resources =
                new PathMatchingResourcePatternResolver().getResources("classpath:mapper/*.xml");
        for (Resource resource : resources) {
            String filename = resource.getFilename();
            String simpleName = filename.substring(0, filename.length() - ".xml".length());
            assertThat(Class.forName("com.fishmatrix.game.mapper." + simpleName))
                    .as("%s 没有对应的 Mapper 接口", filename)
                    .isNotNull();
        }
    }
}
