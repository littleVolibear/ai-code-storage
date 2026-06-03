package com.example.plandeduce.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.plandeduce.config.DynamicDataSourceContextHolder;
import com.example.plandeduce.mapper.IndrectFirePlanMapper;
import com.example.plandeduce.model.IndrectFirePlan;
import com.example.plandeduce.model.ProgressRangeQuery;
import com.example.plandeduce.service.IndrectFirePlanDataService;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
/** 实现间瞄计划数据查询。 */
public class IndrectFirePlanDataServiceImpl implements IndrectFirePlanDataService {
    private final IndrectFirePlanMapper indrectFirePlanMapper;

    /** 注入依赖。 */
    public IndrectFirePlanDataServiceImpl(IndrectFirePlanMapper indrectFirePlanMapper) {
        this.indrectFirePlanMapper = indrectFirePlanMapper;
    }

    /** 查询间瞄计划增量数据。 */
    @Override
    public List<IndrectFirePlan> queryIncrementalData(ProgressRangeQuery rangeQuery) {
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

    /** 查询区间间瞄计划数据。 */
    private List<IndrectFirePlan> queryRowsBetween(int fromExclusive, int toInclusive) {
        int startMillisecond = toMillisecondStart(fromExclusive + 1);
        int endMillisecondExclusive = toMillisecondEndExclusive(toInclusive);
        LambdaQueryWrapper<IndrectFirePlan> queryWrapper = Wrappers.<IndrectFirePlan>lambdaQuery()
                .ge(IndrectFirePlan::getSimTime, startMillisecond)
                .lt(IndrectFirePlan::getSimTime, endMillisecondExclusive)
                .orderByAsc(IndrectFirePlan::getSimTime)
                .orderByAsc(IndrectFirePlan::getIfId)
                .orderByAsc(IndrectFirePlan::getId);
        return indrectFirePlanMapper.selectList(queryWrapper);
    }

    /** 复制间瞄计划结果数据。 */
    private List<IndrectFirePlan> cloneDataList(List<IndrectFirePlan> dataList) {
        if (dataList == null || dataList.isEmpty()) {
            return new ArrayList<>();
        }
        List<IndrectFirePlan> clones = new ArrayList<>(dataList.size());
        for (IndrectFirePlan item : dataList) {
            if (item == null) {
                continue;
            }
            IndrectFirePlan clone = new IndrectFirePlan();
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
