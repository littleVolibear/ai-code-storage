package com.example.plandeduce.service;

import com.example.plandeduce.model.IndrectFirePlan;
import com.example.plandeduce.model.ProgressRangeQuery;

import java.util.List;

/** 定义间瞄计划数据查询能力。 */
public interface IndrectFirePlanDataService {
    /** 查询间瞄计划增量数据。 */
    List<IndrectFirePlan> queryIncrementalData(ProgressRangeQuery rangeQuery);
}
