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
    /** 查询进度条时间范围。 */
    ProgressTimeline queryProgressTimeline(ProgressQueryContext queryContext);

    /** 预热基础快照。 */
    void preloadFullSnapshots(ProgressSnapshotQuery snapshotQuery);

    /** 查询对象全量快照。 */
    List<RoomObjectHis> queryCachedFullData(ProgressSnapshotQuery snapshotQuery);

    /** 查询对象增量数据。 */
    List<RoomObjectHis> queryIncrementalData(ProgressRangeQuery rangeQuery);

    /** 查询对象快照补丁。 */
    List<RoomObjectHis> querySnapshotIncrementalData(ProgressRangeQuery rangeQuery);

    /** 查询事件全量快照。 */
    List<FireJudgeResult> queryEventFullData(ProgressSnapshotQuery snapshotQuery);

    /** 查询事件增量数据。 */
    List<FireJudgeResult> queryEventIncrementalData(ProgressRangeQuery rangeQuery);

    /** 查询事件快照补丁。 */
    List<FireJudgeResult> queryEventSnapshotIncrementalData(ProgressRangeQuery rangeQuery);

    /** 查询间瞄计划全量快照。 */
    List<IndrectFirePlan> queryIndrectFirePlanFullData(ProgressSnapshotQuery snapshotQuery);

    /** 查询间瞄计划增量数据。 */
    List<IndrectFirePlan> queryIndrectFirePlanIncrementalData(ProgressRangeQuery rangeQuery);

    /** 查询间瞄计划快照补丁。 */
    List<IndrectFirePlan> queryIndrectFirePlanSnapshotIncrementalData(ProgressRangeQuery rangeQuery);

    /** 查询指令信息全量快照。 */
    List<CommandInfo> queryCommandInfoFullData(ProgressSnapshotQuery snapshotQuery);

    /** 查询指令信息增量数据。 */
    List<CommandInfo> queryCommandInfoIncrementalData(ProgressRangeQuery rangeQuery);

    /** 查询指令信息快照补丁。 */
    List<CommandInfo> queryCommandInfoSnapshotIncrementalData(ProgressRangeQuery rangeQuery);
}
