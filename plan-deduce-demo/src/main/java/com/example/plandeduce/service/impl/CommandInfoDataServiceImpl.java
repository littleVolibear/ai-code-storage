package com.example.plandeduce.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.plandeduce.config.DynamicDataSourceContextHolder;
import com.example.plandeduce.mapper.CommandInfoMapper;
import com.example.plandeduce.model.CommandInfo;
import com.example.plandeduce.model.ProgressRangeQuery;
import com.example.plandeduce.model.ProgressSnapshotQuery;
import com.example.plandeduce.service.CommandInfoDataService;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class CommandInfoDataServiceImpl implements CommandInfoDataService {
    private final CommandInfoMapper commandInfoMapper;
    private final Map<String, Map<Integer, Map<Integer, List<CommandInfo>>>> fullSnapshotCache = new ConcurrentHashMap<>();

    /** 注入指令信息表访问器。 */
    public CommandInfoDataServiceImpl(CommandInfoMapper commandInfoMapper) {
        this.commandInfoMapper = commandInfoMapper;
    }

    /** 预热指令信息 0 秒基础快照。 */
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

    /** 查询指令信息全量快照并返回克隆结果。 */
    @Override
    public List<CommandInfo> queryFullData(ProgressSnapshotQuery snapshotQuery) {
        String dbName = snapshotQuery.getDbName();
        DynamicDataSourceContextHolder.set(dbName);
        try {
            return cloneDataList(getFullSnapshotAtCachePoint(snapshotQuery));
        } finally {
            DynamicDataSourceContextHolder.clear();
        }
    }

    /** 查询指令信息区间增量记录。 */
    @Override
    public List<CommandInfo> queryIncrementalData(ProgressRangeQuery rangeQuery) {
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

    /** 查询快照阶段使用的指令信息补丁最终态。 */
    @Override
    public List<CommandInfo> querySnapshotIncrementalData(ProgressRangeQuery rangeQuery) {
        String dbName = rangeQuery.getDbName();
        Integer fromExclusive = rangeQuery.getFromExclusive();
        Integer toInclusive = rangeQuery.getToInclusive();
        DynamicDataSourceContextHolder.set(dbName);
        try {
            if (toInclusive == null || fromExclusive == null || toInclusive <= fromExclusive) {
                return new ArrayList<>();
            }
            return cloneDataList(sortByObjId(new ArrayList<>(indexByObjId(queryRowsBetween(fromExclusive, toInclusive)).values())));
        } finally {
            DynamicDataSourceContextHolder.clear();
        }
    }

    /** 确保指定全量间隔下的指令信息快照缓存已初始化。 */
    private void ensureSnapshotCacheInitialized(ProgressSnapshotQuery snapshotQuery) {
        ProgressSnapshotQuery normalizedSnapshotQuery = normalizeSnapshotQuery(snapshotQuery);
        if (normalizedSnapshotQuery.getIntervalSeconds() <= 0) {
            throw new IllegalArgumentException("全量保存间隔必须大于 0 秒");
        }
        Map<Integer, List<CommandInfo>> snapshotCache = getCacheByTime(normalizedSnapshotQuery);
        if (snapshotCache.get(0) == null) {
            snapshotCache.putIfAbsent(0, buildZeroPointSnapshot());
        }
    }

    /** 获取指定秒点的指令信息全量快照。 */
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

    /** 基于上一个全量点滚动构造当前指令信息快照。 */
    private List<CommandInfo> buildFullSnapshotAtPoint(ProgressSnapshotQuery snapshotQuery) {
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
        Map<Integer, CommandInfo> mergedRowsByObjId = indexByObjId(getFullSnapshotAtCachePoint(previousSnapshotQuery));
        for (CommandInfo row : queryRowsBetween(previousFullTime, targetTime)) {
            mergedRowsByObjId.put(row.getObjId(), row);
        }
        return sortByObjId(new ArrayList<>(mergedRowsByObjId.values()));
    }

    /** 构造 0 秒指令信息快照。 */
    private List<CommandInfo> buildZeroPointSnapshot() {
        return sortByObjId(new ArrayList<>(indexByObjId(queryRowsAtTime(0)).values()));
    }

    /** 查询单秒内的指令信息记录。 */
    private List<CommandInfo> queryRowsAtTime(int simTimeValue) {
        int startMillisecond = toMillisecondStart(simTimeValue);
        int endMillisecondExclusive = toMillisecondEndExclusive(simTimeValue);
        LambdaQueryWrapper<CommandInfo> queryWrapper = Wrappers.<CommandInfo>lambdaQuery()
                .ge(CommandInfo::getSimTime, startMillisecond)
                .lt(CommandInfo::getSimTime, endMillisecondExclusive)
                .orderByAsc(CommandInfo::getSimTime)
                .orderByAsc(CommandInfo::getObjId)
                .orderByAsc(CommandInfo::getId);
        return commandInfoMapper.selectList(queryWrapper);
    }

    /** 查询区间内的指令信息记录。 */
    private List<CommandInfo> queryRowsBetween(int fromExclusive, int toInclusive) {
        int startMillisecond = toMillisecondStart(fromExclusive + 1);
        int endMillisecondExclusive = toMillisecondEndExclusive(toInclusive);
        LambdaQueryWrapper<CommandInfo> queryWrapper = Wrappers.<CommandInfo>lambdaQuery()
                .ge(CommandInfo::getSimTime, startMillisecond)
                .lt(CommandInfo::getSimTime, endMillisecondExclusive)
                .orderByAsc(CommandInfo::getSimTime)
                .orderByAsc(CommandInfo::getObjId)
                .orderByAsc(CommandInfo::getId);
        return commandInfoMapper.selectList(queryWrapper);
    }

    /** 获取指定库和间隔对应的指令信息快照缓存槽位。 */
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

    /** 按 objId 建立覆盖索引。 */
    private Map<Integer, CommandInfo> indexByObjId(List<CommandInfo> rows) {
        Map<Integer, CommandInfo> rowsByObjId = new LinkedHashMap<>();
        for (CommandInfo row : rows) {
            rowsByObjId.put(row.getObjId(), row);
        }
        return rowsByObjId;
    }

    /** 按 objId 排序并过滤空记录。 */
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

    /** 克隆指令信息结果，避免调用方改动缓存实例。 */
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
