package com.example.plandeduce.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.plandeduce.config.DynamicDataSourceContextHolder;
import com.example.plandeduce.mapper.ControlPointMapper;
import com.example.plandeduce.model.ControlPoint;
import com.example.plandeduce.model.ProgressQueryContext;
import com.example.plandeduce.model.ProgressRangeQuery;
import com.example.plandeduce.model.ProgressSnapshotQuery;
import com.example.plandeduce.service.ControlPointDataService;
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
/** 实现控制点数据查询。 */
public class ControlPointDataServiceImpl implements ControlPointDataService {
    private final ControlPointMapper controlPointMapper;
    private final RoomInfoService roomInfoService;
    private final Map<String, Map<Integer, Map<Integer, List<ControlPoint>>>> fullSnapshotCache = new ConcurrentHashMap<>();
    private final Map<String, Date> roomStartTimeCache = new ConcurrentHashMap<>();

    /** 注入依赖。 */
    public ControlPointDataServiceImpl(ControlPointMapper controlPointMapper, RoomInfoService roomInfoService) {
        this.controlPointMapper = controlPointMapper;
        this.roomInfoService = roomInfoService;
    }

    /** 预热基础快照。 */
    @Override
    public void preloadSnapshots(ProgressSnapshotQuery snapshotQuery) {
        String dataSourceKey = snapshotQuery.getDataSourceKey();
        DynamicDataSourceContextHolder.set(dataSourceKey);
        try {
            ensureRoomStartTimeLoaded(snapshotQuery.toQueryContext());
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
            ensureRoomStartTimeLoaded(snapshotQuery.toQueryContext());
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
            ensureRoomStartTimeLoaded(rangeQuery.toQueryContext());
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
            ensureRoomStartTimeLoaded(rangeQuery.toQueryContext());
            if (toInclusive == null || fromExclusive == null || toInclusive <= fromExclusive) {
                return new ArrayList<>();
            }
            return cloneDataList(sortById(new ArrayList<>(indexById(queryRowsBetween(dataSourceKey, fromExclusive, toInclusive)).values())));
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
        Map<Integer, ControlPoint> mergedRowsById = indexById(getFullSnapshotAtCachePoint(previousSnapshotQuery));
        for (ControlPoint row : queryRowsBetween(dataSourceKey, previousFullTime, targetTime)) {
            if (row != null && row.getId() != null) {
                mergedRowsById.put(row.getId(), row);
            }
        }
        return sortById(new ArrayList<>(mergedRowsById.values()));
    }

    /** 构造基础快照。 */
    private List<ControlPoint> buildZeroPointSnapshot(String dbName) {
        return sortById(new ArrayList<>(indexById(queryRowsAtTime(dbName, 0)).values()));
    }

    /** 查询单秒控制点数据。 */
    private List<ControlPoint> queryRowsAtTime(String dbName, int simTimeValue) {
        Date roomStartTime = getRequiredRoomStartTime(dbName);
        Timestamp startTime = toAbsoluteTime(roomStartTime, toMillisecondStart(simTimeValue));
        Timestamp endTimeExclusive = toAbsoluteTime(roomStartTime, toMillisecondEndExclusive(simTimeValue));
        LambdaQueryWrapper<ControlPoint> queryWrapper = Wrappers.<ControlPoint>lambdaQuery()
                .ge(ControlPoint::getCreateTime, startTime)
                .lt(ControlPoint::getCreateTime, endTimeExclusive)
                .orderByAsc(ControlPoint::getCreateTime)
                .orderByAsc(ControlPoint::getId);
        return hydrateSimTime(dbName, controlPointMapper.selectList(queryWrapper));
    }

    /** 查询区间控制点数据。 */
    private List<ControlPoint> queryRowsBetween(String dbName, int fromExclusive, int toInclusive) {
        Date roomStartTime = getRequiredRoomStartTime(dbName);
        Timestamp startTime = toAbsoluteTime(roomStartTime, toMillisecondStart(fromExclusive + 1));
        Timestamp endTimeExclusive = toAbsoluteTime(roomStartTime, toMillisecondEndExclusive(toInclusive));
        LambdaQueryWrapper<ControlPoint> queryWrapper = Wrappers.<ControlPoint>lambdaQuery()
                .ge(ControlPoint::getCreateTime, startTime)
                .lt(ControlPoint::getCreateTime, endTimeExclusive)
                .orderByAsc(ControlPoint::getCreateTime)
                .orderByAsc(ControlPoint::getId);
        return hydrateSimTime(dbName, controlPointMapper.selectList(queryWrapper));
    }

    /** 获取控制点快照缓存。 */
    private Map<Integer, List<ControlPoint>> getCacheByTime(ProgressSnapshotQuery snapshotQuery) {
        Map<Integer, Map<Integer, List<ControlPoint>>> cacheByInterval = fullSnapshotCache.computeIfAbsent(
                snapshotQuery.getDataSourceKey(),
                key -> new ConcurrentHashMap<>()
        );
        return cacheByInterval.computeIfAbsent(snapshotQuery.getIntervalSeconds(), key -> new ConcurrentHashMap<>());
    }

    /** 按 id 建索引。 */
    private Map<Integer, ControlPoint> indexById(List<ControlPoint> rows) {
        Map<Integer, ControlPoint> rowsById = new LinkedHashMap<>();
        for (ControlPoint row : rows) {
            if (row != null && row.getId() != null) {
                rowsById.put(row.getId(), row);
            }
        }
        return rowsById;
    }

    /** 按 id 排序。 */
    private List<ControlPoint> sortById(List<ControlPoint> rows) {
        List<ControlPoint> filteredRows = new ArrayList<>();
        for (ControlPoint row : rows) {
            if (row != null) {
                filteredRows.add(row);
            }
        }
        filteredRows.sort((left, right) -> compareNullableInteger(left.getId(), right.getId()));
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

    /** 加载房间开始时间。 */
    private void ensureRoomStartTimeLoaded(ProgressQueryContext queryContext) {
        String dataSourceKey = queryContext.getDataSourceKey();
        if (roomStartTimeCache.containsKey(dataSourceKey)) {
            return;
        }
        roomStartTimeCache.putIfAbsent(dataSourceKey, roomInfoService.queryRequiredStartTime(queryContext));
    }

    /** 获取房间开始时间。 */
    private Date getRequiredRoomStartTime(String dbName) {
        Date roomStartTime = roomStartTimeCache.get(dbName);
        if (roomStartTime == null) {
            throw new IllegalStateException("未加载 ROOM_INFO.startTime: dbName=" + dbName);
        }
        return roomStartTime;
    }

    /** 补齐 simTime 字段。 */
    private List<ControlPoint> hydrateSimTime(String dbName, List<ControlPoint> rows) {
        Date roomStartTime = getRequiredRoomStartTime(dbName);
        for (ControlPoint row : rows) {
            if (row != null && row.getCreateTime() != null) {
                row.setSimTime(toRelativeMillisecond(roomStartTime, row.getCreateTime()));
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
    private int toRelativeMillisecond(Date roomStartTime, Date createTime) {
        return Math.toIntExact(createTime.getTime() - roomStartTime.getTime());
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
