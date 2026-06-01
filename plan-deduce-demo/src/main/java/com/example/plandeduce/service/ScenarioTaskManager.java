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

/** 管理所有会话任务。 */
@Service
public class ScenarioTaskManager {
    private final Map<String, ScenarioTask> taskMap = new ConcurrentHashMap<>();
    /** 提供调度线程池。 */
    @Getter
    private final ScheduledExecutorService executor;
    private final ProgressDataService progressDataService;
    private final PlanDeducePush pushService;
    private final PlanDeduceProperties properties;

    /** 创建任务管理器。 */
    public ScenarioTaskManager(ProgressDataService progressDataService,
                               PlanDeducePush pushService,
                               PlanDeduceProperties properties) {
        this.progressDataService = progressDataService;
        this.pushService = pushService;
        this.properties = properties;
        this.executor = Executors.newScheduledThreadPool(Math.max(properties.getMaxWorkerThreads(), 1));
    }

    /** 获取或创建任务。 */
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

    /** 获取任务。 */
    public ScenarioTask get(String dbName, String sessionId) {
        return taskMap.get(buildKey(dbName, sessionId));
    }

    /** 删除任务并释放资源。 */
    public void remove(String dbName, String sessionId) {
        ScenarioTask task = taskMap.remove(buildKey(dbName, sessionId));
        if (task != null) {
            task.stopAndDestroy();
        }
    }

    /** 生成任务键。 */
    private String buildKey(String dbName, String sessionId) {
        return dbName + "::" + sessionId;
    }

    /** 关闭时清理任务。 */
    @PreDestroy
    public void destroy() {
        taskMap.values().forEach(ScenarioTask::stopAndDestroy);
        executor.shutdownNow();
    }
}
