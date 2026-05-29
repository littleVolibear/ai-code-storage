package com.example.plandeduce.service;

import com.example.plandeduce.model.IndrectFirePlan;
import com.example.plandeduce.model.ProgressRangeQuery;
import com.example.plandeduce.model.ProgressSnapshotQuery;

import java.util.List;

public interface IndrectFirePlanDataService {
    void preloadSnapshots(ProgressSnapshotQuery snapshotQuery);

    List<IndrectFirePlan> queryFullData(ProgressSnapshotQuery snapshotQuery);

    List<IndrectFirePlan> queryIncrementalData(ProgressRangeQuery rangeQuery);
}
