package com.example.plandeduce.service;

import com.example.plandeduce.model.ProgressRangeQuery;
import com.example.plandeduce.model.ProgressSnapshotQuery;
import com.example.plandeduce.model.RoomObjectHis;

import java.util.List;

/** 定义对象历史数据查询能力。 */
public interface RoomObjectHisDataService {
    /** 预热基础快照。 */
    void preloadSnapshots(ProgressSnapshotQuery snapshotQuery);

    /** 查询对象全量快照。 */
    List<RoomObjectHis> queryFullData(ProgressSnapshotQuery snapshotQuery);

    /** 查询对象增量数据。 */
    List<RoomObjectHis> queryIncrementalData(ProgressRangeQuery rangeQuery);

    /** 查询对象快照补丁。 */
    List<RoomObjectHis> querySnapshotIncrementalData(ProgressRangeQuery rangeQuery);
}
