package com.example.plandeduce.model;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class ProgressRangeQuery {
    private final String dbName;
    private final Integer fromExclusive;
    private final Integer toInclusive;
}
