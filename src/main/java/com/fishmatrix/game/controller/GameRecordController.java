package com.fishmatrix.game.controller;

import com.fishmatrix.game.model.MatchRecordPreview;
import com.fishmatrix.game.service.GameRecordService;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 比赛记录列表：只返回预览（对阵、比分、状态），小局与落子走比赛详情接口。
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/game")
public class GameRecordController {

    private final GameRecordService records;

    @GetMapping("/records")
    public Map<String, Object> list(@RequestParam(defaultValue = "20") int limit) {
        List<MatchRecordPreview> previews = records.list(limit);
        return Map.of("records", previews);
    }
}
