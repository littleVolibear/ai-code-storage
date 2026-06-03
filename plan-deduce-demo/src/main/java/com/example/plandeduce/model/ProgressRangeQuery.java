package com.example.plandeduce.model;

import lombok.Getter;

@Getter
public class ProgressRangeQuery {
    private final String dbName;
    private final String dataSourceKey;
    private final Integer fromExclusive;
    private final Integer toInclusive;

    public ProgressRangeQuery(String dbName, Integer fromExclusive, Integer toInclusive) {
        this(dbName, null, fromExclusive, toInclusive);
    }

    public ProgressRangeQuery(String dbName, String dataSourceKey, Integer fromExclusive, Integer toInclusive) {
        this.dbName = dbName;
        this.dataSourceKey = requireDataSourceKey(dataSourceKey);
        this.fromExclusive = fromExclusive;
        this.toInclusive = toInclusive;
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
