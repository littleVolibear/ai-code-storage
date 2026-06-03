package com.example.plandeduce.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.plandeduce.config.DynamicDataSourceContextHolder;
import com.example.plandeduce.mapper.FireJudgeResultMapper;
import com.example.plandeduce.model.FireJudgeResult;
import com.example.plandeduce.model.ProgressRangeQuery;
import com.example.plandeduce.service.FireJudgeResultDataService;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
/** 实现射击裁决数据查询。 */
public class FireJudgeResultDataServiceImpl implements FireJudgeResultDataService {
    private final FireJudgeResultMapper fireJudgeResultMapper;

    /** 注入依赖。 */
    public FireJudgeResultDataServiceImpl(FireJudgeResultMapper fireJudgeResultMapper) {
        this.fireJudgeResultMapper = fireJudgeResultMapper;
    }

    /** 查询射击裁决增量数据。 */
    @Override
    public List<FireJudgeResult> queryIncrementalData(ProgressRangeQuery rangeQuery) {
        String dataSourceKey = rangeQuery.getDataSourceKey();
        Integer fromExclusive = rangeQuery.getFromExclusive();
        Integer toInclusive = rangeQuery.getToInclusive();
        DynamicDataSourceContextHolder.set(dataSourceKey);
        try {
            if (toInclusive == null || fromExclusive == null || toInclusive <= fromExclusive) {
                return new ArrayList<>();
            }
            return cloneDataList(queryRowsBetween(fromExclusive, toInclusive));
        } finally {
            DynamicDataSourceContextHolder.clear();
        }
    }

    /** 查询区间射击裁决数据。 */
    private List<FireJudgeResult> queryRowsBetween(int fromExclusive, int toInclusive) {
        int startMillisecond = toMillisecondStart(fromExclusive + 1);
        int endMillisecondExclusive = toMillisecondEndExclusive(toInclusive);
        LambdaQueryWrapper<FireJudgeResult> queryWrapper = Wrappers.<FireJudgeResult>lambdaQuery()
                .ge(FireJudgeResult::getSimTime, startMillisecond)
                .lt(FireJudgeResult::getSimTime, endMillisecondExclusive)
                .orderByAsc(FireJudgeResult::getSimTime)
                .orderByAsc(FireJudgeResult::getObjId)
                .orderByAsc(FireJudgeResult::getTarObjId)
                .orderByAsc(FireJudgeResult::getId);
        return fireJudgeResultMapper.selectList(queryWrapper);
    }

    /** 复制射击裁决结果数据。 */
    private List<FireJudgeResult> cloneDataList(List<FireJudgeResult> dataList) {
        if (dataList == null || dataList.isEmpty()) {
            return new ArrayList<>();
        }
        List<FireJudgeResult> clones = new ArrayList<>(dataList.size());
        for (FireJudgeResult item : dataList) {
            if (item == null) {
                continue;
            }
            FireJudgeResult clone = new FireJudgeResult();
            BeanUtils.copyProperties(item, clone);
            clones.add(clone);
        }
        return clones;
    }

    /** 秒转毫秒起点。 */
    private int toMillisecondStart(int secondValue) {
        return Math.multiplyExact(Math.max(secondValue, 0), 1000);
    }

    /** 秒转毫秒终点。 */
    private int toMillisecondEndExclusive(int secondValue) {
        return Math.multiplyExact(Math.max(secondValue, 0) + 1, 1000);
    }

}
