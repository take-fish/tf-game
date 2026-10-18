package com.fishmatrix.game;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {"spring.cloud.nacos.discovery.enabled=false"})
class GameApplicationTests {

    @Test
    void contextLoads() {
    }
}
