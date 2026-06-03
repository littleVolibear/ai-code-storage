package com.example.plandeduce.service;

import com.example.plandeduce.model.ControlPoint;
import com.example.plandeduce.model.ProgressRangeQuery;

import java.util.List;

/** 定义控制点数据查询能力。 */
public interface ControlPointDataService {
    /** 查询增量数据。 */
    List<ControlPoint> queryIncrementalData(ProgressRangeQuery rangeQuery);
}
