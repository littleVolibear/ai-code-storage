package com.example.plandeduce.service;

import com.example.plandeduce.model.IndrectFirePlan;
import com.example.plandeduce.model.ProgressRangeQuery;
import com.example.plandeduce.model.ProgressSnapshotQuery;

import java.util.List;

/** 定义间瞄计划数据查询能力。 */
public interface IndrectFirePlanDataService {
    /** 预热基础快照。 */
    void preloadSnapshots(ProgressSnapshotQuery snapshotQuery);

    /** 查询间瞄计划全量快照。 */
    List<IndrectFirePlan> queryFullData(ProgressSnapshotQuery snapshotQuery);

    /** 查询间瞄计划增量数据。 */
    List<IndrectFirePlan> queryIncrementalData(ProgressRangeQuery rangeQuery);

    /** 查询间瞄计划快照补丁。 */
    List<IndrectFirePlan> querySnapshotIncrementalData(ProgressRangeQuery rangeQuery);
}
