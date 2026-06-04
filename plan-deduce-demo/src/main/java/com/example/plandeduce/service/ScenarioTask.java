package com.example.plandeduce.service;

import com.example.plandeduce.config.PlanDeduceProperties;
import com.example.plandeduce.model.CommandInfo;
import com.example.plandeduce.model.ControlPoint;
import com.example.plandeduce.model.FireJudgeResult;
import com.example.plandeduce.model.IndrectFirePlan;
import com.example.plandeduce.model.ProgressQueryContext;
import com.example.plandeduce.model.ProgressRangeQuery;
import com.example.plandeduce.model.ProgressSnapshotQuery;
import com.example.plandeduce.model.RoomObjectHis;
import com.example.plandeduce.model.SkipRenderData;
import com.example.plandeduce.websocket.PlanDeducePush;

import java.util.List;
import java.util.Collections;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** 管理单个会话的播放状态。 */
public class ScenarioTask {
    private final String dbName;
    private final AtomicReference<String> dataSourceKey = new AtomicReference<>();
    private final String sessionId;
    private final ProgressDataService progressDataService;
    private final PlanDeducePush pushService;
    private final PlanDeduceProperties properties;
    private final ScenarioTaskManager taskManager;

    private final AtomicInteger currentTime = new AtomicInteger(0);
    private final AtomicInteger realTime = new AtomicInteger(0);
    private final AtomicInteger speed = new AtomicInteger(1);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean initialized = new AtomicBoolean(false);
    // 标记本轮是否已经发过 INIT。
    private final AtomicBoolean initSnapshotPushed = new AtomicBoolean(false);
    private final AtomicInteger fullSaveIntervalSeconds = new AtomicInteger(600);
    private final AtomicInteger maxSimTime = new AtomicInteger(0);
    // 暂存待处理的跳点时间。
    private final AtomicReference<Integer> pendingSkipTime = new AtomicReference<>();
    private volatile ScheduledFuture<?> future;

    /** 创建任务。 */
    public ScenarioTask(String dbName,
                        String dataSourceKey,
                        String sessionId,
                        ProgressDataService progressDataService,
                        PlanDeducePush pushService,
                        PlanDeduceProperties properties,
                        ScenarioTaskManager taskManager) {
        this.dbName = dbName;
        this.dataSourceKey.set(requireDataSourceKey(dataSourceKey));
        this.sessionId = sessionId;
        this.progressDataService = progressDataService;
        this.pushService = pushService;
        this.properties = properties;
        this.taskManager = taskManager;
        this.speed.set(Math.max(properties.getDefaultSpeed(), 1));
        this.fullSaveIntervalSeconds.set(Math.max(properties.getDefaultFullSaveIntervalSeconds(), 1));
    }

    /** 更新任务使用的数据源标识。 */
    public void updateDataSourceKey(String newDataSourceKey) {
        dataSourceKey.set(requireDataSourceKey(newDataSourceKey));
    }

    /** 初始化任务。 */
    public synchronized void initialize(Integer startTime, Integer intervalSeconds) {
        initialize(startTime, intervalSeconds, null);
    }

    /** 初始化任务并指定最大推演时间。 */
    public synchronized void initialize(Integer startTime, Integer intervalSeconds, Integer knownMaxSimTime) {
        if (startTime != null) {
            int safeStartTime = Math.max(startTime, 0);
            currentTime.set(safeStartTime);
            realTime.set(safeStartTime);
        }
        if (intervalSeconds != null && intervalSeconds > 0) {
            fullSaveIntervalSeconds.set(intervalSeconds);
        }
        initializeRuntimeState(knownMaxSimTime);
        running.set(false);
        initSnapshotPushed.set(false);
    }

    /** 执行一次播放推进。 */
    private synchronized void tickSafely() {
        try {
            if (handlePendingSkip()) {
                return;
            }
            if (!running.get()) {
                return;
            }
            int currentSpeed = speed.get();
            if (currentSpeed <= 0) {
                return;
            }
            int now = currentTime.get();
            int maxSimTimeValue = maxSimTime.get();
            if (now >= maxSimTimeValue) {
                pause();
                pushStatus("FINISH", calculateNearestFullTime(maxSimTimeValue), "推演结束");
                return;
            }
            int next = Math.min(now + currentSpeed, maxSimTimeValue);
            currentTime.set(next);
            realTime.set(Math.min(realTime.get() + 1, maxSimTimeValue));
            pushPlaySnapshot(now, next, currentSpeed);
            if (next >= maxSimTimeValue) {
                pause();
                pushStatus("FINISH", calculateNearestFullTime(maxSimTimeValue), "推演结束");
            }
        } catch (Exception e) {
            pushStatus("ERROR", calculateNearestFullTime(currentTime.get()), e.getMessage());
        }
    }

    /** 登记跳点时间。 */
    public synchronized void skip(Integer targetTime) {
        pendingSkipTime.set(Math.max(targetTime, 0));
    }

    /** 跳点后继续播放。 */
    public synchronized void skipAndResume(Integer targetTime) {
        skip(targetTime);
        if (!running.get()) {
            resume();
        }
    }

    /** 设置倍速。 */
    public void setSpeed(Integer newSpeed) {
        int value = newSpeed == null ? 1 : newSpeed;
        boolean wasRunning = running.get();
        speed.set(Math.max(value, 0));
        if (value == 0) {
            running.set(false);
        } else if (wasRunning) {
            running.set(true);
        }
        pushStatus("SPEED", calculateNearestFullTime(currentTime.get()), "倍速已设置为 " + speed.get());
    }

    /** 设置倍速并按需恢复播放。 */
    public synchronized void setSpeedAndResume(Integer newSpeed) {
        setSpeed(newSpeed);
        if (newSpeed != null && newSpeed > 0 && !running.get()) {
            resume();
        }
    }

    /** 统一的开始暂停入口。 */
    public void startOrStop(Integer flag) {
        if (flag != null && flag == 0) {
            pause();
        } else {
            resume();
        }
    }

    /** 暂停任务。 */
    public void pause() {
        running.set(false);
        speed.compareAndSet(0, 1);
        pushStatus("PAUSE", calculateNearestFullTime(currentTime.get()), "已暂停");
    }

    /** 从当前时间恢复播放。 */
    public void resume() {
        if (speed.get() <= 0) {
            speed.set(1);
        }
        if (!initialized.get()) {
            initializeRuntimeState();
            initSnapshotPushed.set(false);
        }
        if (currentTime.get() >= maxSimTime.get()) {
            resetForReplay();
        }
        if (!initSnapshotPushed.get()) {
            running.set(true);
            pushCurrentIncrementalSnapshot("INIT");
            initSnapshotPushed.set(true);
            ensureWorkerScheduled();
            return;
        }
        running.set(true);
        pushStatus("START", calculateNearestFullTime(currentTime.get()), "已开始");
    }

    /** 设置全量快照间隔。 */
    public synchronized void updateFullSaveInterval(Integer intervalSeconds) {
        if (intervalSeconds == null || intervalSeconds <= 0) {
            throw new IllegalArgumentException("全量保存间隔必须大于 0 秒");
        }
        fullSaveIntervalSeconds.set(intervalSeconds);
        initializeRuntimeState();
        pushCurrentIncrementalSnapshot("INTERVAL");
    }

    /** 销毁任务。 */
    public synchronized void stopAndDestroy() {
        running.set(false);
        if (future != null) {
            future.cancel(false);
        }
        pushStatus("DESTROY", calculateNearestFullTime(currentTime.get()), "任务已销毁");
    }

    /** 计算最近的全量时间点。 */
    private int calculateNearestFullTime(int time) {
        int interval = Math.max(fullSaveIntervalSeconds.get(), 1);
        return (time / interval) * interval;
    }

    /** 推送当前秒增量数据。 */
    private void pushCurrentIncrementalSnapshot(String type) {
        int now = currentTime.get();
        int fullTime = calculateNearestFullTime(now);
        ProgressRangeQuery rangeQuery = new ProgressRangeQuery(dbName, getDataSourceKey(), now - 1, now);
        List<RoomObjectHis> incrementalData = progressDataService.queryIncrementalData(rangeQuery);
        List<FireJudgeResult> eventIncrementalData = progressDataService.queryEventIncrementalData(rangeQuery);
        List<IndrectFirePlan> indrectFirePlanIncrementalData = progressDataService.queryIndrectFirePlanIncrementalData(rangeQuery);
        List<CommandInfo> commandInfoIncrementalData = progressDataService.queryCommandInfoIncrementalData(rangeQuery);
        List<ControlPoint> controlPointIncrementalData = progressDataService.queryControlPointIncrementalData(rangeQuery);
        pushService.pushSnapshot(
                type,
                dbName,
                sessionId,
                realTime.get(),
                now,
                fullTime,
                now - 1,
                speed.get(),
                running.get(),
                maxSimTime.get(),
                Collections.emptyList(),
                incrementalData,
                Collections.emptyList(),
                eventIncrementalData,
                Collections.emptyList(),
                indrectFirePlanIncrementalData,
                Collections.emptyList(),
                commandInfoIncrementalData,
                Collections.emptyList(),
                controlPointIncrementalData
        );
    }

    /** 推送播放区间增量数据。 */
    private void pushPlaySnapshot(int previousTime, int nextTime, int currentSpeed) {
        int fullTime = calculateNearestFullTime(nextTime);
        ProgressRangeQuery dataRangeQuery = new ProgressRangeQuery(dbName, getDataSourceKey(), previousTime, Math.min(previousTime + currentSpeed, nextTime));
        ProgressRangeQuery eventRangeQuery = new ProgressRangeQuery(dbName, getDataSourceKey(), previousTime, nextTime);
        List<RoomObjectHis> incrementalData = progressDataService.queryIncrementalData(dataRangeQuery);
        List<FireJudgeResult> eventIncrementalData = progressDataService.queryEventIncrementalData(eventRangeQuery);
        List<IndrectFirePlan> indrectFirePlanIncrementalData = progressDataService.queryIndrectFirePlanIncrementalData(eventRangeQuery);
        List<CommandInfo> commandInfoIncrementalData = progressDataService.queryCommandInfoIncrementalData(eventRangeQuery);
        List<ControlPoint> controlPointIncrementalData = progressDataService.queryControlPointIncrementalData(eventRangeQuery);
        pushService.pushSnapshot(
                "PLAY",
                dbName,
                sessionId,
                realTime.get(),
                nextTime,
                fullTime,
                previousTime,
                speed.get(),
                running.get(),
                maxSimTime.get(),
                Collections.emptyList(),
                incrementalData,
                Collections.emptyList(),
                eventIncrementalData,
                Collections.emptyList(),
                indrectFirePlanIncrementalData,
                Collections.emptyList(),
                commandInfoIncrementalData,
                Collections.emptyList(),
                controlPointIncrementalData
        );
    }

    /** 推送跳点数据。 */
    private void pushSkipSnapshot() {
        int now = currentTime.get();
        int fullTime = calculateNearestFullTime(now);
        ProgressSnapshotQuery snapshotQuery = new ProgressSnapshotQuery(dbName, getDataSourceKey(), fullSaveIntervalSeconds.get(), fullTime);
        ProgressRangeQuery rangeQuery = new ProgressRangeQuery(dbName, getDataSourceKey(), fullTime, now);
        ProgressRangeQuery replayRangeQuery = new ProgressRangeQuery(dbName, getDataSourceKey(), -1, now);
        ProgressRangeQuery renderRangeQuery = new ProgressRangeQuery(dbName, getDataSourceKey(), now - 1, now);
        List<RoomObjectHis> fullData = progressDataService.queryFullData(snapshotQuery);
        List<RoomObjectHis> incrementalData = progressDataService.querySnapshotIncrementalData(rangeQuery);
        List<FireJudgeResult> eventIncrementalData = progressDataService.queryEventIncrementalData(replayRangeQuery);
        List<IndrectFirePlan> indrectFirePlanIncrementalData = progressDataService.queryIndrectFirePlanIncrementalData(replayRangeQuery);
        List<CommandInfo> commandInfoIncrementalData = progressDataService.queryCommandInfoIncrementalData(replayRangeQuery);
        List<ControlPoint> controlPointFullData = progressDataService.queryControlPointFullData(snapshotQuery);
        List<ControlPoint> controlPointIncrementalData = progressDataService.queryControlPointSnapshotIncrementalData(rangeQuery);
        SkipRenderData skipRenderData = new SkipRenderData();
        skipRenderData.setEventData(progressDataService.queryEventIncrementalData(renderRangeQuery));
        skipRenderData.setCommandInfoData(progressDataService.queryCommandInfoIncrementalData(renderRangeQuery));
        skipRenderData.setControlPointData(progressDataService.queryControlPointIncrementalData(renderRangeQuery));
        pushService.pushSnapshot(
                "SKIP",
                dbName,
                sessionId,
                realTime.get(),
                now,
                fullTime,
                fullTime,
                speed.get(),
                running.get(),
                maxSimTime.get(),
                fullData,
                incrementalData,
                Collections.emptyList(),
                eventIncrementalData,
                Collections.emptyList(),
                indrectFirePlanIncrementalData,
                Collections.emptyList(),
                commandInfoIncrementalData,
                controlPointFullData,
                controlPointIncrementalData,
                skipRenderData
        );
    }

    /** 处理待跳点请求。 */
    private boolean handlePendingSkip() {
        Integer targetTime = pendingSkipTime.getAndSet(null);
        if (targetTime == null) {
            return false;
        }
        currentTime.set(targetTime);
        realTime.set(targetTime);
        pushSkipSnapshot();
        return true;
    }

    /** 启动播放线程。 */
    private void ensureWorkerScheduled() {
        if (future == null || future.isCancelled() || future.isDone()) {
            future = taskManager.getExecutor().scheduleWithFixedDelay(
                    this::tickSafely,
                    properties.getTickIntervalMs(),
                    properties.getTickIntervalMs(),
                    TimeUnit.MILLISECONDS
            );
        }
    }

    /** 判断任务是否正在运行。 */
    public boolean isExecuting() {
        return running.get() && future != null && !future.isCancelled() && !future.isDone();
    }

    /** 初始化运行时状态。 */
    private void initializeRuntimeState() {
        initializeRuntimeState(null);
    }

    /** 初始化运行时状态并指定最大推演时间。 */
    private void initializeRuntimeState(Integer knownMaxSimTime) {
        progressDataService.preloadFullSnapshots(new ProgressSnapshotQuery(dbName, getDataSourceKey(), fullSaveIntervalSeconds.get(), 0));
        Integer resolvedMaxSimTime = knownMaxSimTime != null
                ? knownMaxSimTime
                : progressDataService.queryProgressTimeline(new ProgressQueryContext(dbName, getDataSourceKey())).getEndTime();
        maxSimTime.set(resolvedMaxSimTime == null ? 0 : resolvedMaxSimTime);
        initialized.set(true);
    }

    /** 推送状态类消息。 */
    private void pushStatus(String type, Integer fullTime, String text) {
        pushService.pushStatus(
                type,
                dbName,
                sessionId,
                realTime.get(),
                currentTime.get(),
                fullTime,
                speed.get(),
                running.get(),
                maxSimTime.get(),
                text
        );
    }

    /** 重置重播状态。 */
    private void resetForReplay() {
        currentTime.set(0);
        realTime.set(0);
        running.set(false);
        initSnapshotPushed.set(false);
    }

    /** 读取当前数据源标识。 */
    private String getDataSourceKey() {
        String currentDataSourceKey = dataSourceKey.get();
        return requireDataSourceKey(currentDataSourceKey);
    }

    /** 校验数据源标识。 */
    private String requireDataSourceKey(String currentDataSourceKey) {
        if (currentDataSourceKey == null || currentDataSourceKey.trim().isEmpty()) {
            throw new IllegalArgumentException("dataSourceKey 不能为空");
        }
        return currentDataSourceKey;
    }
}
