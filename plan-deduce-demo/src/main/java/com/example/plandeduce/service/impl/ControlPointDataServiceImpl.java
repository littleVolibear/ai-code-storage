package com.example.plandeduce.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.plandeduce.config.DynamicDataSourceContextHolder;
import com.example.plandeduce.mapper.ControlPointMapper;
import com.example.plandeduce.mapper.FireJudgeResultMapper;
import com.example.plandeduce.model.ControlPoint;
import com.example.plandeduce.model.FireJudgeResult;
import com.example.plandeduce.model.ProgressRangeQuery;
import com.example.plandeduce.model.ProgressSnapshotQuery;
import com.example.plandeduce.service.ControlPointDataService;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
/** 实现控制点数据查询。 */
public class ControlPointDataServiceImpl implements ControlPointDataService {
    private static final int FIRE_JUDGE_TYPE_CONTROL_POINT = 9;

    private final ControlPointMapper controlPointMapper;
    private final FireJudgeResultMapper fireJudgeResultMapper;
    private final Map<String, Map<Integer, Map<Integer, List<ControlPoint>>>> fullSnapshotCache = new ConcurrentHashMap<>();

    /** 注入依赖。 */
    public ControlPointDataServiceImpl(ControlPointMapper controlPointMapper, FireJudgeResultMapper fireJudgeResultMapper) {
        this.controlPointMapper = controlPointMapper;
        this.fireJudgeResultMapper = fireJudgeResultMapper;
    }

    /** 预热基础快照。 */
    @Override
    public void preloadSnapshots(ProgressSnapshotQuery snapshotQuery) {
        String dataSourceKey = snapshotQuery.getDataSourceKey();
        DynamicDataSourceContextHolder.set(dataSourceKey);
        try {
            ensureSnapshotCacheInitialized(snapshotQuery);
        } finally {
            DynamicDataSourceContextHolder.clear();
        }
    }

    /** 查询控制点全量快照。 */
    @Override
    public List<ControlPoint> queryFullData(ProgressSnapshotQuery snapshotQuery) {
        String dataSourceKey = snapshotQuery.getDataSourceKey();
        DynamicDataSourceContextHolder.set(dataSourceKey);
        try {
            return cloneDataList(getFullSnapshotAtCachePoint(snapshotQuery));
        } finally {
            DynamicDataSourceContextHolder.clear();
        }
    }

    /** 查询控制点增量数据。 */
    @Override
    public List<ControlPoint> queryIncrementalData(ProgressRangeQuery rangeQuery) {
        String dataSourceKey = rangeQuery.getDataSourceKey();
        Integer fromExclusive = rangeQuery.getFromExclusive();
        Integer toInclusive = rangeQuery.getToInclusive();
        DynamicDataSourceContextHolder.set(dataSourceKey);
        try {
            if (toInclusive == null || fromExclusive == null || toInclusive <= fromExclusive) {
                return new ArrayList<>();
            }
            return cloneDataList(queryRowsBetween(dataSourceKey, fromExclusive, toInclusive));
        } finally {
            DynamicDataSourceContextHolder.clear();
        }
    }

    /** 查询控制点快照补丁。 */
    @Override
    public List<ControlPoint> querySnapshotIncrementalData(ProgressRangeQuery rangeQuery) {
        String dataSourceKey = rangeQuery.getDataSourceKey();
        Integer fromExclusive = rangeQuery.getFromExclusive();
        Integer toInclusive = rangeQuery.getToInclusive();
        DynamicDataSourceContextHolder.set(dataSourceKey);
        try {
            if (toInclusive == null || fromExclusive == null || toInclusive <= fromExclusive) {
                return new ArrayList<>();
            }
            return cloneDataList(sortByControlPointId(new ArrayList<>(indexByControlPointId(queryRowsBetween(dataSourceKey, fromExclusive, toInclusive)).values())));
        } finally {
            DynamicDataSourceContextHolder.clear();
        }
    }

    /** 初始化控制点快照缓存。 */
    private void ensureSnapshotCacheInitialized(ProgressSnapshotQuery snapshotQuery) {
        ProgressSnapshotQuery normalizedSnapshotQuery = normalizeSnapshotQuery(snapshotQuery);
        if (normalizedSnapshotQuery.getIntervalSeconds() <= 0) {
            throw new IllegalArgumentException("全量保存间隔必须大于 0 秒");
        }
        Map<Integer, List<ControlPoint>> snapshotCache = getCacheByTime(normalizedSnapshotQuery);
        if (snapshotCache.get(0) == null) {
            snapshotCache.putIfAbsent(0, buildZeroPointSnapshot(normalizedSnapshotQuery.getDataSourceKey()));
        }
    }

    /** 获取控制点全量快照。 */
    private List<ControlPoint> getFullSnapshotAtCachePoint(ProgressSnapshotQuery snapshotQuery) {
        ProgressSnapshotQuery normalizedSnapshotQuery = normalizeSnapshotQuery(snapshotQuery);
        ensureSnapshotCacheInitialized(normalizedSnapshotQuery);
        Map<Integer, List<ControlPoint>> snapshotCacheByTime = getCacheByTime(normalizedSnapshotQuery);
        int simTime = normalizedSnapshotQuery.getSimTime();
        List<ControlPoint> cachedSnapshot = snapshotCacheByTime.get(simTime);
        if (cachedSnapshot != null) {
            return cachedSnapshot;
        }
        List<ControlPoint> builtSnapshot = buildFullSnapshotAtPoint(normalizedSnapshotQuery);
        List<ControlPoint> existingSnapshot = snapshotCacheByTime.putIfAbsent(simTime, builtSnapshot);
        return existingSnapshot != null ? existingSnapshot : builtSnapshot;
    }

    /** 构造控制点全量快照。 */
    private List<ControlPoint> buildFullSnapshotAtPoint(ProgressSnapshotQuery snapshotQuery) {
        String dataSourceKey = snapshotQuery.getDataSourceKey();
        int targetTime = snapshotQuery.getSimTime();
        if (targetTime == 0) {
            return buildZeroPointSnapshot(dataSourceKey);
        }
        int previousFullTime = Math.max(targetTime - Math.max(snapshotQuery.getIntervalSeconds(), 1), 0);
        ProgressSnapshotQuery previousSnapshotQuery = new ProgressSnapshotQuery(
                snapshotQuery.getDbName(),
                dataSourceKey,
                snapshotQuery.getIntervalSeconds(),
                previousFullTime
        );
        Map<Integer, ControlPoint> mergedRowsByControlPointId = indexByControlPointId(getFullSnapshotAtCachePoint(previousSnapshotQuery));
        for (ControlPoint row : queryRowsBetween(dataSourceKey, previousFullTime, targetTime)) {
            if (row != null && row.getControlPointId() != null) {
                mergedRowsByControlPointId.put(row.getControlPointId(), row);
            }
        }
        return sortByControlPointId(new ArrayList<>(mergedRowsByControlPointId.values()));
    }

    /** 构造基础快照。 */
    private List<ControlPoint> buildZeroPointSnapshot(String dbName) {
        return sortByControlPointId(new ArrayList<>(indexByControlPointId(queryRowsAtTime(dbName, 0)).values()));
    }

    /** 查询单秒控制点数据。 */
    private List<ControlPoint> queryRowsAtTime(String dbName, int simTimeValue) {
        return queryRowsByFireJudgeTimeWindow(toMillisecondStart(simTimeValue), toMillisecondEndExclusive(simTimeValue));
    }

    /** 查询区间控制点数据。 */
    private List<ControlPoint> queryRowsBetween(String dbName, int fromExclusive, int toInclusive) {
        return queryRowsByFireJudgeTimeWindow(toMillisecondStart(fromExclusive + 1), toMillisecondEndExclusive(toInclusive));
    }

    /** 根据夺控裁决事件查询控制点。 */
    private List<ControlPoint> queryRowsByFireJudgeTimeWindow(int startMillisecond, int endMillisecondExclusive) {
        List<FireJudgeResult> controlPointEvents = queryControlPointEvents(startMillisecond, endMillisecondExclusive);
        if (controlPointEvents.isEmpty()) {
            return new ArrayList<>();
        }
        Map<Integer, ControlPoint> controlPointsByControlPointId = queryControlPointsByControlPointId(controlPointEvents);
        List<ControlPoint> rows = new ArrayList<>(controlPointEvents.size());
        for (FireJudgeResult event : controlPointEvents) {
            if (event == null || event.getControlPointId() == null) {
                continue;
            }
            ControlPoint source = controlPointsByControlPointId.get(event.getControlPointId());
            if (source == null) {
                continue;
            }
            ControlPoint clone = new ControlPoint();
            BeanUtils.copyProperties(source, clone);
            clone.setSimTime(event.getSimTime());
            rows.add(clone);
        }
        return rows;
    }

    /** 查询时间窗口内的夺控裁决事件。 */
    private List<FireJudgeResult> queryControlPointEvents(int startMillisecond, int endMillisecondExclusive) {
        LambdaQueryWrapper<FireJudgeResult> queryWrapper = Wrappers.<FireJudgeResult>lambdaQuery()
                .eq(FireJudgeResult::getType, FIRE_JUDGE_TYPE_CONTROL_POINT)
                .isNotNull(FireJudgeResult::getControlPointId)
                .ge(FireJudgeResult::getSimTime, startMillisecond)
                .lt(FireJudgeResult::getSimTime, endMillisecondExclusive)
                .orderByAsc(FireJudgeResult::getSimTime)
                .orderByAsc(FireJudgeResult::getControlPointId)
                .orderByAsc(FireJudgeResult::getId);
        return fireJudgeResultMapper.selectList(queryWrapper);
    }

    /** 批量查询控制点主表。 */
    private Map<Integer, ControlPoint> queryControlPointsByControlPointId(List<FireJudgeResult> controlPointEvents) {
        List<Integer> controlPointIds = new ArrayList<>();
        for (FireJudgeResult event : controlPointEvents) {
            if (event != null && event.getControlPointId() != null && !controlPointIds.contains(event.getControlPointId())) {
                controlPointIds.add(event.getControlPointId());
            }
        }
        if (controlPointIds.isEmpty()) {
            return new LinkedHashMap<>();
        }
        LambdaQueryWrapper<ControlPoint> queryWrapper = Wrappers.<ControlPoint>lambdaQuery()
                .in(ControlPoint::getControlPointId, controlPointIds)
                .orderByAsc(ControlPoint::getControlPointId);
        return indexByControlPointId(controlPointMapper.selectList(queryWrapper));
    }

    /** 获取控制点快照缓存。 */
    private Map<Integer, List<ControlPoint>> getCacheByTime(ProgressSnapshotQuery snapshotQuery) {
        Map<Integer, Map<Integer, List<ControlPoint>>> cacheByInterval = fullSnapshotCache.computeIfAbsent(
                snapshotQuery.getDataSourceKey(),
                key -> new ConcurrentHashMap<>()
        );
        return cacheByInterval.computeIfAbsent(snapshotQuery.getIntervalSeconds(), key -> new ConcurrentHashMap<>());
    }

    /** 按 controlPointId 建索引。 */
    private Map<Integer, ControlPoint> indexByControlPointId(List<ControlPoint> rows) {
        Map<Integer, ControlPoint> rowsByControlPointId = new LinkedHashMap<>();
        for (ControlPoint row : rows) {
            if (row != null && row.getControlPointId() != null) {
                rowsByControlPointId.put(row.getControlPointId(), row);
            }
        }
        return rowsByControlPointId;
    }

    /** 按 controlPointId 排序。 */
    private List<ControlPoint> sortByControlPointId(List<ControlPoint> rows) {
        List<ControlPoint> filteredRows = new ArrayList<>();
        for (ControlPoint row : rows) {
            if (row != null) {
                filteredRows.add(row);
            }
        }
        filteredRows.sort((left, right) -> compareNullableInteger(left.getControlPointId(), right.getControlPointId()));
        return filteredRows;
    }

    /** 复制控制点结果数据。 */
    private List<ControlPoint> cloneDataList(List<ControlPoint> dataList) {
        if (dataList == null || dataList.isEmpty()) {
            return new ArrayList<>();
        }
        List<ControlPoint> clones = new ArrayList<>(dataList.size());
        for (ControlPoint item : dataList) {
            if (item == null) {
                continue;
            }
            ControlPoint clone = new ControlPoint();
            BeanUtils.copyProperties(item, clone);
            clones.add(clone);
        }
        return clones;
    }

    /** 规范化快照参数。 */
    private ProgressSnapshotQuery normalizeSnapshotQuery(ProgressSnapshotQuery snapshotQuery) {
        return new ProgressSnapshotQuery(
                snapshotQuery.getDbName(),
                snapshotQuery.getDataSourceKey(),
                snapshotQuery.getIntervalSeconds(),
                Math.max(snapshotQuery.getSimTime(), 0)
        );
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
