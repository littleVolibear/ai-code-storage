package com.example.plandeduce.service;

import com.example.plandeduce.config.PlanDeduceProperties;
import com.example.plandeduce.model.FireJudgeResult;
import com.example.plandeduce.model.RoomObjectHis;
import com.example.plandeduce.websocket.PlanDeducePush;

import java.util.List;
import java.util.Collections;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 单个推演任务的运行时状态。
 * dbName 表示 ROOM_INFO 主键 ID；任务实际仍按 sessionId 隔离，
 * 这里封装的是单个前端会话下的播放进度、倍速、暂停状态和快照查询逻辑。
 */
public class ScenarioTask {
    private final String dbName;
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
    // 标记本轮播放是否已经向前端推送过首次 INIT 完整快照，避免暂停恢复时重复初始化。
    private final AtomicBoolean initSnapshotPushed = new AtomicBoolean(false);
    private final AtomicInteger fullSaveIntervalSeconds = new AtomicInteger(600);
    private final AtomicInteger maxSimTime = new AtomicInteger(0);
    // 暂存待跳转的目标秒点，由 tick 线程串行消费，避免 skip 与正常播放推进并发冲突。
    private final AtomicReference<Integer> pendingSkipTime = new AtomicReference<Integer>();
    private volatile ScheduledFuture<?> future;

    public ScenarioTask(String dbName,
                        String sessionId,
                        ProgressDataService progressDataService,
                        PlanDeducePush pushService,
                        PlanDeduceProperties properties,
                        ScenarioTaskManager taskManager) {
        this.dbName = dbName;
        this.sessionId = sessionId;
        this.progressDataService = progressDataService;
        this.pushService = pushService;
        this.properties = properties;
        this.taskManager = taskManager;
        this.speed.set(Math.max(properties.getDefaultSpeed(), 1));
        this.fullSaveIntervalSeconds.set(Math.max(properties.getDefaultFullSaveIntervalSeconds(), 1));
    }

    /**
     * 初始化或重新开始任务。
     * startTime 用于决定从哪个业务秒点开始，intervalSeconds 用于覆盖默认全量快照间隔。
     * 当前实现会预热 0 秒快照，后续整间隔全量点由数据服务按需构造。
     */
    public synchronized void initialize(Integer startTime, Integer intervalSeconds) {
        initialize(startTime, intervalSeconds, null);
    }

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

    /**
     * 单次播放推进。
     * 每个 tick 会先判断是否可播放，再根据倍速推进 currentTime，最后推送新的快照。
     */
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

    /**
     * 跳转到指定秒点，并立即推送该秒点对应的全量+增量快照。
     * 这里仅登记待处理跳点，真正的串行消费和推送由播放线程负责。
     */
    public synchronized void skip(Integer targetTime) {
        pendingSkipTime.set(Math.max(targetTime, 0));
    }

    /**
     * 跳点后强制进入播放态。
     * 如果任务此前尚未开始，会先补 INIT；如果已经暂停过，会先发 START，再由 worker 消费 SKIP。
     */
    public synchronized void skipAndResume(Integer targetTime) {
        skip(targetTime);
        if (!running.get()) {
            resume();
        }
    }

    /**
     * 调整播放倍速。
     * 重要规则：
     * 1. speed=0 等价于暂停；
     * 2. 这里只负责更新倍速和 running 标记，是否自动恢复由外层 setSpeedAndResume 决定；
     * 3. 已播放状态下改倍速，下一帧会按新倍速推进。
     */
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

    /**
     * 调速后的组合入口。
     * 先发 SPEED；当 speed>0 且当前未运行时，再根据初始化状态补 INIT 或 START。
     * 如果当前已在播放，则不会重复进入播放态，只会让后续 tick 按新倍速推进。
     */
    public synchronized void setSpeedAndResume(Integer newSpeed) {
        setSpeed(newSpeed);
        if (newSpeed != null && newSpeed > 0 && !running.get()) {
            resume();
        }
    }

    /**
     * 对外统一的开始/暂停入口。
     * flag=0 表示暂停，其余情况都按开始处理。
     */
    public void startOrStop(Integer flag) {
        if (flag != null && flag == 0) {
            pause();
        } else {
            resume();
        }
    }

    /**
     * 暂停任务。
     * 如果之前是 speed=0 暂停，这里会把 speed 恢复到 1，避免后续 start 后仍保持 0 倍速。
     */
    public void pause() {
        running.set(false);
        speed.compareAndSet(0, 1);
        pushStatus("PAUSE", calculateNearestFullTime(currentTime.get()), "已暂停");
    }

    /**
     * 从当前时间恢复播放。
     * 首次开始会先发 INIT；结束后再次开始会重置到 0 重播。
     */
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
            pushCurrentStateSnapshot("INIT");
            initSnapshotPushed.set(true);
            ensureWorkerScheduled();
            return;
        }
        running.set(true);
        pushStatus("START", calculateNearestFullTime(currentTime.get()), "已开始");
    }

    /**
     * 修改全量快照间隔，并立即返回当前秒点在新规则下的快照结果。
     */
    public synchronized void updateFullSaveInterval(Integer intervalSeconds) {
        if (intervalSeconds == null || intervalSeconds <= 0) {
            throw new IllegalArgumentException("全量保存间隔必须大于 0 秒");
        }
        fullSaveIntervalSeconds.set(intervalSeconds);
        initializeRuntimeState();
        pushCurrentStateSnapshot("INTERVAL");
    }

    /**
     * 销毁任务并取消定时器。
     * destroy 是彻底释放资源，不等价于 pause。
     */
    public synchronized void stopAndDestroy() {
        running.set(false);
        if (future != null) {
            future.cancel(false);
        }
        pushStatus("DESTROY", calculateNearestFullTime(currentTime.get()), "任务已销毁");
    }

    private int calculateNearestFullTime(int time) {
        int interval = Math.max(fullSaveIntervalSeconds.get(), 1);
        return (time / interval) * interval;
    }

    /**
     * 推送完整快照。
     * 当前协议约定：
     * 1. fullData 是最近全量秒点的最终完整状态；
     * 2. incrementalData 不是区间历史全量回放，而是从 fullTime 到当前秒之间每个 roomObjectId 最后一次生效的补丁；
     * 3. 前端拿到 fullData + incrementalData 后，应能还原目标秒点最终状态。
     */
    private void pushCurrentStateSnapshot(String type) {
        int now = currentTime.get();
        int fullTime = calculateNearestFullTime(now);
        List<RoomObjectHis> fullData = progressDataService.queryCachedFullData(dbName, fullSaveIntervalSeconds.get(), fullTime);
        List<RoomObjectHis> incrementalData = progressDataService.querySnapshotIncrementalData(dbName, fullTime, now);
        List<FireJudgeResult> eventFullData = progressDataService.queryEventFullData(dbName, fullSaveIntervalSeconds.get(), fullTime);
        List<FireJudgeResult> eventIncrementalData = progressDataService.queryEventIncrementalData(dbName, fullTime, now);
        List<FireJudgeResult> eventData = mergeEventData(eventFullData, eventIncrementalData);
        pushService.pushSnapshot(
                type,
                dbName,
                sessionId,
                realTime.get(),
                now,
                fullTime,
                speed.get(),
                running.get(),
                maxSimTime.get(),
                fullData,
                incrementalData,
                eventData
        );
    }

    /**
     * 播放推进时的推送规则：
     * 1. 如果 next 正好落在全量间隔点，只发该秒点全量数据；
     * 2. 其他情况下，只发当前步长区间内的数据，即 (previousTime, nextTime]。
     */
    private void pushPlaySnapshot(int previousTime, int nextTime, int currentSpeed) {
        int interval = fullSaveIntervalSeconds.get();
        int fullTime = calculateNearestFullTime(nextTime);
        boolean intervalPoint = nextTime % Math.max(interval, 1) == 0;
        List<RoomObjectHis> fullData = intervalPoint
                ? progressDataService.queryCachedFullData(dbName, interval, nextTime)
                : Collections.emptyList();
        List<RoomObjectHis> incrementalData = intervalPoint
                ? Collections.emptyList()
                : progressDataService.queryIncrementalData(dbName, previousTime, Math.min(previousTime + currentSpeed, nextTime));
        List<FireJudgeResult> eventFullData = intervalPoint
                ? progressDataService.queryEventFullData(dbName, interval, nextTime)
                : Collections.emptyList();
        List<FireJudgeResult> eventIncrementalData = intervalPoint
                ? Collections.emptyList()
                : progressDataService.queryEventIncrementalData(dbName, previousTime, nextTime);
        List<FireJudgeResult> eventData = mergeEventData(eventFullData, eventIncrementalData);
        pushService.pushSnapshot(
                "PLAY",
                dbName,
                sessionId,
                realTime.get(),
                nextTime,
                fullTime,
                speed.get(),
                running.get(),
                maxSimTime.get(),
                fullData,
                incrementalData,
                eventData
        );
    }

    private List<FireJudgeResult> mergeEventData(List<FireJudgeResult> fullData, List<FireJudgeResult> incrementalData) {
        List<FireJudgeResult> merged = new java.util.ArrayList<>(fullData.size() + incrementalData.size());
        merged.addAll(fullData);
        merged.addAll(incrementalData);
        return merged;
    }

    /**
     * 如果存在待处理 skip，就在定时任务线程中先处理跳点，再决定本轮是否继续正常播放。
     * 返回 true 表示本轮 tick 已经消费掉一个 skip，不再继续推进播放时间。
     */
    private boolean handlePendingSkip() {
        Integer targetTime = pendingSkipTime.getAndSet(null);
        if (targetTime == null) {
            return false;
        }
        currentTime.set(targetTime);
        realTime.set(targetTime);
        pushCurrentStateSnapshot("SKIP");
        return true;
    }

    /**
     * 定时任务既负责播放推进，也负责消费 skip 这类需要异步串行发送的数据命令。
     * 当前 worker 只在首次开始播放时拉起；pause / speed=0 只会让它空转，不会真正停掉线程。
     */
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

    public boolean isExecuting() {
        return running.get() && future != null && !future.isCancelled() && !future.isDone();
    }

    public int getMaxSimTime() {
        return maxSimTime.get();
    }

    /**
     * 播放前所需的运行时准备动作。
     * 允许重复调用，用于“先设倍速、后点击开始”这类延迟初始化场景。
     */
    private void initializeRuntimeState() {
        initializeRuntimeState(null);
    }

    private void initializeRuntimeState(Integer knownMaxSimTime) {
        progressDataService.preloadFullSnapshots(dbName, fullSaveIntervalSeconds.get());
        Integer resolvedMaxSimTime = knownMaxSimTime != null
                ? knownMaxSimTime
                : progressDataService.queryProgressTimeline(dbName).getEndTime();
        maxSimTime.set(resolvedMaxSimTime == null ? 0 : resolvedMaxSimTime);
        initialized.set(true);
    }

    /**
     * 推送状态类消息。
     * 这类消息不携带 data，只用于通知前端切换播放状态或显示结束/异常结果。
     */
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

    /**
     * 已播放到终点后再次开始时，重置到起点并沿用当前倍速与间隔配置。
     */
    private void resetForReplay() {
        currentTime.set(0);
        realTime.set(0);
        running.set(false);
        initSnapshotPushed.set(false);
    }

}
