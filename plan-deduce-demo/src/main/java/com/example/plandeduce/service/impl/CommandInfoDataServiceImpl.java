package com.example.plandeduce.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.plandeduce.config.DynamicDataSourceContextHolder;
import com.example.plandeduce.mapper.CommandInfoMapper;
import com.example.plandeduce.model.CommandInfo;
import com.example.plandeduce.model.ProgressRangeQuery;
import com.example.plandeduce.model.ProgressSnapshotQuery;
import com.example.plandeduce.service.CommandInfoDataService;
import com.example.plandeduce.service.RoomInfoService;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class CommandInfoDataServiceImpl implements CommandInfoDataService {
    private final CommandInfoMapper commandInfoMapper;
    private final RoomInfoService roomInfoService;
    private final Map<String, Map<Integer, Map<Integer, List<CommandInfo>>>> fullSnapshotCache = new ConcurrentHashMap<>();
    private final Map<String, Date> roomStartTimeCache = new ConcurrentHashMap<>();

    /** 注入依赖。 */
    public CommandInfoDataServiceImpl(CommandInfoMapper commandInfoMapper, RoomInfoService roomInfoService) {
        this.commandInfoMapper = commandInfoMapper;
        this.roomInfoService = roomInfoService;
    }

    /** 预热 0 秒快照。 */
    @Override
    public void preloadSnapshots(ProgressSnapshotQuery snapshotQuery) {
        String dbName = snapshotQuery.getDbName();
        DynamicDataSourceContextHolder.set(dbName);
        try {
            ensureRoomStartTimeLoaded(dbName);
            ensureSnapshotCacheInitialized(snapshotQuery);
        } finally {
            DynamicDataSourceContextHolder.clear();
        }
    }

    /** 查询指令信息全量快照。 */
    @Override
    public List<CommandInfo> queryFullData(ProgressSnapshotQuery snapshotQuery) {
        String dbName = snapshotQuery.getDbName();
        DynamicDataSourceContextHolder.set(dbName);
        try {
            ensureRoomStartTimeLoaded(dbName);
            return cloneDataList(getFullSnapshotAtCachePoint(snapshotQuery));
        } finally {
            DynamicDataSourceContextHolder.clear();
        }
    }

    /** 查询指令信息增量数据。 */
    @Override
    public List<CommandInfo> queryIncrementalData(ProgressRangeQuery rangeQuery) {
        String dbName = rangeQuery.getDbName();
        Integer fromExclusive = rangeQuery.getFromExclusive();
        Integer toInclusive = rangeQuery.getToInclusive();
        DynamicDataSourceContextHolder.set(dbName);
        try {
            ensureRoomStartTimeLoaded(dbName);
            if (toInclusive == null || fromExclusive == null || toInclusive <= fromExclusive) {
                return new ArrayList<>();
            }
            return cloneDataList(queryRowsBetween(dbName, fromExclusive, toInclusive));
        } finally {
            DynamicDataSourceContextHolder.clear();
        }
    }

    /** 查询指令信息快照补丁。 */
    @Override
    public List<CommandInfo> querySnapshotIncrementalData(ProgressRangeQuery rangeQuery) {
        String dbName = rangeQuery.getDbName();
        Integer fromExclusive = rangeQuery.getFromExclusive();
        Integer toInclusive = rangeQuery.getToInclusive();
        DynamicDataSourceContextHolder.set(dbName);
        try {
            ensureRoomStartTimeLoaded(dbName);
            if (toInclusive == null || fromExclusive == null || toInclusive <= fromExclusive) {
                return new ArrayList<>();
            }
            return cloneDataList(sortByObjId(new ArrayList<>(indexByObjId(queryRowsBetween(dbName, fromExclusive, toInclusive)).values())));
        } finally {
            DynamicDataSourceContextHolder.clear();
        }
    }

    /** 初始化指令信息快照缓存。 */
    private void ensureSnapshotCacheInitialized(ProgressSnapshotQuery snapshotQuery) {
        ProgressSnapshotQuery normalizedSnapshotQuery = normalizeSnapshotQuery(snapshotQuery);
        if (normalizedSnapshotQuery.getIntervalSeconds() <= 0) {
            throw new IllegalArgumentException("全量保存间隔必须大于 0 秒");
        }
        Map<Integer, List<CommandInfo>> snapshotCache = getCacheByTime(normalizedSnapshotQuery);
        if (snapshotCache.get(0) == null) {
            snapshotCache.putIfAbsent(0, buildZeroPointSnapshot(normalizedSnapshotQuery.getDbName()));
        }
    }

    /** 获取指令信息全量快照。 */
    private List<CommandInfo> getFullSnapshotAtCachePoint(ProgressSnapshotQuery snapshotQuery) {
        ProgressSnapshotQuery normalizedSnapshotQuery = normalizeSnapshotQuery(snapshotQuery);
        ensureSnapshotCacheInitialized(normalizedSnapshotQuery);
        Map<Integer, List<CommandInfo>> snapshotCacheByTime = getCacheByTime(normalizedSnapshotQuery);
        int simTime = normalizedSnapshotQuery.getSimTime();
        List<CommandInfo> cachedSnapshot = snapshotCacheByTime.get(simTime);
        if (cachedSnapshot != null) {
            return cachedSnapshot;
        }
        List<CommandInfo> builtSnapshot = buildFullSnapshotAtPoint(normalizedSnapshotQuery);
        List<CommandInfo> existingSnapshot = snapshotCacheByTime.putIfAbsent(simTime, builtSnapshot);
        return existingSnapshot != null ? existingSnapshot : builtSnapshot;
    }

    /** 构造指令信息全量快照。 */
    private List<CommandInfo> buildFullSnapshotAtPoint(ProgressSnapshotQuery snapshotQuery) {
        ProgressSnapshotQuery normalizedSnapshotQuery = normalizeSnapshotQuery(snapshotQuery);
        String dbName = normalizedSnapshotQuery.getDbName();
        int targetTime = normalizedSnapshotQuery.getSimTime();
        if (targetTime == 0) {
            return buildZeroPointSnapshot(dbName);
        }
        int interval = Math.max(normalizedSnapshotQuery.getIntervalSeconds(), 1);
        int previousFullTime = Math.max(targetTime - interval, 0);
        ProgressSnapshotQuery previousSnapshotQuery = new ProgressSnapshotQuery(
                dbName,
                normalizedSnapshotQuery.getIntervalSeconds(),
                previousFullTime
        );
        Map<Integer, CommandInfo> mergedRowsByObjId = indexByObjId(getFullSnapshotAtCachePoint(previousSnapshotQuery));
        for (CommandInfo row : queryRowsBetween(dbName, previousFullTime, targetTime)) {
            mergedRowsByObjId.put(row.getObjId(), row);
        }
        return sortByObjId(new ArrayList<>(mergedRowsByObjId.values()));
    }

    /** 构造 0 秒快照。 */
    private List<CommandInfo> buildZeroPointSnapshot(String dbName) {
        return sortByObjId(new ArrayList<>(indexByObjId(queryRowsAtTime(dbName, 0)).values()));
    }

    /** 查询单秒指令信息记录。 */
    private List<CommandInfo> queryRowsAtTime(String dbName, int simTimeValue) {
        Date roomStartTime = getRequiredRoomStartTime(dbName);
        Timestamp startTime = toAbsoluteTime(roomStartTime, toMillisecondStart(simTimeValue));
        Timestamp endTimeExclusive = toAbsoluteTime(roomStartTime, toMillisecondEndExclusive(simTimeValue));
        LambdaQueryWrapper<CommandInfo> queryWrapper = Wrappers.<CommandInfo>lambdaQuery()
                .ge(CommandInfo::getBeginTime, startTime)
                .lt(CommandInfo::getBeginTime, endTimeExclusive)
                .orderByAsc(CommandInfo::getBeginTime)
                .orderByAsc(CommandInfo::getObjId)
                .orderByAsc(CommandInfo::getId);
        return hydrateSimTime(dbName, commandInfoMapper.selectList(queryWrapper));
    }

    /** 查询区间指令信息记录。 */
    private List<CommandInfo> queryRowsBetween(String dbName, int fromExclusive, int toInclusive) {
        Date roomStartTime = getRequiredRoomStartTime(dbName);
        Timestamp startTime = toAbsoluteTime(roomStartTime, toMillisecondStart(fromExclusive + 1));
        Timestamp endTimeExclusive = toAbsoluteTime(roomStartTime, toMillisecondEndExclusive(toInclusive));
        LambdaQueryWrapper<CommandInfo> queryWrapper = Wrappers.<CommandInfo>lambdaQuery()
                .ge(CommandInfo::getBeginTime, startTime)
                .lt(CommandInfo::getBeginTime, endTimeExclusive)
                .orderByAsc(CommandInfo::getBeginTime)
                .orderByAsc(CommandInfo::getObjId)
                .orderByAsc(CommandInfo::getId);
        return hydrateSimTime(dbName, commandInfoMapper.selectList(queryWrapper));
    }

    /** 获取指令信息快照缓存。 */
    private Map<Integer, List<CommandInfo>> getCacheByTime(ProgressSnapshotQuery snapshotQuery) {
        String dbName = snapshotQuery.getDbName();
        int intervalSeconds = snapshotQuery.getIntervalSeconds();
        Map<Integer, Map<Integer, List<CommandInfo>>> cacheByInterval = fullSnapshotCache.get(dbName);
        if (cacheByInterval == null) {
            Map<Integer, Map<Integer, List<CommandInfo>>> newCacheByInterval = new ConcurrentHashMap<>();
            Map<Integer, Map<Integer, List<CommandInfo>>> existingCacheByInterval = fullSnapshotCache.putIfAbsent(dbName, newCacheByInterval);
            cacheByInterval = existingCacheByInterval != null ? existingCacheByInterval : newCacheByInterval;
        }
        Map<Integer, List<CommandInfo>> cacheByTime = cacheByInterval.get(intervalSeconds);
        if (cacheByTime == null) {
            Map<Integer, List<CommandInfo>> newCacheByTime = new ConcurrentHashMap<>();
            Map<Integer, List<CommandInfo>> existingCacheByTime = cacheByInterval.putIfAbsent(intervalSeconds, newCacheByTime);
            cacheByTime = existingCacheByTime != null ? existingCacheByTime : newCacheByTime;
        }
        return cacheByTime;
    }

    /** 按 objId 建索引。 */
    private Map<Integer, CommandInfo> indexByObjId(List<CommandInfo> rows) {
        Map<Integer, CommandInfo> rowsByObjId = new LinkedHashMap<>();
        for (CommandInfo row : rows) {
            rowsByObjId.put(row.getObjId(), row);
        }
        return rowsByObjId;
    }

    /** 按 objId 排序。 */
    private List<CommandInfo> sortByObjId(List<CommandInfo> rows) {
        if (rows == null || rows.isEmpty()) {
            return new ArrayList<>();
        }
        List<CommandInfo> filteredRows = new ArrayList<>();
        for (CommandInfo row : rows) {
            if (row != null) {
                filteredRows.add(row);
            }
        }
        filteredRows.sort((left, right) -> compareNullableInteger(left.getObjId(), right.getObjId()));
        return filteredRows;
    }

    /** 复制指令信息数据。 */
    private List<CommandInfo> cloneDataList(List<CommandInfo> dataList) {
        if (dataList == null || dataList.isEmpty()) {
            return new ArrayList<>();
        }
        List<CommandInfo> clones = new ArrayList<>(dataList.size());
        for (CommandInfo item : dataList) {
            if (item == null) {
                continue;
            }
            CommandInfo clone = new CommandInfo();
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

    /** 加载房间开始时间。 */
    private void ensureRoomStartTimeLoaded(String dbName) {
        if (roomStartTimeCache.containsKey(dbName)) {
            return;
        }
        roomStartTimeCache.putIfAbsent(dbName, roomInfoService.queryRequiredStartTime(dbName));
    }

    /** 获取房间开始时间。 */
    private Date getRequiredRoomStartTime(String dbName) {
        Date roomStartTime = roomStartTimeCache.get(dbName);
        if (roomStartTime == null) {
            throw new IllegalStateException("未加载 ROOM_INFO.startTime: dbName=" + dbName);
        }
        return roomStartTime;
    }

    /** 补齐 simTime。 */
    private List<CommandInfo> hydrateSimTime(String dbName, List<CommandInfo> rows) {
        Date roomStartTime = getRequiredRoomStartTime(dbName);
        for (CommandInfo row : rows) {
            if (row != null && row.getBeginTime() != null) {
                row.setSimTime(toRelativeMillisecond(roomStartTime, row.getBeginTime()));
            }
        }
        return rows;
    }

    /** 计算绝对时间。 */
    private Timestamp toAbsoluteTime(Date roomStartTime, int millisecondOffset) {
        long startMillis = roomStartTime.getTime();
        return new Timestamp(startMillis + Math.max(millisecondOffset, 0));
    }

    /** 秒转毫秒起点。 */
    private int toMillisecondStart(int secondValue) {
        return Math.multiplyExact(Math.max(secondValue, 0), 1000);
    }

    /** 秒转毫秒终点。 */
    private int toMillisecondEndExclusive(int secondValue) {
        return Math.multiplyExact(Math.max(secondValue, 0) + 1, 1000);
    }

    /** 计算相对毫秒。 */
    private int toRelativeMillisecond(Date roomStartTime, Date beginTime) {
        return Math.toIntExact(beginTime.getTime() - roomStartTime.getTime());
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
