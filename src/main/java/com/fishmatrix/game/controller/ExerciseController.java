package com.fishmatrix.game.controller;

import com.fishmatrix.common.auth.UserContext;
import com.fishmatrix.game.model.CreateExerciseRoomRequest;
import com.fishmatrix.game.model.GameIdentity;
import com.fishmatrix.game.service.ExerciseService;
import com.fishmatrix.game.store.ExerciseRepository.ResultRow;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 集群巡检对抗（2D 轻 FPS）房间与战绩入口。
 * 实时输入走 WebSocket；这里有房间大厅、加入/准备/离开与最近战绩。
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/game/exercise")
public class ExerciseController {

    private final ExerciseService exercise;

    @GetMapping("/rooms")
    public Map<String, Object> rooms() {
        List<Map<String, Object>> rooms = exercise.listRooms(identity());
        return Map.of("rooms", rooms);
    }

    @PostMapping("/rooms")
    public Map<String, Object> create(@RequestBody CreateExerciseRoomRequest request) {
        return exercise.create(
                identity(),
                request == null ? null : request.mode(),
                request == null ? null : request.maxPlayers());
    }

    @GetMapping("/rooms/{key}")
    public Map<String, Object> detail(@PathVariable String key) {
        return exercise.detail(identity(), key);
    }

    @PostMapping("/rooms/{key}/join")
    public Map<String, Object> join(@PathVariable String key) {
        return exercise.join(identity(), key);
    }

    @PostMapping("/rooms/{key}/ready")
    public Map<String, Object> ready(@PathVariable String key) {
        return exercise.ready(identity(), key);
    }

    @PostMapping("/rooms/{key}/leave")
    public Map<String, Object> leave(@PathVariable String key) {
        exercise.leave(identity(), key);
        return Map.of("left", true);
    }

    @GetMapping("/results")
    public Map<String, Object> results(@RequestParam(defaultValue = "20") int limit) {
        List<ResultRow> rows = exercise.recentResults(limit);
        return Map.of("results", rows);
    }

    private static GameIdentity identity() {
        return GameIdentity.of(UserContext.require());
    }
}
