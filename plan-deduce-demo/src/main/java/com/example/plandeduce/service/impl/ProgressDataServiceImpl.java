package com.example.plandeduce.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import cn.hutool.core.date.DatePattern;
import cn.hutool.core.date.DateUtil;
import com.example.plandeduce.config.DynamicDataSourceContextHolder;
import com.example.plandeduce.mapper.FireJudgeResultMapper;
import com.example.plandeduce.mapper.RoomInfoMapper;
import com.example.plandeduce.mapper.RoomObjectHisMapper;
import com.example.plandeduce.model.ProgressQueryContext;
import com.example.plandeduce.model.ProgressRangeQuery;
import com.example.plandeduce.model.ProgressSnapshotQuery;
import com.example.plandeduce.model.FireJudgeResult;
import com.example.plandeduce.model.ProgressTimeline;
import com.example.plandeduce.model.RoomInfo;
import com.example.plandeduce.model.RoomObjectHis;
import com.example.plandeduce.service.ProgressDataService;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
/**
 * 动态数据源下的快照查询与缓存实现。
 * 所有缓存都必须按 dbName 隔离，避免不同数据库之间串数据。
 */
public class ProgressDataServiceImpl implements ProgressDataService {
    private static final String SOURCE_TYPE_FULL = "FULL";
    private static final String SOURCE_TYPE_INCREMENT = "INCREMENT";

    private final RoomObjectHisMapper roomObjectMapper;
    private final FireJudgeResultMapper fireJudgeResultMapper;
    private final RoomInfoMapper roomInfoMapper;
    /** 对象全量快照缓存：第一层 key=dbName，第二层 key=intervalSeconds，第三层 key=simTime，value=该秒点的对象全量快照列表。 */
    private final Map<String, Map<Integer, Map<Integer, List<RoomObjectHis>>>> fullSnapshotCache = new ConcurrentHashMap<>();
    /** 事件全量快照缓存：第一层 key=dbName，第二层 key=intervalSeconds，第三层 key=simTime，value=该秒点的事件全量快照列表。 */
    private final Map<String, Map<Integer, Map<Integer, List<FireJudgeResult>>>> eventFullSnapshotCache = new ConcurrentHashMap<>();

    public ProgressDataServiceImpl(RoomObjectHisMapper roomObjectMapper,
                                   FireJudgeResultMapper fireJudgeResultMapper,
                                   RoomInfoMapper roomInfoMapper) {
        this.roomObjectMapper = roomObjectMapper;
        this.fireJudgeResultMapper = fireJudgeResultMapper;
        this.roomInfoMapper = roomInfoMapper;
    }

    @Override
    public ProgressTimeline queryProgressTimeline(ProgressQueryContext queryContext) {
        String dbName = queryContext.getDbName();
        DynamicDataSourceContextHolder.set(dbName);
        try {
            RoomInfo roomInfo = requireRoomInfo(dbName);
            return new ProgressTimeline(formatStartTime(roomInfo.getStartTime()), minutesToSeconds(roomInfo.getTotalTime()));
        } finally {
            DynamicDataSourceContextHolder.clear();
        }
    }

    /** 预热指定全量间隔下的 0 秒快照。 */
    @Override
    public void preloadFullSnapshots(ProgressSnapshotQuery snapshotQuery) {
        String dbName = snapshotQuery.getDbName();
        DynamicDataSourceContextHolder.set(dbName);
        try {
            ensureSnapshotCacheInitialized(snapshotQuery);
        } finally {
            DynamicDataSourceContextHolder.clear();
        }
    }

    /** 读取全量秒点快照，未命中时现场构造并回填缓存。 */
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

    /** 查询播放帧使用的区间原始数据。 */
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

    /** 查询快照类消息使用的区间最终态补丁。 */
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

    /** 读取事件全量快照。 */
    @Override
    public List<FireJudgeResult> queryEventFullData(ProgressSnapshotQuery snapshotQuery) {
        String dbName = snapshotQuery.getDbName();
        DynamicDataSourceContextHolder.set(dbName);
        try {
            return cloneEventDataList(getEventFullSnapshotAtCachePoint(snapshotQuery));
        } finally {
            DynamicDataSourceContextHolder.clear();
        }
    }

    /** 查询事件增量区间。 */
    @Override
    public List<FireJudgeResult> queryEventIncrementalData(ProgressRangeQuery rangeQuery) {
        String dbName = rangeQuery.getDbName();
        Integer fromExclusive = rangeQuery.getFromExclusive();
        Integer toInclusive = rangeQuery.getToInclusive();
        DynamicDataSourceContextHolder.set(dbName);
        try {
            if (toInclusive == null || fromExclusive == null || toInclusive <= fromExclusive) {
                return new ArrayList<>();
            }
            return cloneEventDataList(queryEventRowsBetween(fromExclusive, toInclusive));
        } finally {
            DynamicDataSourceContextHolder.clear();
        }
    }

    private RoomInfo requireRoomInfo(String dbName) {
        if (dbName == null || dbName.trim().isEmpty()) {
            throw new IllegalArgumentException("dbName 不能为空");
        }

        Long roomInfoId;
        try {
            roomInfoId = Long.valueOf(dbName);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("dbName 必须是动态数据库标识，同时满足 ROOM_INFO.id 的数字字符串约定");
        }
        RoomInfo roomInfo = roomInfoMapper.selectById(roomInfoId);
        if (roomInfo == null) {
            throw new IllegalArgumentException("未找到对应的 ROOM_INFO 记录: id=" + roomInfoId);
        }
        return roomInfo;
    }

    private Integer minutesToSeconds(Integer totalTimeMinutes) {
        if (totalTimeMinutes == null || totalTimeMinutes <= 0) {
            return 0;
        }
        return Math.multiplyExact(totalTimeMinutes, 60);
    }

    private String formatStartTime(java.util.Date startTime) {
        if (startTime == null) {
            return null;
        }
        return DateUtil.format(startTime, DatePattern.NORM_DATETIME_PATTERN);
    }

    private int toMillisecondStart(int secondValue) {
        return Math.multiplyExact(Math.max(secondValue, 0), 1000);
    }

    private int toMillisecondEndExclusive(int secondValue) {
        return Math.multiplyExact(Math.max(secondValue, 0) + 1, 1000);
    }

    private Integer toSecondValue(Integer millisecondValue) {
        if (millisecondValue == null) {
            return null;
        }
        return millisecondValue / 1000;
    }

    /** 获取指定秒点的对象全量快照。 */
    private List<RoomObjectHis> getFullSnapshotAtCachePoint(ProgressSnapshotQuery snapshotQuery) {
        ProgressSnapshotQuery normalizedSnapshotQuery = normalizeSnapshotQuery(snapshotQuery);
        ensureSnapshotCacheInitialized(normalizedSnapshotQuery);
        Map<Integer, List<RoomObjectHis>> snapshotCacheByTime = getRoomObjectCacheByTime(normalizedSnapshotQuery);
        int simTime = normalizedSnapshotQuery.getSimTime();
        List<RoomObjectHis> cachedSnapshot = snapshotCacheByTime.get(simTime);
        if (cachedSnapshot != null) {
            return cachedSnapshot;
        }
        List<RoomObjectHis> builtSnapshot = buildFullSnapshotAtPoint(normalizedSnapshotQuery);
        List<RoomObjectHis> existingSnapshot = snapshotCacheByTime.putIfAbsent(simTime, builtSnapshot);
        return existingSnapshot != null ? existingSnapshot : builtSnapshot;
    }

    /** 获取指定秒点的事件全量快照。 */
    private List<FireJudgeResult> getEventFullSnapshotAtCachePoint(ProgressSnapshotQuery snapshotQuery) {
        ProgressSnapshotQuery normalizedSnapshotQuery = normalizeSnapshotQuery(snapshotQuery);
        ensureSnapshotCacheInitialized(normalizedSnapshotQuery);
        Map<Integer, List<FireJudgeResult>> snapshotCacheByTime = getEventCacheByTime(normalizedSnapshotQuery);
        int simTime = normalizedSnapshotQuery.getSimTime();
        List<FireJudgeResult> cachedSnapshot = snapshotCacheByTime.get(simTime);
        if (cachedSnapshot != null) {
            return cachedSnapshot;
        }
        List<FireJudgeResult> builtSnapshot = buildEventFullSnapshotAtPoint(normalizedSnapshotQuery);
        List<FireJudgeResult> existingSnapshot = snapshotCacheByTime.putIfAbsent(simTime, builtSnapshot);
        return existingSnapshot != null ? existingSnapshot : builtSnapshot;
    }

    /** 基于上一个全量点滚动构造事件快照。 */
    private List<FireJudgeResult> buildEventFullSnapshotAtPoint(ProgressSnapshotQuery snapshotQuery) {
        ProgressSnapshotQuery normalizedSnapshotQuery = normalizeSnapshotQuery(snapshotQuery);
        int targetTime = normalizedSnapshotQuery.getSimTime();
        if (targetTime == 0) {
            return buildZeroPointEventSnapshot();
        }
        int interval = Math.max(normalizedSnapshotQuery.getIntervalSeconds(), 1);
        int previousFullTime = Math.max(targetTime - interval, 0);
        ProgressSnapshotQuery previousSnapshotQuery = new ProgressSnapshotQuery(
                normalizedSnapshotQuery.getDbName(),
                normalizedSnapshotQuery.getIntervalSeconds(),
                previousFullTime
        );
        List<FireJudgeResult> merged = new ArrayList<>(getEventFullSnapshotAtCachePoint(previousSnapshotQuery));
        merged.addAll(queryEventRowsBetween(previousFullTime, targetTime));
        return merged;
    }

    /** 构造 0 秒事件快照。 */
    private List<FireJudgeResult> buildZeroPointEventSnapshot() {
        return queryEventRowsAtTime(0);
    }

    /** 基于上一个全量点滚动构造对象快照。 */
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

    private void ensureSnapshotCacheInitialized(ProgressSnapshotQuery snapshotQuery) {
        ProgressSnapshotQuery normalizedSnapshotQuery = normalizeSnapshotQuery(snapshotQuery);
        if (normalizedSnapshotQuery.getIntervalSeconds() <= 0) {
            throw new IllegalArgumentException("全量保存间隔必须大于 0 秒");
        }
        Map<Integer, List<RoomObjectHis>> roomObjectSnapshotCache = getRoomObjectCacheByTime(normalizedSnapshotQuery);
        if (roomObjectSnapshotCache.get(0) == null) {
            roomObjectSnapshotCache.putIfAbsent(0, buildZeroPointSnapshot());
        }

        Map<Integer, List<FireJudgeResult>> eventSnapshotCache = getEventCacheByTime(normalizedSnapshotQuery);
        if (eventSnapshotCache.get(0) == null) {
            eventSnapshotCache.putIfAbsent(0, buildZeroPointEventSnapshot());
        }
    }

    private Map<Integer, List<RoomObjectHis>> getRoomObjectCacheByTime(ProgressSnapshotQuery snapshotQuery) {
        String dbName = snapshotQuery.getDbName();
        int intervalSeconds = snapshotQuery.getIntervalSeconds();
        // 第一层 key=dbName，value=当前数据库下的“全量间隔 -> 秒点快照”映射。
        Map<Integer, Map<Integer, List<RoomObjectHis>>> cacheByInterval = fullSnapshotCache.get(dbName);
        if (cacheByInterval == null) {
            Map<Integer, Map<Integer, List<RoomObjectHis>>> newCacheByInterval = new ConcurrentHashMap<>();
            Map<Integer, Map<Integer, List<RoomObjectHis>>> existingCacheByInterval = fullSnapshotCache.putIfAbsent(dbName, newCacheByInterval);
            cacheByInterval = existingCacheByInterval != null ? existingCacheByInterval : newCacheByInterval;
        }

        // 第二层 key=intervalSeconds，value=该全量间隔下的“simTime -> 快照数据”映射。
        Map<Integer, List<RoomObjectHis>> cacheByTime = cacheByInterval.get(intervalSeconds);
        if (cacheByTime == null) {
            Map<Integer, List<RoomObjectHis>> newCacheByTime = new ConcurrentHashMap<>();
            Map<Integer, List<RoomObjectHis>> existingCacheByTime = cacheByInterval.putIfAbsent(intervalSeconds, newCacheByTime);
            cacheByTime = existingCacheByTime != null ? existingCacheByTime : newCacheByTime;
        }
        return cacheByTime;
    }

    private Map<Integer, List<FireJudgeResult>> getEventCacheByTime(ProgressSnapshotQuery snapshotQuery) {
        String dbName = snapshotQuery.getDbName();
        int intervalSeconds = snapshotQuery.getIntervalSeconds();
        // 第一层 key=dbName，value=当前数据库下的“全量间隔 -> 秒点事件快照”映射。
        Map<Integer, Map<Integer, List<FireJudgeResult>>> cacheByInterval = eventFullSnapshotCache.get(dbName);
        if (cacheByInterval == null) {
            Map<Integer, Map<Integer, List<FireJudgeResult>>> newCacheByInterval = new ConcurrentHashMap<Integer, Map<Integer, List<FireJudgeResult>>>();
            Map<Integer, Map<Integer, List<FireJudgeResult>>> existingCacheByInterval = eventFullSnapshotCache.putIfAbsent(dbName, newCacheByInterval);
            cacheByInterval = existingCacheByInterval != null ? existingCacheByInterval : newCacheByInterval;
        }

        // 第二层 key=intervalSeconds，value=该全量间隔下的“simTime -> 事件快照数据”映射。
        Map<Integer, List<FireJudgeResult>> cacheByTime = cacheByInterval.get(intervalSeconds);
        if (cacheByTime == null) {
            Map<Integer, List<FireJudgeResult>> newCacheByTime = new ConcurrentHashMap<Integer, List<FireJudgeResult>>();
            Map<Integer, List<FireJudgeResult>> existingCacheByTime = cacheByInterval.putIfAbsent(intervalSeconds, newCacheByTime);
            cacheByTime = existingCacheByTime != null ? existingCacheByTime : newCacheByTime;
        }
        return cacheByTime;
    }

    private ProgressSnapshotQuery normalizeSnapshotQuery(ProgressSnapshotQuery snapshotQuery) {
        return new ProgressSnapshotQuery(
                snapshotQuery.getDbName(),
                snapshotQuery.getIntervalSeconds(),
                Math.max(snapshotQuery.getSimTime(), 0)
        );
    }

    /** 查询某一秒的对象记录。 */
    private List<RoomObjectHis> queryRowsAtTime(int simTime) {
        int startMillisecond = toMillisecondStart(simTime);
        int endMillisecondExclusive = toMillisecondEndExclusive(simTime);
        LambdaQueryWrapper<RoomObjectHis> queryWrapper = Wrappers.<RoomObjectHis>lambdaQuery()
                .ge(RoomObjectHis::getSimTime, startMillisecond)
                .lt(RoomObjectHis::getSimTime, endMillisecondExclusive)
                .orderByAsc(RoomObjectHis::getRoomObjectId);
        return roomObjectMapper.selectList(queryWrapper);
    }

    /** 查询区间内的对象记录。 */
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
        Map<Integer, RoomObjectHis> latestRowsByObjectId = new LinkedHashMap<Integer, RoomObjectHis>();
        for (RoomObjectHis row : queryRowsBetween(fromExclusive, toInclusive)) {
            latestRowsByObjectId.put(row.getRoomObjectId(), row);
        }
        return sortByRoomObjectId(new ArrayList<>(latestRowsByObjectId.values()));
    }

    /** 查询某一秒的事件记录。 */
    private List<FireJudgeResult> queryEventRowsAtTime(int simTimeValue) {
        int startMillisecond = toMillisecondStart(simTimeValue);
        int endMillisecondExclusive = toMillisecondEndExclusive(simTimeValue);
        LambdaQueryWrapper<FireJudgeResult> queryWrapper = Wrappers.<FireJudgeResult>lambdaQuery()
                .ge(FireJudgeResult::getSimTime, startMillisecond)
                .lt(FireJudgeResult::getSimTime, endMillisecondExclusive)
                .orderByAsc(FireJudgeResult::getSimTime)
                .orderByAsc(FireJudgeResult::getId);
        return fireJudgeResultMapper.selectList(queryWrapper);
    }

    /** 查询区间内的事件记录。 */
    private List<FireJudgeResult> queryEventRowsBetween(int fromExclusive, int toInclusive) {
        int startMillisecond = toMillisecondStart(fromExclusive + 1);
        int endMillisecondExclusive = toMillisecondEndExclusive(toInclusive);
        LambdaQueryWrapper<FireJudgeResult> queryWrapper = Wrappers.<FireJudgeResult>lambdaQuery()
                .ge(FireJudgeResult::getSimTime, startMillisecond)
                .lt(FireJudgeResult::getSimTime, endMillisecondExclusive)
                .orderByAsc(FireJudgeResult::getSimTime)
                .orderByAsc(FireJudgeResult::getId);
        return fireJudgeResultMapper.selectList(queryWrapper);
    }

    /** 按对象 ID 建索引，便于做最终态覆盖。 */
    private Map<Integer, RoomObjectHis> indexByRoomObjectId(List<RoomObjectHis> rows) {
        Map<Integer, RoomObjectHis> rowsByObjectId = new LinkedHashMap<>();
        for (RoomObjectHis row : rows) {
            rowsByObjectId.put(row.getRoomObjectId(), row);
        }
        return rowsByObjectId;
    }

    /** 统一结果顺序，便于前端消费和测试断言。 */
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

    /** 标记快照来源类型。 */
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

    /** 克隆对象数据，避免调用方改动缓存对象。 */
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

    /** 克隆事件数据，避免调用方改动缓存对象。 */
    private List<FireJudgeResult> cloneEventDataList(List<FireJudgeResult> dataList) {
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
