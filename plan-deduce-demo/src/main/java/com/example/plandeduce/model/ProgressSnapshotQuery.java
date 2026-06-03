package com.example.plandeduce.model;

import lombok.Getter;

@Getter
public class ProgressSnapshotQuery {
    private final String dbName;
    private final String dataSourceKey;
    private final int intervalSeconds;
    private final int simTime;

    public ProgressSnapshotQuery(String dbName, int intervalSeconds, int simTime) {
        this(dbName, null, intervalSeconds, simTime);
    }

    public ProgressSnapshotQuery(String dbName, String dataSourceKey, int intervalSeconds, int simTime) {
        this.dbName = dbName;
        this.dataSourceKey = requireDataSourceKey(dataSourceKey);
        this.intervalSeconds = intervalSeconds;
        this.simTime = simTime;
    }

    public ProgressQueryContext toQueryContext() {
        return new ProgressQueryContext(dbName, dataSourceKey);
    }

    private String requireDataSourceKey(String dataSourceKey) {
        if (dataSourceKey == null || dataSourceKey.trim().isEmpty()) {
            throw new IllegalArgumentException("dataSourceKey 不能为空");
        }
        return dataSourceKey;
    }
}
