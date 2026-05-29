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

    /** 注入对象历史表访问器。 */
    public RoomObjectHisDataServiceImpl(RoomObjectHisMapper roomObjectMapper) {
        this.roomObjectMapper = roomObjectMapper;
    }

    /** 预热对象 0 秒基础快照。 */
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

    /** 查询对象全量快照并返回克隆结果。 */
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

    /** 查询播放阶段使用的对象增量最终态。 */
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

    /** 查询快照阶段使用的对象补丁最终态。 */
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

    /** 确保指定全量间隔下的对象快照缓存已初始化。 */
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

    /** 获取指定秒点的对象全量快照。 */
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

    /** 基于上一个全量点滚动构造当前对象快照。 */
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

    /** 构造 0 秒对象快照。 */
    private List<RoomObjectHis> buildZeroPointSnapshot() {
        return markSourceType(sortByRoomObjectId(queryRowsAtTime(0)), SOURCE_TYPE_FULL);
    }

    /** 查询单秒内的对象历史记录。 */
    private List<RoomObjectHis> queryRowsAtTime(int simTime) {
        int startMillisecond = toMillisecondStart(simTime);
        int endMillisecondExclusive = toMillisecondEndExclusive(simTime);
        LambdaQueryWrapper<RoomObjectHis> queryWrapper = Wrappers.<RoomObjectHis>lambdaQuery()
                .ge(RoomObjectHis::getSimTime, startMillisecond)
                .lt(RoomObjectHis::getSimTime, endMillisecondExclusive)
                .orderByAsc(RoomObjectHis::getRoomObjectId);
        return roomObjectMapper.selectList(queryWrapper);
    }

    /** 查询区间内的对象历史记录。 */
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

    /** 提取区间内每个对象最后一次生效记录。 */
    private List<RoomObjectHis> queryLatestRowsByRoomObjectId(int fromExclusive, int toInclusive) {
        Map<Integer, RoomObjectHis> latestRowsByObjectId = new LinkedHashMap<>();
        for (RoomObjectHis row : queryRowsBetween(fromExclusive, toInclusive)) {
            latestRowsByObjectId.put(row.getRoomObjectId(), row);
        }
        return sortByRoomObjectId(new ArrayList<>(latestRowsByObjectId.values()));
    }

    /** 获取指定库和间隔对应的对象快照缓存槽位。 */
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

    /** 按对象 ID 建立覆盖索引。 */
    private Map<Integer, RoomObjectHis> indexByRoomObjectId(List<RoomObjectHis> rows) {
        Map<Integer, RoomObjectHis> rowsByObjectId = new LinkedHashMap<>();
        for (RoomObjectHis row : rows) {
            rowsByObjectId.put(row.getRoomObjectId(), row);
        }
        return rowsByObjectId;
    }

    /** 按对象 ID 排序并过滤空记录。 */
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

    /** 为对象结果打上全量或增量来源标记。 */
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

    /** 克隆对象结果，避免调用方改动缓存实例。 */
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
