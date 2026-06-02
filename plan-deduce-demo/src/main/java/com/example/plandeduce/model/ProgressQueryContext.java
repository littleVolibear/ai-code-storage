package com.example.plandeduce.model;

import lombok.Getter;

@Getter
public class ProgressQueryContext {
    private final String dbName;
    private final String dataSourceKey;

    public ProgressQueryContext(String dbName) {
        this(dbName, dbName);
    }

    public ProgressQueryContext(String dbName, String dataSourceKey) {
        this.dbName = dbName;
        this.dataSourceKey = dataSourceKey == null || dataSourceKey.trim().isEmpty() ? dbName : dataSourceKey;
    }
}
