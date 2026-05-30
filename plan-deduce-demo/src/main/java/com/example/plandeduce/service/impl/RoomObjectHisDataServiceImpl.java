package com.example.plandeduce.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.plandeduce.config.DynamicDataSourceContextHolder;
import com.example.plandeduce.mapper.RoomObjectHisMapper;
import com.example.plandeduce.model.ProgressRangeQuery;
import com.example.plandeduce.model.ProgressSnapshotQuery;
import com.example.plandeduce.model.RoomObjectHis;
import com.example.plandeduce.service.RoomObjectHisDataService;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class RoomObjectHisDataServiceImpl implements RoomObjectHisDataService {
    private static final String SOURCE_TYPE_FULL = "FULL";
    private static final String SOURCE_TYPE_INCREMENT = "INCREMENT";

    private final RoomObjectHisMapper roomObjectMapper;
    private final Map<String, Map<Integer, Map<Integer, List<RoomObjectHis>>>> fullSnapshotCache = new ConcurrentHashMap<>();

    /** 注入依赖。 */
    public RoomObjectHisDataServiceImpl(RoomObjectHisMapper roomObjectMapper) {
        this.roomObjectMapper = roomObjectMapper;
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

    /** 查询对象全量快照。 */
    @Override
    public List<RoomObjectHis> queryCachedFullData(ProgressSnapshotQuery snapshotQuery) {
        String dbName = snapshotQuery.getDbName();
        DynamicDataSourceContextHolder.set(dbName);
        try {
            return cloneDataList(getFullSnapshotAtCachePoint(snapshotQuery), SOURCE_TYPE_FULL);
        } finally {
            DynamicDataSourceContextHolder.clear();
        }
    }

    /** 查询对象增量数据。 */
    @Override
    public List<RoomObjectHis> queryIncrementalData(ProgressRangeQuery rangeQuery) {
        String dbName = rangeQuery.getDbName();
        Integer fromExclusive = rangeQuery.getFromExclusive();
        Integer toInclusive = rangeQuery.getToInclusive();
        DynamicDataSourceContextHolder.set(dbName);
        try {
            if (toInclusive == null || fromExclusive == null || toInclusive <= fromExclusive) {
                return new ArrayList<>();
            }
            return cloneDataList(queryLatestRowsByRoomObjectId(fromExclusive, toInclusive), SOURCE_TYPE_INCREMENT);
        } finally {
            DynamicDataSourceContextHolder.clear();
        }
    }

    /** 查询对象快照补丁。 */
    @Override
    public List<RoomObjectHis> querySnapshotIncrementalData(ProgressRangeQuery rangeQuery) {
        String dbName = rangeQuery.getDbName();
        Integer fromExclusive = rangeQuery.getFromExclusive();
        Integer toInclusive = rangeQuery.getToInclusive();
        DynamicDataSourceContextHolder.set(dbName);
        try {
            if (toInclusive == null || fromExclusive == null || toInclusive <= fromExclusive) {
                return new ArrayList<>();
            }
            Map<Integer, RoomObjectHis> latestRowsByObjectId = new LinkedHashMap<>();
            for (RoomObjectHis row : queryRowsBetween(fromExclusive, toInclusive)) {
                latestRowsByObjectId.put(row.getRoomObjectId(), row);
            }
            return cloneDataList(sortByRoomObjectId(new ArrayList<>(latestRowsByObjectId.values())), SOURCE_TYPE_INCREMENT);
        } finally {
            DynamicDataSourceContextHolder.clear();
        }
    }

    /** 初始化对象快照缓存。 */
    private void ensureSnapshotCacheInitialized(ProgressSnapshotQuery snapshotQuery) {
        ProgressSnapshotQuery normalizedSnapshotQuery = normalizeSnapshotQuery(snapshotQuery);
        if (normalizedSnapshotQuery.getIntervalSeconds() <= 0) {
            throw new IllegalArgumentException("全量保存间隔必须大于 0 秒");
        }
        Map<Integer, List<RoomObjectHis>> snapshotCache = getCacheByTime(normalizedSnapshotQuery);
        if (snapshotCache.get(0) == null) {
            snapshotCache.putIfAbsent(0, buildZeroPointSnapshot());
        }
    }

    /** 获取对象全量快照。 */
    private List<RoomObjectHis> getFullSnapshotAtCachePoint(ProgressSnapshotQuery snapshotQuery) {
        ProgressSnapshotQuery normalizedSnapshotQuery = normalizeSnapshotQuery(snapshotQuery);
        ensureSnapshotCacheInitialized(normalizedSnapshotQuery);
        Map<Integer, List<RoomObjectHis>> snapshotCacheByTime = getCacheByTime(normalizedSnapshotQuery);
        int simTime = normalizedSnapshotQuery.getSimTime();
        List<RoomObjectHis> cachedSnapshot = snapshotCacheByTime.get(simTime);
        if (cachedSnapshot != null) {
            return cachedSnapshot;
        }
        List<RoomObjectHis> builtSnapshot = buildFullSnapshotAtPoint(normalizedSnapshotQuery);
        List<RoomObjectHis> existingSnapshot = snapshotCacheByTime.putIfAbsent(simTime, builtSnapshot);
        return existingSnapshot != null ? existingSnapshot : builtSnapshot;
    }

    /** 构造对象全量快照。 */
    private List<RoomObjectHis> buildFullSnapshotAtPoint(ProgressSnapshotQuery snapshotQuery) {
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
        Map<Integer, RoomObjectHis> mergedRowsByObjectId = indexByRoomObjectId(getFullSnapshotAtCachePoint(previousSnapshotQuery));
        for (RoomObjectHis latestRow : queryLatestRowsByRoomObjectId(previousFullTime, targetTime)) {
            mergedRowsByObjectId.put(latestRow.getRoomObjectId(), latestRow);
        }
        return markSourceType(sortByRoomObjectId(new ArrayList<>(mergedRowsByObjectId.values())), SOURCE_TYPE_FULL);
    }

    /** 构造 0 秒快照。 */
    private List<RoomObjectHis> buildZeroPointSnapshot() {
        return markSourceType(sortByRoomObjectId(queryRowsAtTime(0)), SOURCE_TYPE_FULL);
    }

    /** 查询单秒对象记录。 */
    private List<RoomObjectHis> queryRowsAtTime(int simTime) {
        int startMillisecond = toMillisecondStart(simTime);
        int endMillisecondExclusive = toMillisecondEndExclusive(simTime);
        LambdaQueryWrapper<RoomObjectHis> queryWrapper = Wrappers.<RoomObjectHis>lambdaQuery()
                .ge(RoomObjectHis::getSimTime, startMillisecond)
                .lt(RoomObjectHis::getSimTime, endMillisecondExclusive)
                .orderByAsc(RoomObjectHis::getRoomObjectId);
        return roomObjectMapper.selectList(queryWrapper);
    }

    /** 查询区间对象记录。 */
    private List<RoomObjectHis> queryRowsBetween(int fromExclusive, int toInclusive) {
        int startMillisecond = toMillisecondStart(fromExclusive + 1);
        int endMillisecondExclusive = toMillisecondEndExclusive(toInclusive);
        LambdaQueryWrapper<RoomObjectHis> queryWrapper = Wrappers.<RoomObjectHis>lambdaQuery()
                .ge(RoomObjectHis::getSimTime, startMillisecond)
                .lt(RoomObjectHis::getSimTime, endMillisecondExclusive)
                .orderByAsc(RoomObjectHis::getSimTime)
                .orderByAsc(RoomObjectHis::getRoomObjectId);
        return roomObjectMapper.selectList(queryWrapper);
    }

    /** 提取对象最后一条记录。 */
    private List<RoomObjectHis> queryLatestRowsByRoomObjectId(int fromExclusive, int toInclusive) {
        Map<Integer, RoomObjectHis> latestRowsByObjectId = new LinkedHashMap<>();
        for (RoomObjectHis row : queryRowsBetween(fromExclusive, toInclusive)) {
            latestRowsByObjectId.put(row.getRoomObjectId(), row);
        }
        return sortByRoomObjectId(new ArrayList<>(latestRowsByObjectId.values()));
    }

    /** 获取对象快照缓存。 */
    private Map<Integer, List<RoomObjectHis>> getCacheByTime(ProgressSnapshotQuery snapshotQuery) {
        String dbName = snapshotQuery.getDbName();
        int intervalSeconds = snapshotQuery.getIntervalSeconds();
        Map<Integer, Map<Integer, List<RoomObjectHis>>> cacheByInterval = fullSnapshotCache.get(dbName);
        if (cacheByInterval == null) {
            Map<Integer, Map<Integer, List<RoomObjectHis>>> newCacheByInterval = new ConcurrentHashMap<>();
            Map<Integer, Map<Integer, List<RoomObjectHis>>> existingCacheByInterval = fullSnapshotCache.putIfAbsent(dbName, newCacheByInterval);
            cacheByInterval = existingCacheByInterval != null ? existingCacheByInterval : newCacheByInterval;
        }
        Map<Integer, List<RoomObjectHis>> cacheByTime = cacheByInterval.get(intervalSeconds);
        if (cacheByTime == null) {
            Map<Integer, List<RoomObjectHis>> newCacheByTime = new ConcurrentHashMap<>();
            Map<Integer, List<RoomObjectHis>> existingCacheByTime = cacheByInterval.putIfAbsent(intervalSeconds, newCacheByTime);
            cacheByTime = existingCacheByTime != null ? existingCacheByTime : newCacheByTime;
        }
        return cacheByTime;
    }

    /** 按对象 ID 建索引。 */
    private Map<Integer, RoomObjectHis> indexByRoomObjectId(List<RoomObjectHis> rows) {
        Map<Integer, RoomObjectHis> rowsByObjectId = new LinkedHashMap<>();
        for (RoomObjectHis row : rows) {
            rowsByObjectId.put(row.getRoomObjectId(), row);
        }
        return rowsByObjectId;
    }

    /** 按对象 ID 排序。 */
    private List<RoomObjectHis> sortByRoomObjectId(List<RoomObjectHis> rows) {
        if (rows == null || rows.isEmpty()) {
            return new ArrayList<>();
        }
        List<RoomObjectHis> filteredRows = new ArrayList<>();
        for (RoomObjectHis row : rows) {
            if (row != null) {
                filteredRows.add(row);
            }
        }
        filteredRows.sort((left, right) -> compareNullableInteger(left.getRoomObjectId(), right.getRoomObjectId()));
        return filteredRows;
    }

    /** 设置来源标记。 */
    private List<RoomObjectHis> markSourceType(List<RoomObjectHis> rows, String sourceType) {
        if (rows == null || rows.isEmpty()) {
            return new ArrayList<>();
        }
        for (RoomObjectHis row : rows) {
            if (row != null) {
                row.setSourceType(sourceType);
            }
        }
        return rows;
    }

    /** 复制对象数据。 */
    private List<RoomObjectHis> cloneDataList(List<RoomObjectHis> dataList, String sourceType) {
        if (dataList == null || dataList.isEmpty()) {
            return new ArrayList<>();
        }
        List<RoomObjectHis> clones = new ArrayList<>(dataList.size());
        for (RoomObjectHis item : dataList) {
            if (item == null) {
                continue;
            }
            RoomObjectHis clone = new RoomObjectHis();
            BeanUtils.copyProperties(item, clone);
            clone.setSourceType(sourceType);
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
