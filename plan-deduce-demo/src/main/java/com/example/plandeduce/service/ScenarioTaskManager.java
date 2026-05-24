package com.example.plandeduce.service;

import com.example.plandeduce.config.PlanDeduceProperties;
import com.example.plandeduce.websocket.PlanDeducePush;
import lombok.Getter;
import org.springframework.stereotype.Service;

import javax.annotation.PreDestroy;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

@Service
/**
 * 任务管理器。
 * 负责维护所有 sessionId 下的运行任务，并复用统一线程池执行播放推进。
 * 任务管理器本身不处理 WebSocket 协议细节，只负责给任务注入统一的推送门面。
 */
public class ScenarioTaskManager {
    private final Map<String, ScenarioTask> taskMap = new ConcurrentHashMap<>();
    /**
     * -- GETTER --
     *  提供统一线程池给 ScenarioTask 创建定时任务使用。
     */
    @Getter
    private final ScheduledExecutorService executor;
    private final ProgressDataService progressDataService;
    private final PlanDeducePush pushService;
    private final PlanDeduceProperties properties;

    public ScenarioTaskManager(ProgressDataService progressDataService,
                               PlanDeducePush pushService,
                               PlanDeduceProperties properties) {
        this.progressDataService = progressDataService;
        this.pushService = pushService;
        this.properties = properties;
        this.executor = Executors.newScheduledThreadPool(Math.max(properties.getMaxWorkerThreads(), 1));
    }

    /**
     * 获取或创建任务。
     * 动态数据库场景下，任务按 dbName + sessionId 联合隔离。
     */
    public ScenarioTask getOrCreate(String dbName, String sessionId) {
        String key = buildKey(dbName, sessionId);
        return taskMap.computeIfAbsent(key, k -> new ScenarioTask(
                dbName,
                sessionId,
                progressDataService,
                pushService,
                properties,
                this
        ));
    }

    /**
     * 仅读取已存在任务，不会创建新任务。
     */
    public ScenarioTask get(String dbName, String sessionId) {
        return taskMap.get(buildKey(dbName, sessionId));
    }

    /**
     * 移除并销毁任务。
     * 这个动作会真正停止定时推进，不是单纯把任务从 Map 删除。
     */
    public void remove(String dbName, String sessionId) {
        ScenarioTask task = taskMap.remove(buildKey(dbName, sessionId));
        if (task != null) {
            task.stopAndDestroy();
        }
    }

    /**
     * 任务唯一键按 dbName + sessionId 组合，避免跨库串任务。
     */
    private String buildKey(String dbName, String sessionId) {
        return dbName + "::" + sessionId;
    }

    /**
     * 应用关闭时统一清理任务和线程池。
     */
    @PreDestroy
    public void destroy() {
        taskMap.values().forEach(ScenarioTask::stopAndDestroy);
        executor.shutdownNow();
    }
}
