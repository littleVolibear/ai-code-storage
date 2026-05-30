package com.example.plandeduce.service;

import com.example.plandeduce.model.ProgressQueryContext;
import com.example.plandeduce.model.ProgressRangeQuery;
import com.example.plandeduce.model.ProgressSnapshotQuery;
import com.example.plandeduce.model.ProgressTimeline;
import com.example.plandeduce.model.CommandInfo;
import com.example.plandeduce.model.FireJudgeResult;
import com.example.plandeduce.model.IndrectFirePlan;
import com.example.plandeduce.model.RoomObjectHis;

import java.util.List;

public interface ProgressDataService {
    /**
     * 查询进度条展示所需的时间范围。
     * startTime 和 endTime 都来自同一条 ROOM_INFO 记录。
     * 其中 ROOM_INFO.totalTime 在库里按分钟存储，对外统一返回秒。
     */
    ProgressTimeline queryProgressTimeline(ProgressQueryContext queryContext);

    /**
     * 预热指定全量间隔下的基础快照缓存。
     * 当前实现只保证 0 点全量快照可用，其他全量点按需生成并缓存。
     */
    void preloadFullSnapshots(ProgressSnapshotQuery snapshotQuery);

    /**
     * 查询某个全量秒点的完整快照。
     * 返回结果优先走缓存，缓存未命中时会现场构造并回填缓存。
     */
    List<RoomObjectHis> queryCachedFullData(ProgressSnapshotQuery snapshotQuery);

    /**
     * 查询播放推进时使用的原始增量数据。
     * 保留区间内所有变化记录，供 PLAY 消息按步长逐帧下发。
     */
    List<RoomObjectHis> queryIncrementalData(ProgressRangeQuery rangeQuery);

    /**
     * 查询快照类消息使用的增量数据。
     * 对同一个 roomObjectId 只保留区间内最后一次生效状态。
     */
    List<RoomObjectHis> querySnapshotIncrementalData(ProgressRangeQuery rangeQuery);

    /**
     * 查询某个全量秒点上的事件数据。
     */
    List<FireJudgeResult> queryEventFullData(ProgressSnapshotQuery snapshotQuery);

    /**
     * 查询事件增量数据，区间语义为 (fromExclusive, toInclusive]。
     */
    List<FireJudgeResult> queryEventIncrementalData(ProgressRangeQuery rangeQuery);

    /**
     * 查询事件快照增量数据。
     * 对同一个 (objId, tarObjId) 只保留区间内最后一次生效状态。
     */
    List<FireJudgeResult> queryEventSnapshotIncrementalData(ProgressRangeQuery rangeQuery);

    /**
     * 查询某个全量秒点上的间瞄计划数据。
     */
    List<IndrectFirePlan> queryIndrectFirePlanFullData(ProgressSnapshotQuery snapshotQuery);

    /**
     * 查询间瞄计划增量数据，区间语义为 (fromExclusive, toInclusive]。
     */
    List<IndrectFirePlan> queryIndrectFirePlanIncrementalData(ProgressRangeQuery rangeQuery);

    /**
     * 查询间瞄计划快照增量数据。
     * 对同一个 ifId 只保留区间内最后一次生效状态。
     */
    List<IndrectFirePlan> queryIndrectFirePlanSnapshotIncrementalData(ProgressRangeQuery rangeQuery);

    /**
     * 查询某个全量秒点上的指令信息数据。
     */
    List<CommandInfo> queryCommandInfoFullData(ProgressSnapshotQuery snapshotQuery);

    /**
     * 查询指令信息增量数据，区间语义为 (fromExclusive, toInclusive]。
     */
    List<CommandInfo> queryCommandInfoIncrementalData(ProgressRangeQuery rangeQuery);

    /**
     * 查询指令信息快照增量数据。
     * 对同一个 objId 只保留区间内最后一次生效状态。
     */
    List<CommandInfo> queryCommandInfoSnapshotIncrementalData(ProgressRangeQuery rangeQuery);
}
