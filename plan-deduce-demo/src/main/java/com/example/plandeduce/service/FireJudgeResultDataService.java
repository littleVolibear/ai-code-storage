package com.example.plandeduce.service;

import com.example.plandeduce.model.FireJudgeResult;
import com.example.plandeduce.model.ProgressRangeQuery;
import com.example.plandeduce.model.ProgressSnapshotQuery;

import java.util.List;

public interface FireJudgeResultDataService {
    void preloadSnapshots(ProgressSnapshotQuery snapshotQuery);

    List<FireJudgeResult> queryFullData(ProgressSnapshotQuery snapshotQuery);

    List<FireJudgeResult> queryIncrementalData(ProgressRangeQuery rangeQuery);
}
