package com.example.plandeduce.service;

import com.example.plandeduce.model.FireJudgeResult;
import com.example.plandeduce.model.ProgressRangeQuery;
import com.example.plandeduce.model.ProgressSnapshotQuery;

import java.util.List;

/** 定义射击裁决数据查询能力。 */
public interface FireJudgeResultDataService {
    /** 预热基础快照。 */
    void preloadSnapshots(ProgressSnapshotQuery snapshotQuery);

    /** 查询射击裁决全量快照。 */
    List<FireJudgeResult> queryFullData(ProgressSnapshotQuery snapshotQuery);

    /** 查询射击裁决增量数据。 */
    List<FireJudgeResult> queryIncrementalData(ProgressRangeQuery rangeQuery);

    /** 查询射击裁决快照补丁。 */
    List<FireJudgeResult> querySnapshotIncrementalData(ProgressRangeQuery rangeQuery);
}
