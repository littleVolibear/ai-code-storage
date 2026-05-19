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
 * 这里封装了一个 dbName + sessionId 组合下的播放进度、倍速、暂停状态和快照查询逻辑。
 * 具体的 WebSocket 消息组装与发送已经下沉到 {@link PlanDeducePush}，避免业务状态机和传输协议耦合。
 */
public class ScenarioTask {
    /**
     * 当前任务绑定的业务库名。
     * 这个值和 sessionId 共同决定“哪一条播放任务”的数据来源与状态隔离边界。
     */
    private final String dbName;
    /**
     * 当前任务绑定的前端会话标识。
     * 所有 WebSocket 推送都会发往这个 session，对同库多会话场景起到隔离作用。
     */
    private final String sessionId;
    /**
     * 底层快照查询服务。
     * 负责按时间点查询全量/增量数据，并处理静态字段补齐、缓存命中等数据层细节。
     */
    private final ProgressDataService progressDataService;
    /**
     * 统一的推送门面。
     * 业务层只关心“现在该推什么”，消息结构拼装和具体发送由它负责。
     */
    private final PlanDeducePush pushService;
    /**
     * 播放任务相关配置。
     * 主要提供默认倍速、默认全量间隔、tick 调度周期等运行参数。
     */
    private final PlanDeduceProperties properties;
    /**
     * 任务管理器。
     * 主要用于获取共享调度线程池，让当前任务可以注册周期性 tick。
     */
    private final ScenarioTaskManager taskManager;

    /**
     * 当前播放到的业务秒点。
     * 注意：
     * 1. 这是整个任务最核心的时间游标；
     * 2. PLAY/SKIP/PAUSE/START 等消息里的 currentTime 都以它为准；
     * 3. 播放中的增量区间通常基于“旧 currentTime -> 新 currentTime”计算。
     */
    private final AtomicInteger currentTime = new AtomicInteger(0);
    /**
     * 当前倍速。
     * 注意：
     * 1. 正整数表示正常播放倍速；
     * 2. 0 在当前协议里等价于暂停；
     * 3. 暂停后改成非 0 不会自动恢复播放，还要结合 running 状态看任务是否真的在推进。
     */
    private final AtomicInteger speed = new AtomicInteger(1);
    /**
     * 当前任务是否处于“允许播放推进”的状态。
     * 注意：
     * 1. running=true 只表示 tick 可以推进，不代表一定马上有数据推送；
     * 2. speed<=0 时即使 running 被错误设成 true，tick 里也会被拦住；
     * 3. START/PAUSE/SPEED 等状态事件都会影响这个值。
     */
    private final AtomicBoolean running = new AtomicBoolean(false);
    /**
     * 当前任务是否已经完成播放所需的运行时初始化。
     * 这里的初始化包括全量快照预加载、最大业务时间查询等启动 worker 前的准备动作。
     */
    private final AtomicBoolean initialized = new AtomicBoolean(false);
    /**
     * 是否已经向前端发送过首次 INIT 快照。
     * sendPlanDeduce 只做初始化，不推数据；第一次真正开始播放时才发送 INIT。
     */
    private final AtomicBoolean initSnapshotPushed = new AtomicBoolean(false);
    /**
     * 当前任务采用的全量快照间隔，单位秒。
     * 这个值直接影响 fullTime 的计算规则，以及“最近全量 + 之后增量”的组装方式。
     */
    private final AtomicInteger fullSaveIntervalSeconds = new AtomicInteger(600);
    /**
     * 当前库可播放到的最大业务秒点。
     * 初始化时查询一次后缓存下来，tick 期间直接使用，避免每一帧都查库。
     */
    private final AtomicInteger maxSimTime = new AtomicInteger(0);
    /**
     * 待处理的跳点请求。
     * 注意：
     * 1. skip 接口线程只负责写入目标秒点，不直接推数据；
     * 2. 真正消费这个值并发 SKIP 快照的是定时任务线程；
     * 3. 这样可以把“跳点”和“正常播放推进”串行化，避免并发推送打乱顺序。
     */
    private final AtomicReference<Integer> pendingSkipTime = new AtomicReference<Integer>();

    /**
     * 当前任务注册到调度线程池里的定时句柄。
     * 用来判断 worker 是否已启动，以及在 destroy 时取消后续 tick。
     */
    private volatile ScheduledFuture<?> future;

    /**
     * 创建任务时只初始化内存状态，不立即启动定时器。
     * 真正开始播放由 {@link #startOrStop(Integer)} 触发。
     */
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
     * 当前初始化协议只要求先准备 0 点全量快照；后续整间隔全量点由数据服务按需构造。
     */
    public synchronized void initialize(Integer startTime, Integer intervalSeconds) {
        if (startTime != null) {
            // 任何外部传入的时间都强制收敛到非负数，避免进度回退到非法区间。
            currentTime.set(Math.max(startTime, 0));
        }
        if (intervalSeconds != null && intervalSeconds > 0) {
            // 初始化时允许前端覆盖默认全量间隔，后续 fullTime 计算都以这个值为准。
            fullSaveIntervalSeconds.set(intervalSeconds);
        }
        initializeRuntimeState();
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
                // 已经抵达终点时不再推进，直接收敛为暂停态并发 FINISH。
                pause();
                pushStatus("FINISH", calculateNearestFullTime(maxSimTimeValue), "推演结束");
                return;
            }
            // 播放推进只按 speed 叠加业务秒数，真实 tick 节奏由 tick-interval-ms 决定。
            int next = Math.min(now + currentSpeed, maxSimTimeValue);
            currentTime.set(next);
            pushPlaySnapshot(now, next, currentSpeed);
            if (next >= maxSimTimeValue) {
                // 最后一帧既要推送最终数据，也要补发结束状态，方便前端切到 finished。
                pause();
                pushStatus("FINISH", calculateNearestFullTime(maxSimTimeValue), "推演结束");
            }
        } catch (Exception e) {
            pushStatus("ERROR", calculateNearestFullTime(currentTime.get()), e.getMessage());
        }
    }

    /**
     * 跳转到指定秒点，并立即推送该秒点对应的全量+增量快照。
     * 当前约定 skip 只会发生在任务已经 start 之后，因此这里不再重复负责拉起 worker。
     */
    public synchronized void skip(Integer targetTime) {
        // skip 只表达“登记一个待消费的跳点命令”，具体由已启动的任务线程串行处理。
        pendingSkipTime.set(Math.max(targetTime, 0));
    }

    /**
     * 调整播放倍速。
     * 重要规则：
     * 1. speed=0 等价于暂停；
     * 2. 已暂停状态下把 speed 改成非 0，只修改倍速，不会自动恢复播放；
     * 3. 已播放状态下改倍速，下一帧会按新倍速推进。
     */
    public void setSpeed(Integer newSpeed) {
        int value = newSpeed == null ? 1 : newSpeed;
        boolean wasRunning = running.get();
        speed.set(Math.max(value, 0));
        if (value == 0) {
            running.set(false);
        } else if (wasRunning) {
            // 只有原本就在播放时，调速后才继续保持播放。
            running.set(true);
        }
        pushStatus("SPEED", calculateNearestFullTime(currentTime.get()), "倍速已设置为 " + speed.get());
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
     * 如果 speed 因异常情况小于等于 0，会先兜底恢复为 1 倍速。
     */
    public void resume() {
        if (speed.get() <= 0) {
            // 恢复播放时不能保留 0 倍速，否则任务会处于 running=true 但永远不推进的假播放态。
            speed.set(1);
        }
        if (!initialized.get()) {
            initializeRuntimeState();
            initSnapshotPushed.set(false);
        }
        if (!initSnapshotPushed.get()) {
            running.set(true);
            // 首次通过“开始”进入播放时，先补一帧 INIT，确保前端先拿到基准快照。
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

    /**
     * 计算当前业务秒点对应的最近全量秒点。
     * 例如 interval=10, time=13，则返回 10。
     */
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
        // fullData 只取最近全量秒点最终态，incrementalData 只取该全量点之后每个对象最后一次生效补丁。
        List<RoomObjectHis> fullData = progressDataService.queryCachedFullData(dbName, fullSaveIntervalSeconds.get(), fullTime);
        List<RoomObjectHis> incrementalData = progressDataService.querySnapshotIncrementalData(dbName, fullTime, now);
        List<FireJudgeResult> eventFullData = progressDataService.queryEventFullData(dbName, fullSaveIntervalSeconds.get(), fullTime);
        List<FireJudgeResult> eventIncrementalData = progressDataService.queryEventIncrementalData(dbName, fullTime, now);
        List<FireJudgeResult> eventData = mergeEventData(eventFullData, eventIncrementalData);
        // 业务层只负责提供快照原材料，具体的消息协议拼装和发送交给统一推送门面。
        pushService.pushSnapshot(
                type,
                dbName,
                sessionId,
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
     * 2. 其他情况下，只发当前步长内的增量数据，即 (previousTime, nextTime]。
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
        pushCurrentStateSnapshot("SKIP");
        return true;
    }

    /**
     * 定时任务既负责播放推进，也负责消费 skip 这类需要异步串行发送的数据命令。
     * 当前 worker 只由 start() 拉起；pause / speed=0 只会让它空转，不会真正停掉线程。
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

    /**
     * 判断当前任务是否仍处于播放执行态。
     * 初始化接口会用它拦截重复启动，避免同一个任务在播放中被重新初始化打乱状态。
     */
    public boolean isExecuting() {
        return running.get() && future != null && !future.isCancelled() && !future.isDone();
    }

    /**
     * 返回当前任务已缓存的最大业务秒点。
     * 供初始化接口直接回给前端作为进度条结束时间。
     */
    public int getMaxSimTime() {
        return maxSimTime.get();
    }

    /**
     * 播放前所需的运行时准备动作。
     * 允许重复调用，用于“先设倍速、后点击开始”这类延迟初始化场景。
     */
    private void initializeRuntimeState() {
        progressDataService.preloadFullSnapshots(dbName, fullSaveIntervalSeconds.get());
        maxSimTime.set(progressDataService.queryMaxSimTime(dbName));
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
                currentTime.get(),
                fullTime,
                speed.get(),
                running.get(),
                maxSimTime.get(),
                text
        );
    }

}
