package com.example.plandeduce.service;

import com.example.plandeduce.model.CommandInfo;
import com.example.plandeduce.model.ProgressRangeQuery;
import com.example.plandeduce.model.ProgressSnapshotQuery;

import java.util.List;

public interface CommandInfoDataService {
    void preloadSnapshots(ProgressSnapshotQuery snapshotQuery);

    List<CommandInfo> queryFullData(ProgressSnapshotQuery snapshotQuery);

    List<CommandInfo> queryIncrementalData(ProgressRangeQuery rangeQuery);

    List<CommandInfo> querySnapshotIncrementalData(ProgressRangeQuery rangeQuery);
}
