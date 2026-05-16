package com.example.plandeduce.controller;

import com.example.plandeduce.service.ScenarioTask;
import com.example.plandeduce.service.ScenarioTaskManager;
import org.springframework.web.bind.annotation.*;

import javax.validation.constraints.NotNull;

@RestController
@RequestMapping("/plan")
/**
 * 进度条控制接口。
 * 这里的 HTTP 接口只负责“发命令”，真正的播放状态和数据变化通过 WebSocket 推送给前端。
 */
public class PlanDeduceController {
    private final ScenarioTaskManager taskManager;

    public PlanDeduceController(ScenarioTaskManager taskManager) {
        this.taskManager = taskManager;
    }

    /**
     * 初始化任务并开始播放。
     * 第一次进入页面或 destroy 后重新开始时，前端应优先调用这个接口。
     */
    @GetMapping("/sendPlanDeduce")
    public void sendPlanDeduce(@NotNull(message = "库名不能为空") @RequestParam String dbName,
                               @RequestParam(defaultValue = "0") Integer skip,
                               @RequestParam(defaultValue = "default") String sessionId) {
        // 初始化前先检查现有任务是否仍在播放，避免同一个任务在执行中被重新初始化打乱状态和推送时序。
        ScenarioTask existingTask = taskManager.get(dbName, sessionId);
        if (existingTask != null && existingTask.isExecuting()) {
            throw new IllegalArgumentException("当前任务正在执行，请先暂停或销毁后再初始化");
        }
        ScenarioTask task = existingTask != null ? existingTask : taskManager.getOrCreate(dbName, sessionId);
        task.start(skip, null);
    }

    /**
     * 跳转到指定秒点，并立即推送该秒点对应的完整快照。
     */
    @GetMapping("/skip")
    public void skip(@NotNull(message = "库名不能为空") @RequestParam String dbName,
                     @NotNull(message = "跳转时间不能为空") @RequestParam Integer skip,
                     @RequestParam(defaultValue = "default") String sessionId) {
        ScenarioTask task = taskManager.getOrCreate(dbName, sessionId);
        task.skip(skip);
    }

    /**
     * 修改当前任务倍速。
     * speed=0 表示暂停；暂停态下调成非 0 倍速只会更新速度，不会自动恢复播放。
     */
    @GetMapping("/speed")
    public void speed(@NotNull(message = "库名不能为空") @RequestParam String dbName,
                      @NotNull(message = "倍速不能为空") @RequestParam Integer speed,
                      @RequestParam(defaultValue = "default") String sessionId,
                      @RequestParam(required = false) String uuid) {
        ScenarioTask task = taskManager.getOrCreate(dbName, sessionId);
        task.setSpeed(speed);
    }

    /**
     * 统一的开始/暂停入口。
     * flag=0 暂停，flag=1 开始。
     */
    @GetMapping("/startOrStop")
    public void startOrStop(@NotNull(message = "库名不能为空") @RequestParam String dbName,
                            @NotNull(message = "开始暂停标识不能为空") @RequestParam Integer flag,
                            @RequestParam(defaultValue = "default") String sessionId,
                            @RequestParam(required = false) String uuid) {
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
}
