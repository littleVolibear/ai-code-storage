package com.example.plandeduce.service;

import com.example.plandeduce.model.ProgressRangeQuery;
import com.example.plandeduce.model.ProgressSnapshotQuery;
import com.example.plandeduce.model.RoomObjectHis;

import java.util.List;

public interface RoomObjectHisDataService {
    void preloadSnapshots(ProgressSnapshotQuery snapshotQuery);

    List<RoomObjectHis> queryCachedFullData(ProgressSnapshotQuery snapshotQuery);

    List<RoomObjectHis> queryIncrementalData(ProgressRangeQuery rangeQuery);

    List<RoomObjectHis> querySnapshotIncrementalData(ProgressRangeQuery rangeQuery);
}
