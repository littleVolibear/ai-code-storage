package com.example.plandeduce.model;

import lombok.Getter;

@Getter
public class ProgressRangeQuery {
    private final String dbName;
    private final String dataSourceKey;
    private final Integer fromExclusive;
    private final Integer toInclusive;

    public ProgressRangeQuery(String dbName, Integer fromExclusive, Integer toInclusive) {
        this(dbName, dbName, fromExclusive, toInclusive);
    }

    public ProgressRangeQuery(String dbName, String dataSourceKey, Integer fromExclusive, Integer toInclusive) {
        this.dbName = dbName;
        this.dataSourceKey = dataSourceKey == null || dataSourceKey.trim().isEmpty() ? dbName : dataSourceKey;
        this.fromExclusive = fromExclusive;
        this.toInclusive = toInclusive;
    }

    public ProgressQueryContext toQueryContext() {
        return new ProgressQueryContext(dbName, dataSourceKey);
    }
}
