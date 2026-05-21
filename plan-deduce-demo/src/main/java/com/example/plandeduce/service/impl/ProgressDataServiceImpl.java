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
 * 单库快照查询与缓存实现。
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

    /** 预热指定全量间隔下的 0 秒快照。 */
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

    /** 读取全量秒点快照，未命中时现场构造并回填缓存。 */
    @Override
    public List<RoomObjectHis> queryCachedFullData(String dbName, int intervalSeconds, int simTime) {
        return cloneDataList(getFullSnapshotAtCachePoint(intervalSeconds, Math.max(simTime, 0)), "FULL");
    }

    /** 查询播放帧使用的区间原始数据。 */
    @Override
    public List<RoomObjectHis> queryIncrementalData(String dbName, Integer fromExclusive, Integer toInclusive) {
        if (toInclusive == null || fromExclusive == null || toInclusive <= fromExclusive) {
            return new ArrayList<>();
        }
        return cloneDataList(queryRowsBetween(fromExclusive, toInclusive), "INCREMENT");
    }

    /** 查询快照类消息使用的区间最终态补丁。 */
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

    /** 查询最大业务秒点。 */
    @Override
    public Integer queryMaxSimTime(String dbName) {
        LambdaQueryWrapper<RoomObjectHis> queryWrapper = Wrappers.<RoomObjectHis>lambdaQuery()
                .select(RoomObjectHis::getSimTime)
                .orderByDesc(RoomObjectHis::getSimTime)
                .last("LIMIT 1");
        RoomObjectHis latest = roomObjectMapper.selectOne(queryWrapper);
        return latest == null || latest.getSimTime() == null ? 0 : latest.getSimTime();
    }

    /** 读取事件全量快照。 */
    @Override
    public List<FireJudgeResult> queryEventFullData(String dbName, int intervalSeconds, Integer simTime) {
        return cloneEventDataList(getEventFullSnapshotAtCachePoint(intervalSeconds, Math.max(simTime == null ? 0 : simTime, 0)));
    }

    /** 查询事件增量区间。 */
    @Override
    public List<FireJudgeResult> queryEventIncrementalData(String dbName, Integer fromExclusive, Integer toInclusive) {
        if (toInclusive == null || fromExclusive == null || toInclusive <= fromExclusive) {
            return new ArrayList<>();
        }
        return cloneEventDataList(queryEventRowsBetween(fromExclusive, toInclusive));
    }

    /** 返回开始时间配置。 */
    @Override
    public String queryRoomStartTime(String dbName) {
        LambdaQueryWrapper<RoomInfo> queryWrapper = Wrappers.<RoomInfo>lambdaQuery()
                .orderByAsc(RoomInfo::getId)
                .last("LIMIT 1");
        RoomInfo firstRow = roomInfoMapper.selectOne(queryWrapper);
        return firstRow == null ? null : firstRow.getStartTime();
    }

    /** 获取指定秒点的对象全量快照。 */
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

    /** 获取指定秒点的事件全量快照。 */
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

    /** 基于上一个全量点滚动构造事件快照。 */
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

    /** 构造 0 秒事件快照。 */
    private List<FireJudgeResult> buildZeroPointEventSnapshot() {
        return queryEventRowsAtTime(0);
    }

    /** 基于上一个全量点滚动构造对象快照。 */
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

    /** 构造 0 秒对象快照。 */
    private List<RoomObjectHis> buildZeroPointSnapshot() {
        return markSourceType(sortByRoomObjectId(queryRowsAtTime(0)), "FULL");
    }

    /** 查询某一秒的对象记录。 */
    private List<RoomObjectHis> queryRowsAtTime(int simTime) {
        LambdaQueryWrapper<RoomObjectHis> queryWrapper = Wrappers.<RoomObjectHis>lambdaQuery()
                .eq(RoomObjectHis::getSimTime, simTime)
                .orderByAsc(RoomObjectHis::getRoomObjectId);
        return roomObjectMapper.selectList(queryWrapper);
    }

    /** 查询区间内的对象记录。 */
    private List<RoomObjectHis> queryRowsBetween(int fromExclusive, int toInclusive) {
        LambdaQueryWrapper<RoomObjectHis> queryWrapper = Wrappers.<RoomObjectHis>lambdaQuery()
                .gt(RoomObjectHis::getSimTime, fromExclusive)
                .le(RoomObjectHis::getSimTime, toInclusive)
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

    /** 查询某一秒的事件记录。 */
    private List<FireJudgeResult> queryEventRowsAtTime(int simTimeValue) {
        LambdaQueryWrapper<FireJudgeResult> queryWrapper = Wrappers.<FireJudgeResult>lambdaQuery()
                .eq(FireJudgeResult::getSimTime, simTimeValue)
                .orderByAsc(FireJudgeResult::getSimTime)
                .orderByAsc(FireJudgeResult::getId);
        return fireJudgeResultMapper.selectList(queryWrapper);
    }

    /** 查询区间内的事件记录。 */
    private List<FireJudgeResult> queryEventRowsBetween(int fromExclusive, int toInclusive) {
        LambdaQueryWrapper<FireJudgeResult> queryWrapper = Wrappers.<FireJudgeResult>lambdaQuery()
                .gt(FireJudgeResult::getSimTime, fromExclusive)
                .le(FireJudgeResult::getSimTime, toInclusive)
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
        rows.sort(Comparator
                .comparing(RoomObjectHis::getRoomObjectId)
                .thenComparing(RoomObjectHis::getTargetId, Comparator.nullsLast(Comparator.naturalOrder())));
        return rows;
    }

    /** 标记快照来源类型。 */
    private List<RoomObjectHis> markSourceType(List<RoomObjectHis> rows, String sourceType) {
        rows.forEach(row -> row.setSourceType(sourceType));
        return rows;
    }

    /** 克隆对象数据，避免调用方改动缓存对象。 */
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

    /** 克隆事件数据，避免调用方改动缓存对象。 */
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
