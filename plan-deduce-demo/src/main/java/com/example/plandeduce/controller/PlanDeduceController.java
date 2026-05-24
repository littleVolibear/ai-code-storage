package com.example.plandeduce.controller;

import com.example.plandeduce.service.ScenarioTask;
import com.example.plandeduce.service.ScenarioTaskManager;
import com.example.plandeduce.service.ProgressDataService;
import com.example.plandeduce.model.ProgressQueryContext;
import com.example.plandeduce.model.ProgressTimeline;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.springframework.web.bind.annotation.*;

import javax.validation.constraints.NotNull;

@RestController
@RequestMapping("/plan")
/**
 * 进度条控制接口。
 * HTTP 负责发命令，播放状态和数据变化通过 WebSocket 推送给前端。
 */
public class PlanDeduceController {
    private final ScenarioTaskManager taskManager;
    private final ProgressDataService progressDataService;

    public PlanDeduceController(ScenarioTaskManager taskManager, ProgressDataService progressDataService) {
        this.taskManager = taskManager;
        this.progressDataService = progressDataService;
    }

    /**
     * 初始化任务，但不开始播放。
     * 前端拿到时间范围后，需要再调用 startOrStop(flag=1) 才会真正开始推送 WebSocket 数据。
     * dbName 表示初始化时选定的数据库连接标识；
     * 当前实现约定它同时与所选库中的 ROOM_INFO.id 保持一致。
     */
    @GetMapping("/sendPlanDeduce")
    public InitProgressResponse sendPlanDeduce(@NotNull(message = "库名不能为空") @RequestParam String dbName,
                                               @RequestParam(defaultValue = "0") Integer skip,
                                               @RequestParam(defaultValue = "default") String sessionId) {
        ScenarioTask existingTask = taskManager.get(dbName, sessionId);
        if (existingTask != null && existingTask.isExecuting()) {
            throw new IllegalArgumentException("当前任务正在执行，请先暂停或销毁后再初始化");
        }
        ScenarioTask task = existingTask != null ? existingTask : taskManager.getOrCreate(dbName, sessionId);
        ProgressTimeline timeline = progressDataService.queryProgressTimeline(new ProgressQueryContext(dbName));
        task.initialize(skip, null, timeline.getEndTime());
        return new InitProgressResponse(dbName, sessionId, timeline.getStartTime(), timeline.getEndTime());
    }

    /**
     * 跳转到指定秒点，并立即推送该秒点对应的完整快照。
     */
    @GetMapping("/skip")
    public void skip(@NotNull(message = "库名不能为空") @RequestParam String dbName,
                     @NotNull(message = "跳转时间不能为空") @RequestParam Integer skip,
                     @RequestParam(defaultValue = "default") String sessionId) {
        ScenarioTask task = taskManager.getOrCreate(dbName, sessionId);
        task.skipAndResume(skip);
    }

    /**
     * 修改当前任务倍速。
     * speed=0 表示暂停；
     * speed>0 时会先更新倍速，如果当前未运行则自动开始或恢复播放，如果当前已在播放则仅影响后续推进速度。
     */
    @GetMapping("/speed")
    public void speed(@NotNull(message = "库名不能为空") @RequestParam String dbName,
                      @NotNull(message = "倍速不能为空") @RequestParam Integer speed,
                      @RequestParam(defaultValue = "default") String sessionId,
                      @RequestParam(required = false) String uuid) {
        ScenarioTask task = taskManager.getOrCreate(dbName, sessionId);
        task.setSpeedAndResume(speed);
    }

    /**
     * 统一的开始/暂停入口。
     * flag=0 暂停，flag=1 开始。
     */
    @GetMapping("/startOrStop")
    public void startOrStop(@NotNull(message = "库名不能为空") @RequestParam String dbName,
                            @NotNull(message = "开始暂停标识不能为空") @RequestParam Integer flag,
                            @RequestParam(defaultValue = "default") String sessionId) {
        ScenarioTask task = taskManager.getOrCreate(dbName, sessionId);
        task.startOrStop(flag);
    }

    /**
     * 修改全量快照间隔。
     * 修改后会立即按新间隔规则返回一次当前快照，方便前端立刻刷新。
     */
    @GetMapping("/fullSaveInterval")
    public void fullSaveInterval(@NotNull(message = "库名不能为空") @RequestParam String dbName,
                                 @NotNull(message = "全量保存间隔不能为空") @RequestParam Integer fullSaveIntervalSeconds,
                                 @RequestParam(defaultValue = "default") String sessionId) {
        ScenarioTask task = taskManager.getOrCreate(dbName, sessionId);
        task.updateFullSaveInterval(fullSaveIntervalSeconds);
    }

    /**
     * 销毁任务。
     * 这个动作会停止定时推进并释放后端资源，前端退出页面时应主动调用。
     */
    @GetMapping("/destroy")
    public void destroy(@RequestParam String dbName,
                        @RequestParam(defaultValue = "default") String sessionId) {
        taskManager.remove(dbName, sessionId);
    }

    /**
     * 初始化接口直接返回进度条时间范围，避免前端还要再额外查一次。
     */
    @Data
    @AllArgsConstructor
    public static class InitProgressResponse {
        private final String dbName;
        private final String sessionId;
        private final String startTime;
        private final Integer endTime;
    }
}
