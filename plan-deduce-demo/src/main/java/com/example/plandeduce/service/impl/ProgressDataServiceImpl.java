package com.example.plandeduce.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.plandeduce.mapper.FireJudgeResultMapper;
import com.example.plandeduce.mapper.RoomInfoMapper;
import com.example.plandeduce.mapper.RoomObjectHisMapper;
import com.example.plandeduce.model.FireJudgeResult;
import com.example.plandeduce.model.RoomInfo;
import com.example.plandeduce.model.RoomObjectHis;
import com.example.plandeduce.service.ProgressDataService;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
/**
 * 数据查询服务实现。
 * 当前项目固定连接单一数据库，不再根据 dbName 动态切换连接。
 * 同时取消静态字段/动态字段拆分，统一按实体全字段查询和缓存。
 */
public class ProgressDataServiceImpl implements ProgressDataService {
    private final RoomObjectHisMapper roomObjectMapper;
    private final FireJudgeResultMapper fireJudgeResultMapper;
    private final RoomInfoMapper roomInfoMapper;
    private final Map<Integer, Map<Integer, List<RoomObjectHis>>> fullSnapshotCache = new ConcurrentHashMap<>();
    private final Map<Integer, Map<Integer, List<FireJudgeResult>>> eventFullSnapshotCache = new ConcurrentHashMap<>();

    public ProgressDataServiceImpl(RoomObjectHisMapper roomObjectMapper,
                                   FireJudgeResultMapper fireJudgeResultMapper,
                                   RoomInfoMapper roomInfoMapper) {
        this.roomObjectMapper = roomObjectMapper;
        this.fireJudgeResultMapper = fireJudgeResultMapper;
        this.roomInfoMapper = roomInfoMapper;
    }

    @Override
    public void preloadFullSnapshots(String dbName, int intervalSeconds) {
        if (intervalSeconds <= 0) {
            throw new IllegalArgumentException("全量保存间隔必须大于 0 秒");
        }
        Map<Integer, List<RoomObjectHis>> roomObjectSnapshotCache = fullSnapshotCache.computeIfAbsent(intervalSeconds, key -> new ConcurrentHashMap<>());
        roomObjectSnapshotCache.computeIfAbsent(0, key -> buildZeroPointSnapshot());
        Map<Integer, List<FireJudgeResult>> eventSnapshotCache = eventFullSnapshotCache.computeIfAbsent(intervalSeconds, key -> new ConcurrentHashMap<>());
        eventSnapshotCache.computeIfAbsent(0, key -> buildZeroPointEventSnapshot());
    }

    @Override
    public List<RoomObjectHis> queryCachedFullData(String dbName, int intervalSeconds, int simTime) {
        return cloneDataList(getFullSnapshotAtCachePoint(intervalSeconds, Math.max(simTime, 0)), "FULL");
    }

    @Override
    public List<RoomObjectHis> queryIncrementalData(String dbName, Integer fromExclusive, Integer toInclusive) {
        if (toInclusive == null || fromExclusive == null || toInclusive <= fromExclusive) {
            return new ArrayList<>();
        }
        return cloneDataList(queryRowsBetween(fromExclusive, toInclusive), "INCREMENT");
    }

    @Override
    public List<RoomObjectHis> querySnapshotIncrementalData(String dbName, Integer fromExclusive, Integer toInclusive) {
        if (toInclusive == null || fromExclusive == null || toInclusive <= fromExclusive) {
            return new ArrayList<>();
        }
        Map<Integer, RoomObjectHis> latestRowsByObjectId = new LinkedHashMap<>();
        for (RoomObjectHis row : queryRowsBetween(fromExclusive, toInclusive)) {
            latestRowsByObjectId.put(row.getRoomObjectId(), row);
        }
        return cloneDataList(sortByRoomObjectId(new ArrayList<>(latestRowsByObjectId.values())), "INCREMENT");
    }

    @Override
    public Integer queryMaxSimTime(String dbName) {
        LambdaQueryWrapper<RoomObjectHis> queryWrapper = Wrappers.<RoomObjectHis>lambdaQuery()
                .select(RoomObjectHis::getSimTime)
                .orderByDesc(RoomObjectHis::getSimTime)
                .last("LIMIT 1");
        RoomObjectHis latest = roomObjectMapper.selectOne(queryWrapper);
        return latest == null || latest.getSimTime() == null ? 0 : latest.getSimTime();
    }

    @Override
    public List<FireJudgeResult> queryEventFullData(String dbName, int intervalSeconds, Integer simTime) {
        return cloneEventDataList(getEventFullSnapshotAtCachePoint(intervalSeconds, Math.max(simTime == null ? 0 : simTime, 0)));
    }

    @Override
    public List<FireJudgeResult> queryEventIncrementalData(String dbName, Integer fromExclusive, Integer toInclusive) {
        if (toInclusive == null || fromExclusive == null || toInclusive <= fromExclusive) {
            return new ArrayList<>();
        }
        return cloneEventDataList(queryEventRowsBetween(fromExclusive, toInclusive));
    }

    @Override
    public String queryRoomStartTime(String dbName) {
        LambdaQueryWrapper<RoomInfo> queryWrapper = Wrappers.<RoomInfo>lambdaQuery()
                .orderByAsc(RoomInfo::getId)
                .last("LIMIT 1");
        RoomInfo firstRow = roomInfoMapper.selectOne(queryWrapper);
        return firstRow == null ? null : firstRow.getStartTime();
    }

    private List<RoomObjectHis> getFullSnapshotAtCachePoint(int intervalSeconds, int simTime) {
        preloadFullSnapshots(null, intervalSeconds);
        Map<Integer, List<RoomObjectHis>> snapshotCacheByTime = fullSnapshotCache.computeIfAbsent(intervalSeconds, key -> new ConcurrentHashMap<>());
        List<RoomObjectHis> cachedSnapshot = snapshotCacheByTime.get(simTime);
        if (cachedSnapshot != null) {
            return cachedSnapshot;
        }
        List<RoomObjectHis> builtSnapshot = buildFullSnapshotAtPoint(intervalSeconds, simTime);
        List<RoomObjectHis> existingSnapshot = snapshotCacheByTime.putIfAbsent(simTime, builtSnapshot);
        return existingSnapshot != null ? existingSnapshot : builtSnapshot;
    }

    private List<FireJudgeResult> getEventFullSnapshotAtCachePoint(int intervalSeconds, int simTime) {
        preloadFullSnapshots(null, intervalSeconds);
        Map<Integer, List<FireJudgeResult>> snapshotCacheByTime = eventFullSnapshotCache.computeIfAbsent(intervalSeconds, key -> new ConcurrentHashMap<>());
        List<FireJudgeResult> cachedSnapshot = snapshotCacheByTime.get(simTime);
        if (cachedSnapshot != null) {
            return cachedSnapshot;
        }
        List<FireJudgeResult> builtSnapshot = buildEventFullSnapshotAtPoint(intervalSeconds, simTime);
        List<FireJudgeResult> existingSnapshot = snapshotCacheByTime.putIfAbsent(simTime, builtSnapshot);
        return existingSnapshot != null ? existingSnapshot : builtSnapshot;
    }

    private List<FireJudgeResult> buildEventFullSnapshotAtPoint(int intervalSeconds, int simTime) {
        int targetTime = Math.max(simTime, 0);
        if (targetTime == 0) {
            return buildZeroPointEventSnapshot();
        }
        int interval = Math.max(intervalSeconds, 1);
        int previousFullTime = Math.max(targetTime - interval, 0);
        List<FireJudgeResult> merged = new ArrayList<>(getEventFullSnapshotAtCachePoint(intervalSeconds, previousFullTime));
        merged.addAll(queryEventRowsBetween(previousFullTime, targetTime));
        return merged;
    }

    private List<FireJudgeResult> buildZeroPointEventSnapshot() {
        return queryEventRowsAtTime(0);
    }

    private List<RoomObjectHis> buildFullSnapshotAtPoint(int intervalSeconds, int simTime) {
        int targetTime = Math.max(simTime, 0);
        if (targetTime == 0) {
            return buildZeroPointSnapshot();
        }
        int interval = Math.max(intervalSeconds, 1);
        int previousFullTime = Math.max(targetTime - interval, 0);
        Map<Integer, RoomObjectHis> mergedRowsByObjectId = indexByRoomObjectId(getFullSnapshotAtCachePoint(intervalSeconds, previousFullTime));
        for (RoomObjectHis latestRow : queryLatestRowsByRoomObjectId(previousFullTime, targetTime)) {
            mergedRowsByObjectId.put(latestRow.getRoomObjectId(), latestRow);
        }
        return markSourceType(sortByRoomObjectId(new ArrayList<>(mergedRowsByObjectId.values())), "FULL");
    }

    private List<RoomObjectHis> buildZeroPointSnapshot() {
        return markSourceType(sortByRoomObjectId(queryRowsAtTime(0)), "FULL");
    }

    private List<RoomObjectHis> queryRowsAtTime(int simTime) {
        LambdaQueryWrapper<RoomObjectHis> queryWrapper = Wrappers.<RoomObjectHis>lambdaQuery()
                .eq(RoomObjectHis::getSimTime, simTime)
                .orderByAsc(RoomObjectHis::getRoomObjectId);
        return roomObjectMapper.selectList(queryWrapper);
    }

    private List<RoomObjectHis> queryRowsBetween(int fromExclusive, int toInclusive) {
        LambdaQueryWrapper<RoomObjectHis> queryWrapper = Wrappers.<RoomObjectHis>lambdaQuery()
                .gt(RoomObjectHis::getSimTime, fromExclusive)
                .le(RoomObjectHis::getSimTime, toInclusive)
                .orderByAsc(RoomObjectHis::getSimTime)
                .orderByAsc(RoomObjectHis::getRoomObjectId);
        return roomObjectMapper.selectList(queryWrapper);
    }

    private List<RoomObjectHis> queryLatestRowsByRoomObjectId(int fromExclusive, int toInclusive) {
        Map<Integer, RoomObjectHis> latestRowsByObjectId = new LinkedHashMap<>();
        for (RoomObjectHis row : queryRowsBetween(fromExclusive, toInclusive)) {
            latestRowsByObjectId.put(row.getRoomObjectId(), row);
        }
        return sortByRoomObjectId(new ArrayList<>(latestRowsByObjectId.values()));
    }

    private List<FireJudgeResult> queryEventRowsAtTime(int simTime) {
        LambdaQueryWrapper<FireJudgeResult> queryWrapper = Wrappers.<FireJudgeResult>lambdaQuery()
                .eq(FireJudgeResult::getSimTime, simTime)
                .orderByAsc(FireJudgeResult::getSimTime)
                .orderByAsc(FireJudgeResult::getId);
        return fireJudgeResultMapper.selectList(queryWrapper);
    }

    private List<FireJudgeResult> queryEventRowsBetween(int fromExclusive, int toInclusive) {
        LambdaQueryWrapper<FireJudgeResult> queryWrapper = Wrappers.<FireJudgeResult>lambdaQuery()
                .gt(FireJudgeResult::getSimTime, fromExclusive)
                .le(FireJudgeResult::getSimTime, toInclusive)
                .orderByAsc(FireJudgeResult::getSimTime)
                .orderByAsc(FireJudgeResult::getId);
        return fireJudgeResultMapper.selectList(queryWrapper);
    }

    private Map<Integer, RoomObjectHis> indexByRoomObjectId(List<RoomObjectHis> rows) {
        Map<Integer, RoomObjectHis> rowsByObjectId = new LinkedHashMap<>();
        for (RoomObjectHis row : rows) {
            rowsByObjectId.put(row.getRoomObjectId(), row);
        }
        return rowsByObjectId;
    }

    private List<RoomObjectHis> sortByRoomObjectId(List<RoomObjectHis> rows) {
        rows.sort(Comparator
                .comparing(RoomObjectHis::getRoomObjectId)
                .thenComparing(RoomObjectHis::getTargetId, Comparator.nullsLast(Comparator.naturalOrder())));
        return rows;
    }

    private List<RoomObjectHis> markSourceType(List<RoomObjectHis> rows, String sourceType) {
        rows.forEach(row -> row.setSourceType(sourceType));
        return rows;
    }

    private List<RoomObjectHis> cloneDataList(List<RoomObjectHis> dataList, String sourceType) {
        List<RoomObjectHis> clones = new ArrayList<>(dataList.size());
        for (RoomObjectHis item : dataList) {
            RoomObjectHis clone = new RoomObjectHis();
            BeanUtils.copyProperties(item, clone);
            clone.setSourceType(sourceType);
            clones.add(clone);
        }
        return clones;
    }

    private List<FireJudgeResult> cloneEventDataList(List<FireJudgeResult> dataList) {
        List<FireJudgeResult> clones = new ArrayList<>(dataList.size());
        for (FireJudgeResult item : dataList) {
            FireJudgeResult clone = new FireJudgeResult();
            BeanUtils.copyProperties(item, clone);
            clones.add(clone);
        }
        return clones;
    }
}
