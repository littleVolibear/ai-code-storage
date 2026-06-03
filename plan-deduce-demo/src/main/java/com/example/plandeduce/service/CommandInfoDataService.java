package com.example.plandeduce.service;

import com.example.plandeduce.model.CommandInfo;
import com.example.plandeduce.model.ProgressRangeQuery;

import java.util.List;

/** 定义指令信息数据查询能力。 */
public interface CommandInfoDataService {
    /** 查询指令信息增量数据。 */
    List<CommandInfo> queryIncrementalData(ProgressRangeQuery rangeQuery);
}
