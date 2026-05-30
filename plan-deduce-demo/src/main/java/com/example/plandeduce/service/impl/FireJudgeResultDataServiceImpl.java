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

    /** 注入依赖。 */
    public FireJudgeResultDataServiceImpl(FireJudgeResultMapper fireJudgeResultMapper) {
        this.fireJudgeResultMapper = fireJudgeResultMapper;
    }

    /** 预热 0 秒快照。 */
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

    /** 查询射击裁决全量快照。 */
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

    /** 查询射击裁决增量数据。 */
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

    /** 查询射击裁决快照补丁。 */
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

    /** 初始化射击裁决快照缓存。 */
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

    /** 获取射击裁决全量快照。 */
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

    /** 构造射击裁决全量快照。 */
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

    /** 构造 0 秒快照。 */
    private List<FireJudgeResult> buildZeroPointSnapshot() {
        return sortData(new ArrayList<>(indexByEventPair(queryRowsAtTime(0)).values()));
    }

    /** 查询单秒射击裁决记录。 */
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

    /** 查询区间射击裁决记录。 */
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

    /** 获取射击裁决快照缓存。 */
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

    /** 按联合键建索引。 */
    private Map<String, FireJudgeResult> indexByEventPair(List<FireJudgeResult> rows) {
        Map<String, FireJudgeResult> rowsByPair = new LinkedHashMap<>();
        for (FireJudgeResult row : rows) {
            rowsByPair.put(buildEventKey(row), row);
        }
        return rowsByPair;
    }

    /** 按联合键排序。 */
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

    /** 复制射击裁决数据。 */
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

    /** 规范化快照参数。 */
    private ProgressSnapshotQuery normalizeSnapshotQuery(ProgressSnapshotQuery snapshotQuery) {
        return new ProgressSnapshotQuery(
                snapshotQuery.getDbName(),
                snapshotQuery.getIntervalSeconds(),
                Math.max(snapshotQuery.getSimTime(), 0)
        );
    }

    /** 生成联合键。 */
    private String buildEventKey(FireJudgeResult row) {
        return row == null ? "null#null" : row.getObjId() + "#" + row.getTarObjId();
    }

    /** 秒转毫秒起点。 */
    private int toMillisecondStart(int secondValue) {
        return Math.multiplyExact(Math.max(secondValue, 0), 1000);
    }

    /** 秒转毫秒终点。 */
    private int toMillisecondEndExclusive(int secondValue) {
        return Math.multiplyExact(Math.max(secondValue, 0) + 1, 1000);
    }

    /** 比较可空整数。 */
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
