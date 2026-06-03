package com.example.plandeduce.model;

import lombok.Getter;

@Getter
public class ProgressQueryContext {
    private final String dbName;
    private final String dataSourceKey;

    public ProgressQueryContext(String dbName) {
        this(dbName, null);
    }

    public ProgressQueryContext(String dbName, String dataSourceKey) {
        this.dbName = dbName;
        this.dataSourceKey = requireDataSourceKey(dataSourceKey);
    }

    private String requireDataSourceKey(String dataSourceKey) {
        if (dataSourceKey == null || dataSourceKey.trim().isEmpty()) {
            throw new IllegalArgumentException("dataSourceKey 不能为空");
        }
        return dataSourceKey;
    }
}
