package com.fishmatrix.game.controller;

import com.fishmatrix.game.model.NotificationView;
import com.fishmatrix.game.service.GameNotificationService;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/game/notifications")
public class NotificationController {

    private final GameNotificationService notifications;

    public NotificationController(GameNotificationService notifications) {
        this.notifications = notifications;
    }

    /** after 为客户端已收到的最后时间戳，0 表示取最近通知。 */
    @GetMapping
    public Map<String, Object> recent(
            @RequestParam(defaultValue = "0") long after,
            @RequestParam(defaultValue = "20") int limit) {
        List<NotificationView> entries = notifications.recent(after, limit);
        return Map.of("notifications", entries);
    }
}
