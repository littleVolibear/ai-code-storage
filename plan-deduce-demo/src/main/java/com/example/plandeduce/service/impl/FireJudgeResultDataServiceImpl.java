package com.example.plandeduce.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.plandeduce.config.DynamicDataSourceContextHolder;
import com.example.plandeduce.mapper.FireJudgeResultMapper;
import com.example.plandeduce.model.FireJudgeResult;
import com.example.plandeduce.model.ProgressRangeQuery;
import com.example.plandeduce.model.ProgressSnapshotQuery;
import com.example.plandeduce.service.FireJudgeResultDataService;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class FireJudgeResultDataServiceImpl implements FireJudgeResultDataService {
    private final FireJudgeResultMapper fireJudgeResultMapper;
    private final Map<String, Map<Integer, Map<Integer, List<FireJudgeResult>>>> fullSnapshotCache = new ConcurrentHashMap<>();

    /** 注入射击裁决表访问器。 */
    public FireJudgeResultDataServiceImpl(FireJudgeResultMapper fireJudgeResultMapper) {
        this.fireJudgeResultMapper = fireJudgeResultMapper;
    }

    /** 预热射击裁决 0 秒基础快照。 */
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

    /** 查询射击裁决全量快照并返回克隆结果。 */
    @Override
    public List<FireJudgeResult> queryFullData(ProgressSnapshotQuery snapshotQuery) {
        String dbName = snapshotQuery.getDbName();
        DynamicDataSourceContextHolder.set(dbName);
        try {
            return cloneDataList(getFullSnapshotAtCachePoint(snapshotQuery));
        } finally {
            DynamicDataSourceContextHolder.clear();
        }
    }

    /** 查询射击裁决区间增量记录。 */
    @Override
    public List<FireJudgeResult> queryIncrementalData(ProgressRangeQuery rangeQuery) {
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

    /** 查询快照阶段使用的射击裁决补丁最终态。 */
    @Override
    public List<FireJudgeResult> querySnapshotIncrementalData(ProgressRangeQuery rangeQuery) {
        String dbName = rangeQuery.getDbName();
        Integer fromExclusive = rangeQuery.getFromExclusive();
        Integer toInclusive = rangeQuery.getToInclusive();
        DynamicDataSourceContextHolder.set(dbName);
        try {
            if (toInclusive == null || fromExclusive == null || toInclusive <= fromExclusive) {
                return new ArrayList<>();
            }
            return cloneDataList(sortData(new ArrayList<>(indexByEventPair(queryRowsBetween(fromExclusive, toInclusive)).values())));
        } finally {
            DynamicDataSourceContextHolder.clear();
        }
    }

    /** 确保指定全量间隔下的射击裁决快照缓存已初始化。 */
    private void ensureSnapshotCacheInitialized(ProgressSnapshotQuery snapshotQuery) {
        ProgressSnapshotQuery normalizedSnapshotQuery = normalizeSnapshotQuery(snapshotQuery);
        if (normalizedSnapshotQuery.getIntervalSeconds() <= 0) {
            throw new IllegalArgumentException("全量保存间隔必须大于 0 秒");
        }
        Map<Integer, List<FireJudgeResult>> snapshotCache = getCacheByTime(normalizedSnapshotQuery);
        if (snapshotCache.get(0) == null) {
            snapshotCache.putIfAbsent(0, buildZeroPointSnapshot());
        }
    }

    /** 获取指定秒点的射击裁决全量快照。 */
    private List<FireJudgeResult> getFullSnapshotAtCachePoint(ProgressSnapshotQuery snapshotQuery) {
        ProgressSnapshotQuery normalizedSnapshotQuery = normalizeSnapshotQuery(snapshotQuery);
        ensureSnapshotCacheInitialized(normalizedSnapshotQuery);
        Map<Integer, List<FireJudgeResult>> snapshotCacheByTime = getCacheByTime(normalizedSnapshotQuery);
        int simTime = normalizedSnapshotQuery.getSimTime();
        List<FireJudgeResult> cachedSnapshot = snapshotCacheByTime.get(simTime);
        if (cachedSnapshot != null) {
            return cachedSnapshot;
        }
        List<FireJudgeResult> builtSnapshot = buildFullSnapshotAtPoint(normalizedSnapshotQuery);
        List<FireJudgeResult> existingSnapshot = snapshotCacheByTime.putIfAbsent(simTime, builtSnapshot);
        return existingSnapshot != null ? existingSnapshot : builtSnapshot;
    }

    /** 基于上一个全量点滚动构造当前射击裁决快照。 */
    private List<FireJudgeResult> buildFullSnapshotAtPoint(ProgressSnapshotQuery snapshotQuery) {
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
        Map<String, FireJudgeResult> mergedRowsByPair = indexByEventPair(getFullSnapshotAtCachePoint(previousSnapshotQuery));
        for (FireJudgeResult row : queryRowsBetween(previousFullTime, targetTime)) {
            mergedRowsByPair.put(buildEventKey(row), row);
        }
        return sortData(new ArrayList<>(mergedRowsByPair.values()));
    }

    /** 构造 0 秒射击裁决快照。 */
    private List<FireJudgeResult> buildZeroPointSnapshot() {
        return sortData(new ArrayList<>(indexByEventPair(queryRowsAtTime(0)).values()));
    }

    /** 查询单秒内的射击裁决记录。 */
    private List<FireJudgeResult> queryRowsAtTime(int simTimeValue) {
        int startMillisecond = toMillisecondStart(simTimeValue);
        int endMillisecondExclusive = toMillisecondEndExclusive(simTimeValue);
        LambdaQueryWrapper<FireJudgeResult> queryWrapper = Wrappers.<FireJudgeResult>lambdaQuery()
                .ge(FireJudgeResult::getSimTime, startMillisecond)
                .lt(FireJudgeResult::getSimTime, endMillisecondExclusive)
                .orderByAsc(FireJudgeResult::getSimTime)
                .orderByAsc(FireJudgeResult::getObjId)
                .orderByAsc(FireJudgeResult::getTarObjId)
                .orderByAsc(FireJudgeResult::getId);
        return fireJudgeResultMapper.selectList(queryWrapper);
    }

    /** 查询区间内的射击裁决记录。 */
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

    /** 获取指定库和间隔对应的射击裁决快照缓存槽位。 */
    private Map<Integer, List<FireJudgeResult>> getCacheByTime(ProgressSnapshotQuery snapshotQuery) {
        String dbName = snapshotQuery.getDbName();
        int intervalSeconds = snapshotQuery.getIntervalSeconds();
        Map<Integer, Map<Integer, List<FireJudgeResult>>> cacheByInterval = fullSnapshotCache.get(dbName);
        if (cacheByInterval == null) {
            Map<Integer, Map<Integer, List<FireJudgeResult>>> newCacheByInterval = new ConcurrentHashMap<>();
            Map<Integer, Map<Integer, List<FireJudgeResult>>> existingCacheByInterval = fullSnapshotCache.putIfAbsent(dbName, newCacheByInterval);
            cacheByInterval = existingCacheByInterval != null ? existingCacheByInterval : newCacheByInterval;
        }
        Map<Integer, List<FireJudgeResult>> cacheByTime = cacheByInterval.get(intervalSeconds);
        if (cacheByTime == null) {
            Map<Integer, List<FireJudgeResult>> newCacheByTime = new ConcurrentHashMap<>();
            Map<Integer, List<FireJudgeResult>> existingCacheByTime = cacheByInterval.putIfAbsent(intervalSeconds, newCacheByTime);
            cacheByTime = existingCacheByTime != null ? existingCacheByTime : newCacheByTime;
        }
        return cacheByTime;
    }

    /** 按射击方和目标方联合键建立覆盖索引。 */
    private Map<String, FireJudgeResult> indexByEventPair(List<FireJudgeResult> rows) {
        Map<String, FireJudgeResult> rowsByPair = new LinkedHashMap<>();
        for (FireJudgeResult row : rows) {
            rowsByPair.put(buildEventKey(row), row);
        }
        return rowsByPair;
    }

    /** 按联合键维度排序并过滤空记录。 */
    private List<FireJudgeResult> sortData(List<FireJudgeResult> rows) {
        if (rows == null || rows.isEmpty()) {
            return new ArrayList<>();
        }
        List<FireJudgeResult> filteredRows = new ArrayList<>();
        for (FireJudgeResult row : rows) {
            if (row != null) {
                filteredRows.add(row);
            }
        }
        filteredRows.sort((left, right) -> {
            int compareObjId = compareNullableInteger(left.getObjId(), right.getObjId());
            if (compareObjId != 0) {
                return compareObjId;
            }
            return compareNullableInteger(left.getTarObjId(), right.getTarObjId());
        });
        return filteredRows;
    }

    /** 克隆射击裁决结果，避免调用方改动缓存实例。 */
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

    /** 规范化快照查询参数中的秒点范围。 */
    private ProgressSnapshotQuery normalizeSnapshotQuery(ProgressSnapshotQuery snapshotQuery) {
        return new ProgressSnapshotQuery(
                snapshotQuery.getDbName(),
                snapshotQuery.getIntervalSeconds(),
                Math.max(snapshotQuery.getSimTime(), 0)
        );
    }

    /** 生成射击裁决联合去重键。 */
    private String buildEventKey(FireJudgeResult row) {
        return row == null ? "null#null" : row.getObjId() + "#" + row.getTarObjId();
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
