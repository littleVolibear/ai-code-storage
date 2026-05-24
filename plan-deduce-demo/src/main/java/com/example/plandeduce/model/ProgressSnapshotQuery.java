package com.example.plandeduce.model;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class ProgressSnapshotQuery {
    private final String dbName;
    private final int intervalSeconds;
    private final int simTime;
}
