package com.example.plandeduce.service;

import com.example.plandeduce.model.CommandInfo;
import com.example.plandeduce.model.ProgressRangeQuery;
import com.example.plandeduce.model.ProgressSnapshotQuery;

import java.util.List;

/** 定义指令信息数据查询能力。 */
public interface CommandInfoDataService {
    /** 预热基础快照。 */
    void preloadSnapshots(ProgressSnapshotQuery snapshotQuery);

    /** 查询指令信息全量快照。 */
    List<CommandInfo> queryFullData(ProgressSnapshotQuery snapshotQuery);

    /** 查询指令信息增量数据。 */
    List<CommandInfo> queryIncrementalData(ProgressRangeQuery rangeQuery);

    /** 查询指令信息快照补丁。 */
    List<CommandInfo> querySnapshotIncrementalData(ProgressRangeQuery rangeQuery);
}
