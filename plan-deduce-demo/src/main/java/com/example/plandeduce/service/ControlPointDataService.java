package com.example.plandeduce.service;

import com.example.plandeduce.model.ControlPoint;
import com.example.plandeduce.model.ProgressRangeQuery;
import com.example.plandeduce.model.ProgressSnapshotQuery;

import java.util.List;

/** 定义控制点数据查询能力。 */
public interface ControlPointDataService {
    /** 预热基础快照。 */
    void preloadSnapshots(ProgressSnapshotQuery snapshotQuery);

    /** 查询全量快照。 */
    List<ControlPoint> queryFullData(ProgressSnapshotQuery snapshotQuery);

    /** 查询增量数据。 */
    List<ControlPoint> queryIncrementalData(ProgressRangeQuery rangeQuery);

    /** 查询快照补丁。 */
    List<ControlPoint> querySnapshotIncrementalData(ProgressRangeQuery rangeQuery);
}
