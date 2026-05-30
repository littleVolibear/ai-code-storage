package com.example.plandeduce.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.plandeduce.config.DynamicDataSourceContextHolder;
import com.example.plandeduce.mapper.IndrectFirePlanMapper;
import com.example.plandeduce.model.IndrectFirePlan;
import com.example.plandeduce.model.ProgressRangeQuery;
import com.example.plandeduce.model.ProgressSnapshotQuery;
import com.example.plandeduce.service.IndrectFirePlanDataService;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class IndrectFirePlanDataServiceImpl implements IndrectFirePlanDataService {
    private final IndrectFirePlanMapper indrectFirePlanMapper;
    private final Map<String, Map<Integer, Map<Integer, List<IndrectFirePlan>>>> fullSnapshotCache = new ConcurrentHashMap<>();

    /** 注入间瞄计划表访问器。 */
    public IndrectFirePlanDataServiceImpl(IndrectFirePlanMapper indrectFirePlanMapper) {
        this.indrectFirePlanMapper = indrectFirePlanMapper;
    }

    /** 预热间瞄计划 0 秒基础快照。 */
    @Override
    public void preloadSnapshots(ProgressSnapshotQuery snapshotQuery) {
        String dbName = snapshotQuery.getDbName();
        DynamicDataSourceContextHolder.set(dbName);
        try {
            ensureSnapshotCacheInitialized(snapshotQuery);
        } finally {
            DynamicDataSourceContextHolder.clear();
        }
    }

    /** 查询间瞄计划全量快照并返回克隆结果。 */
    @Override
    public List<IndrectFirePlan> queryFullData(ProgressSnapshotQuery snapshotQuery) {
        String dbName = snapshotQuery.getDbName();
        DynamicDataSourceContextHolder.set(dbName);
        try {
            return cloneDataList(getFullSnapshotAtCachePoint(snapshotQuery));
        } finally {
            DynamicDataSourceContextHolder.clear();
        }
    }

    /** 查询间瞄计划区间增量记录。 */
    @Override
    public List<IndrectFirePlan> queryIncrementalData(ProgressRangeQuery rangeQuery) {
        String dbName = rangeQuery.getDbName();
        Integer fromExclusive = rangeQuery.getFromExclusive();
        Integer toInclusive = rangeQuery.getToInclusive();
        DynamicDataSourceContextHolder.set(dbName);
        try {
            if (toInclusive == null || fromExclusive == null || toInclusive <= fromExclusive) {
                return new ArrayList<>();
            }
            return cloneDataList(queryRowsBetween(fromExclusive, toInclusive));
        } finally {
            DynamicDataSourceContextHolder.clear();
        }
    }

    /** 查询快照阶段使用的间瞄计划补丁最终态。 */
    @Override
    public List<IndrectFirePlan> querySnapshotIncrementalData(ProgressRangeQuery rangeQuery) {
        String dbName = rangeQuery.getDbName();
        Integer fromExclusive = rangeQuery.getFromExclusive();
        Integer toInclusive = rangeQuery.getToInclusive();
        DynamicDataSourceContextHolder.set(dbName);
        try {
            if (toInclusive == null || fromExclusive == null || toInclusive <= fromExclusive) {
                return new ArrayList<>();
            }
            return cloneDataList(sortByIfId(new ArrayList<>(indexByIfId(queryRowsBetween(fromExclusive, toInclusive)).values())));
        } finally {
            DynamicDataSourceContextHolder.clear();
        }
    }

    /** 确保指定全量间隔下的间瞄计划快照缓存已初始化。 */
    private void ensureSnapshotCacheInitialized(ProgressSnapshotQuery snapshotQuery) {
        ProgressSnapshotQuery normalizedSnapshotQuery = normalizeSnapshotQuery(snapshotQuery);
        if (normalizedSnapshotQuery.getIntervalSeconds() <= 0) {
            throw new IllegalArgumentException("全量保存间隔必须大于 0 秒");
        }
        Map<Integer, List<IndrectFirePlan>> snapshotCache = getCacheByTime(normalizedSnapshotQuery);
        if (snapshotCache.get(0) == null) {
            snapshotCache.putIfAbsent(0, buildZeroPointSnapshot());
        }
    }

    /** 获取指定秒点的间瞄计划全量快照。 */
    private List<IndrectFirePlan> getFullSnapshotAtCachePoint(ProgressSnapshotQuery snapshotQuery) {
        ProgressSnapshotQuery normalizedSnapshotQuery = normalizeSnapshotQuery(snapshotQuery);
        ensureSnapshotCacheInitialized(normalizedSnapshotQuery);
        Map<Integer, List<IndrectFirePlan>> snapshotCacheByTime = getCacheByTime(normalizedSnapshotQuery);
        int simTime = normalizedSnapshotQuery.getSimTime();
        List<IndrectFirePlan> cachedSnapshot = snapshotCacheByTime.get(simTime);
        if (cachedSnapshot != null) {
            return cachedSnapshot;
        }
        List<IndrectFirePlan> builtSnapshot = buildFullSnapshotAtPoint(normalizedSnapshotQuery);
        List<IndrectFirePlan> existingSnapshot = snapshotCacheByTime.putIfAbsent(simTime, builtSnapshot);
        return existingSnapshot != null ? existingSnapshot : builtSnapshot;
    }

    /** 基于上一个全量点滚动构造当前间瞄计划快照。 */
    private List<IndrectFirePlan> buildFullSnapshotAtPoint(ProgressSnapshotQuery snapshotQuery) {
        ProgressSnapshotQuery normalizedSnapshotQuery = normalizeSnapshotQuery(snapshotQuery);
        int targetTime = normalizedSnapshotQuery.getSimTime();
        if (targetTime == 0) {
            return buildZeroPointSnapshot();
        }
        int interval = Math.max(normalizedSnapshotQuery.getIntervalSeconds(), 1);
        int previousFullTime = Math.max(targetTime - interval, 0);
        ProgressSnapshotQuery previousSnapshotQuery = new ProgressSnapshotQuery(
                normalizedSnapshotQuery.getDbName(),
                normalizedSnapshotQuery.getIntervalSeconds(),
                previousFullTime
        );
        Map<Integer, IndrectFirePlan> mergedRowsByIfId = indexByIfId(getFullSnapshotAtCachePoint(previousSnapshotQuery));
        for (IndrectFirePlan row : queryRowsBetween(previousFullTime, targetTime)) {
            mergedRowsByIfId.put(row.getIfId(), row);
        }
        return sortByIfId(new ArrayList<>(mergedRowsByIfId.values()));
    }

    /** 构造 0 秒间瞄计划快照。 */
    private List<IndrectFirePlan> buildZeroPointSnapshot() {
        return sortByIfId(new ArrayList<>(indexByIfId(queryRowsAtTime(0)).values()));
    }

    /** 查询单秒内的间瞄计划记录。 */
    private List<IndrectFirePlan> queryRowsAtTime(int simTimeValue) {
        int startMillisecond = toMillisecondStart(simTimeValue);
        int endMillisecondExclusive = toMillisecondEndExclusive(simTimeValue);
        LambdaQueryWrapper<IndrectFirePlan> queryWrapper = Wrappers.<IndrectFirePlan>lambdaQuery()
                .ge(IndrectFirePlan::getSimTime, startMillisecond)
                .lt(IndrectFirePlan::getSimTime, endMillisecondExclusive)
                .orderByAsc(IndrectFirePlan::getSimTime)
                .orderByAsc(IndrectFirePlan::getIfId)
                .orderByAsc(IndrectFirePlan::getId);
        return indrectFirePlanMapper.selectList(queryWrapper);
    }

    /** 查询区间内的间瞄计划记录。 */
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

    /** 获取指定库和间隔对应的间瞄计划快照缓存槽位。 */
    private Map<Integer, List<IndrectFirePlan>> getCacheByTime(ProgressSnapshotQuery snapshotQuery) {
        String dbName = snapshotQuery.getDbName();
        int intervalSeconds = snapshotQuery.getIntervalSeconds();
        Map<Integer, Map<Integer, List<IndrectFirePlan>>> cacheByInterval = fullSnapshotCache.get(dbName);
        if (cacheByInterval == null) {
            Map<Integer, Map<Integer, List<IndrectFirePlan>>> newCacheByInterval = new ConcurrentHashMap<>();
            Map<Integer, Map<Integer, List<IndrectFirePlan>>> existingCacheByInterval = fullSnapshotCache.putIfAbsent(dbName, newCacheByInterval);
            cacheByInterval = existingCacheByInterval != null ? existingCacheByInterval : newCacheByInterval;
        }
        Map<Integer, List<IndrectFirePlan>> cacheByTime = cacheByInterval.get(intervalSeconds);
        if (cacheByTime == null) {
            Map<Integer, List<IndrectFirePlan>> newCacheByTime = new ConcurrentHashMap<>();
            Map<Integer, List<IndrectFirePlan>> existingCacheByTime = cacheByInterval.putIfAbsent(intervalSeconds, newCacheByTime);
            cacheByTime = existingCacheByTime != null ? existingCacheByTime : newCacheByTime;
        }
        return cacheByTime;
    }

    /** 按 ifId 建立覆盖索引。 */
    private Map<Integer, IndrectFirePlan> indexByIfId(List<IndrectFirePlan> rows) {
        Map<Integer, IndrectFirePlan> rowsByIfId = new LinkedHashMap<>();
        for (IndrectFirePlan row : rows) {
            rowsByIfId.put(row.getIfId(), row);
        }
        return rowsByIfId;
    }

    /** 按 ifId 排序并过滤空记录。 */
    private List<IndrectFirePlan> sortByIfId(List<IndrectFirePlan> rows) {
        if (rows == null || rows.isEmpty()) {
            return new ArrayList<>();
        }
        List<IndrectFirePlan> filteredRows = new ArrayList<>();
        for (IndrectFirePlan row : rows) {
            if (row != null) {
                filteredRows.add(row);
            }
        }
        filteredRows.sort((left, right) -> compareNullableInteger(left.getIfId(), right.getIfId()));
        return filteredRows;
    }

    /** 克隆间瞄计划结果，避免调用方改动缓存实例。 */
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

    /** 规范化快照查询参数中的秒点范围。 */
    private ProgressSnapshotQuery normalizeSnapshotQuery(ProgressSnapshotQuery snapshotQuery) {
        return new ProgressSnapshotQuery(
                snapshotQuery.getDbName(),
                snapshotQuery.getIntervalSeconds(),
                Math.max(snapshotQuery.getSimTime(), 0)
        );
    }

    /** 将秒点起始值换算为毫秒。 */
    private int toMillisecondStart(int secondValue) {
        return Math.multiplyExact(Math.max(secondValue, 0), 1000);
    }

    /** 将秒点结束边界换算为毫秒开区间。 */
    private int toMillisecondEndExclusive(int secondValue) {
        return Math.multiplyExact(Math.max(secondValue, 0) + 1, 1000);
    }

    /** 对可空整数做统一比较。 */
    private int compareNullableInteger(Integer left, Integer right) {
        if (left == null && right == null) {
            return 0;
        }
        if (left == null) {
            return 1;
        }
        if (right == null) {
            return -1;
        }
        return left.compareTo(right);
    }
}
