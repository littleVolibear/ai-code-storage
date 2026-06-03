package com.example.plandeduce.service;

import com.example.plandeduce.model.FireJudgeResult;
import com.example.plandeduce.model.ProgressRangeQuery;

import java.util.List;

/** 定义射击裁决数据查询能力。 */
public interface FireJudgeResultDataService {
    /** 查询射击裁决增量数据。 */
    List<FireJudgeResult> queryIncrementalData(ProgressRangeQuery rangeQuery);
}
