package com.example.plandeduce.service;

import com.example.plandeduce.model.ProgressQueryContext;
import com.example.plandeduce.model.ProgressRangeQuery;
import com.example.plandeduce.model.ProgressSnapshotQuery;
import com.example.plandeduce.model.ProgressTimeline;
import com.example.plandeduce.model.CommandInfo;
import com.example.plandeduce.model.ControlPoint;
import com.example.plandeduce.model.FireJudgeResult;
import com.example.plandeduce.model.IndrectFirePlan;
import com.example.plandeduce.model.RoomObjectHis;

import java.util.List;

/** 定义进度条所需的数据查询能力。 */
public interface ProgressDataService {
    /** 查询进度条时间范围。 */
    ProgressTimeline queryProgressTimeline(ProgressQueryContext queryContext);

    /** 预热基础快照。 */
    void preloadFullSnapshots(ProgressSnapshotQuery snapshotQuery);

    /** 查询对象全量快照。 */
    List<RoomObjectHis> queryFullData(ProgressSnapshotQuery snapshotQuery);

    /** 查询对象增量数据。 */
    List<RoomObjectHis> queryIncrementalData(ProgressRangeQuery rangeQuery);

    /** 查询对象快照补丁。 */
    List<RoomObjectHis> querySnapshotIncrementalData(ProgressRangeQuery rangeQuery);

    /** 查询事件增量数据。 */
    List<FireJudgeResult> queryEventIncrementalData(ProgressRangeQuery rangeQuery);

    /** 查询间瞄计划增量数据。 */
    List<IndrectFirePlan> queryIndrectFirePlanIncrementalData(ProgressRangeQuery rangeQuery);

    /** 查询指令信息增量数据。 */
    List<CommandInfo> queryCommandInfoIncrementalData(ProgressRangeQuery rangeQuery);

    /** 查询控制点增量数据。 */
    List<ControlPoint> queryControlPointIncrementalData(ProgressRangeQuery rangeQuery);
}
