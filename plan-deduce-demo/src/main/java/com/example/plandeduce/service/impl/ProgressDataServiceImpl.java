package com.example.plandeduce.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.example.plandeduce.mapper.FireJudgeResultMapper;
import com.example.plandeduce.mapper.RoomObjectHisMapper;
import com.example.plandeduce.mapper.RoomInfoMapper;
import com.example.plandeduce.model.FireJudgeResult;
import com.example.plandeduce.model.RoomObjectHis;
import com.example.plandeduce.model.RoomInfo;
import com.example.plandeduce.service.ProgressDataService;
import org.springframework.beans.BeanUtils;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

@Service
/**
 * 数据查询服务实现。
 * 当前系统通过 dbName 动态切换库，但固定查询 OBJ_ROOM_HIS 表，并按“全量 + 增量”的方式给播放任务供数。
 * 其中静态基础字段按棋子只加载一次，运行态变化字段按时间轴动态查询。
 * 注意：
 * 1. 这个类同时承担“动态切库、查询组装、缓存复用、快照克隆”四个职责，后续改动要特别注意边界；
 * 2. fullSnapshotCache / pieceBaseCache 返回的数据会在业务层被重复读取，因此缓存对象本身不能直接暴露给调用方修改；
 * 3. 当前快照协议依赖 sourceType、全量字段补齐、按时间有序返回三件事，任何一个改坏都会影响前端拼装逻辑。
 */
public class ProgressDataServiceImpl implements ProgressDataService {
    private static final Pattern DB_NAME_PATTERN = Pattern.compile("[A-Za-z0-9_]+");
    private static final String[] DYNAMIC_STATE_COLUMNS = {
            "ROOM_OBJECT_ID", "TARGET_ID", "SIM_TIME", "OBJ_CURRENT_VEHICLE_NUM", "OBJ_SON_NUM",
            "CURRENT_POS", "NEXT_POS", "DIRECTION", "CURRENT_SPEED", "MOVING", "STOPPING",
            "SUPPRESSED", "COOLING", "IF_PLAN_ID", "HIDE_STATUS", "FIRING_OR_MARCHING",
            "COMBAT_HEIGHT", "WEAPON_ID", "WEAPON_RANGE", "WEAPON_NUM", "FORT_OBJ_ID",
            "SUPPORTING", "LOAD_STATE", "VISIBLE", "CREATE_TIME", "IF_COUNTDOWN", "WEAPON_COUNTDOWN"
    };
    private static final String[] STATIC_BASE_CACHE_COLUMNS = {
            "ROOM_OBJECT_ID", "SCENARIO_ID", "ROOM_ID", "ORG_SEQ", "OBJ_CODE", "OBJ_VEHICLE_NUM",
            "OBJ_LEVEL", "OBJ_PROTO_ID", "SIDE", "OBJ_NAME", "ICON_3D", "ICON_2D", "ICON_SIZE",
            "OBJ_TYPE", "COMBAT_GROUP", "STEALTH_VALUE", "TARGET_VALUE", "WORK_OBSTACLE",
            "ENTRY_TIME", "PERSON_COUNT", "ARMOR_LEVEL", "COMBAT_MODE", "MOVING_FIRE_CAPABILITY",
            "WEAPON_DIVERSITY", "RECON_COMMS_TYPE", "CARRYING_CAPACITY", "MOVE_STOP_CONVERT_TIME",
            "ATTACK_INTERVAL", "OFF_ROAD_MAX_SPEED", "NORMAL_ROAD_MAX_SPEED", "FAST_ROAD_MAX_SPEED",
            "HIGHWAY_MAX_SPEED", "RECON_PERSONNEL", "RECON_VEHICLE", "RECON_AIR", "RECON_OBSTACLE",
            "RECON_MINEFIELD", "DEPLOYMENT_DURATION", "WITHDRAWAL_DURATION", "CAMOUFLAGE_DURATION",
            "NORMAL_AMMUNITION", "HEAVY_AMMUNITION", "MEDIUM_AMMUNITION", "SMALL_AMMUNITION",
            "AMMUNITION_CONSUMPTION", "WEAPON_LIST", "CARRYING_OBJ_LIST", "OPERATOR_ID", "OBJ_PARENT_ID",
            "AI_INTERFACE", "OBJ_SON_ORIGINAL_ID", "FIRST_CATEGORY_CODE", "SECOND_CATEGORY_CODE",
            "AIR_DEFENSE_RANGE"
    };
    private static final String[] MAX_SIM_TIME_COLUMNS = {"SIM_TIME", "TARGET_ID"};
    private static final String[] CHANGING_STATE_COPY_IGNORE_PROPERTIES = {
            "objCurrentVehicleNum", "objSonNum", "currentPos", "nextPos", "direction", "currentSpeed",
            "moving", "stopping", "suppressed", "cooling", "ifPlanId", "hideStatus", "firingOrMarching",
            "combatHeight", "weaponId", "weaponRange", "weaponNum", "fortObjId", "supporting", "loadState",
            "visible", "simTime", "createTime", "ifCountdown", "weaponCountdown", "targetId", "sourceType"
    };

    private final DataSourceProperties dataSourceProperties;
    private final Map<String, RoomObjectHisMapper> mapperCache = new ConcurrentHashMap<>();
    private final Map<String, FireJudgeResultMapper> eventMapperCache = new ConcurrentHashMap<>();
    private final Map<String, RoomInfoMapper> roomInfoMapperCache = new ConcurrentHashMap<>();
    /**
     * 按 dbName 缓存“棋子静态基础信息”。
     * key: dbName
     * value: roomObjectId -> sim_time=0 对应的基础字段对象
     * 作用：
     * 1. 避免每次查快照都重复回表读取不随时间变化的字段；
     * 2. 让 queryFullData/queryIncrementalData 只查变化字段，再由这里补齐成前端可直接消费的完整对象。
     * 注意：
     * 1. 这里假设 sim_time=0 的基础信息在整条时间轴上成立；
     * 2. value 中缓存的是可复用对象本体，调用方不应直接修改它。
     */
    private final Map<String, Map<Integer, RoomObjectHis>> pieceBaseCache = new ConcurrentHashMap<>();
    /**
     * 按“dbName + 全量间隔”缓存整条时间轴上的全量快照。
     * key: dbName:intervalSeconds
     * value: fullTime 秒点 -> 该秒点完整快照列表
     * 作用：
     * 1. 播放时命中全量秒点可以直接从内存返回，减少实时查库开销；
     * 2. 为“最近全量 + 之后增量”的协议提供稳定基座。
     * 注意：
     * 1. 缓存中的列表和元素对象都属于共享读数据，外部消费前必须 clone；
     * 2. 间隔越小、数据量越大，这个缓存占用的内存越高；
     * 3. 当前没有失效机制，默认底层数据在任务生命周期内不变。
     */
    private final Map<String, Map<Integer, List<RoomObjectHis>>> fullSnapshotCache = new ConcurrentHashMap<>();
    /**
     * 按“dbName + 全量间隔”缓存 FireJudgeResult 的全量秒点数据。
     * key: dbName:intervalSeconds
     * value: fullTime 秒点 -> 该秒点事件列表
     * 作用：
     * 1. 让事件表和 OBJ_ROOM_HIS 采用同一套“0 点预热 + 整间隔全量点缓存”的策略；
     * 2. 避免每次命中全量点都直接回表查询。
     */
    private final Map<String, Map<Integer, List<FireJudgeResult>>> eventFullSnapshotCache = new ConcurrentHashMap<>();

    /**
     * 只注入基础数据源配置，真正的库连接和 Mapper 会按 dbName 懒加载创建。
     * 这样可以避免初始化阶段就为所有库建连接，也方便后续通过缓存复用 Mapper。
     */
    public ProgressDataServiceImpl(DataSourceProperties dataSourceProperties) {
        this.dataSourceProperties = dataSourceProperties;
    }

    /**
     * 按当前全量间隔预热基础全量快照。
     * 当前实现只强制准备 0 点全量，其他整间隔全量点会在首次访问时按需构造。
     * 注意：
     * 1. 缓存 key 同时包含 dbName 和 intervalSeconds，不同全量间隔会生成独立缓存；
     * 2. 这里使用 computeIfAbsent，只会在首次访问时装载，后续不会自动刷新底层数据；
     * 3. 如果底表数据会在任务运行中变更，这个缓存策略就不再安全，需要额外失效机制；
     * 4. 这样做可以避免初始化时一次性扫描所有间隔点，符合“sendPlanDeduce 只发 0 点全量”的新规则。
     */
    @Override
    public void preloadFullSnapshots(String dbName, int intervalSeconds) {
        if (intervalSeconds <= 0) {
            throw new IllegalArgumentException("全量保存间隔必须大于 0 秒");
        }
        String cacheKey = buildFullSnapshotCacheKey(dbName, intervalSeconds);
        Map<Integer, List<RoomObjectHis>> cache = fullSnapshotCache.computeIfAbsent(cacheKey, key -> new ConcurrentHashMap<>());
        cache.computeIfAbsent(0, key -> buildZeroPointSnapshot(dbName));
        Map<Integer, List<FireJudgeResult>> eventCache = eventFullSnapshotCache.computeIfAbsent(cacheKey, key -> new ConcurrentHashMap<>());
        eventCache.computeIfAbsent(0, key -> buildZeroPointEventSnapshot(dbName));
    }

    /**
     * 从预加载缓存中取指定全量秒点的数据。
     * 注意：
     * 1. 返回前会做一次克隆，避免调用方修改缓存对象本身；
     * 2. 如果指定秒点没有命中缓存，会按“上一全量点 + 当前区间最后生效状态”现场构造；
     * 3. 返回数据的 sourceType 会统一改写为 FULL，供前端或联调用日志识别来源。
     */
    @Override
    public List<RoomObjectHis> queryCachedFullData(String dbName, int intervalSeconds, int simTime) {
        preloadFullSnapshots(dbName, intervalSeconds);
        Map<Integer, List<RoomObjectHis>> cache = fullSnapshotCache
                .computeIfAbsent(buildFullSnapshotCacheKey(dbName, intervalSeconds), key -> new ConcurrentHashMap<>());
        List<RoomObjectHis> cachedData = cache.computeIfAbsent(simTime, key -> buildFullSnapshotAtPoint(dbName, intervalSeconds, simTime));
        return cachedData == null ? new ArrayList<>() : cloneDataList(cachedData, "FULL");
    }

    /**
     * 查询全量秒点之后到当前秒之间的增量数据。
     * fromExclusive 是最近全量秒点，toInclusive 是当前播放秒点。
     * 注意：
     * 1. 区间语义是 (fromExclusive, toInclusive]，左开右闭，这也是播放过程中避免重复发上一秒数据的关键；
     * 2. 当 toInclusive <= fromExclusive 时返回空列表，不抛异常；
     * 3. 结果保留区间内全部变化记录，供 PLAY 消息表现逐帧过程；
     * 4. 返回顺序按 simTime、roomObjectId、targetId 升序，前端和测试都依赖这个稳定顺序。
     */
    @Override
    public List<RoomObjectHis> queryIncrementalData(String dbName, Integer fromExclusive, Integer toInclusive) {
        if (toInclusive == null || fromExclusive == null || toInclusive <= fromExclusive) {
            return new ArrayList<>();
        }
        return withPieceBaseAndSourceType(queryDynamicRows(dbName, fromExclusive, toInclusive), dbName, "INCREMENT");
    }

    /**
     * 查询快照类消息使用的增量数据。
     * 对同一个 roomObjectId，只保留区间内 simTime 最大的一条，表示该对象在目标时刻前最后一次生效状态。
     * 这套语义用于 INIT / SKIP / INTERVAL：前端需要的是“目标时刻最终状态补丁”，不是区间内全部变化历史。
     */
    @Override
    public List<RoomObjectHis> querySnapshotIncrementalData(String dbName, Integer fromExclusive, Integer toInclusive) {
        if (toInclusive == null || fromExclusive == null || toInclusive <= fromExclusive) {
            return new ArrayList<>();
        }
        Map<Integer, RoomObjectHis> latestRows = new LinkedHashMap<>();
        for (RoomObjectHis row : queryDynamicRows(dbName, fromExclusive, toInclusive)) {
            latestRows.put(row.getRoomObjectId(), row);
        }
        List<RoomObjectHis> snapshotRows = new ArrayList<>(latestRows.values());
        snapshotRows.sort(Comparator.comparing(RoomObjectHis::getRoomObjectId));
        return withPieceBaseAndSourceType(snapshotRows, dbName, "INCREMENT");
    }

    /**
     * 查询当前库中最大的业务秒点，用于判断播放是否到达终点。
     * 注意：
     * 1. 返回 0 表示“没有数据”或“最大秒点就是 0”，上层如果要区分这两种情况需要另加判断；
     * 2. 这里只关心最大 simTime，不关心该秒点有多少条棋子数据。
     */
    @Override
    public Integer queryMaxSimTime(String dbName) {
        QueryWrapper<RoomObjectHis> queryWrapper = Wrappers.<RoomObjectHis>query()
                .select(MAX_SIM_TIME_COLUMNS)
                .orderByDesc("SIM_TIME")
                .last("LIMIT 1");
        RoomObjectHis latest = getMapper(dbName).selectOne(queryWrapper);
        return latest == null || latest.getSimTime() == null ? 0 : latest.getSimTime();
    }

    @Override
    public List<FireJudgeResult> queryEventFullData(String dbName, int intervalSeconds, Integer simTime) {
        if (simTime == null || simTime < 0) {
            return new ArrayList<>();
        }
        int targetTime = Math.max(simTime, 0);
        preloadFullSnapshots(dbName, intervalSeconds);
        Map<Integer, List<FireJudgeResult>> cache = eventFullSnapshotCache
                .computeIfAbsent(buildFullSnapshotCacheKey(dbName, intervalSeconds), key -> new ConcurrentHashMap<>());
        List<FireJudgeResult> cachedData = cache.computeIfAbsent(targetTime, key -> buildEventFullSnapshotAtPoint(dbName, intervalSeconds, targetTime));
        return cloneEventDataList(cachedData);
    }

    @Override
    public List<FireJudgeResult> queryEventIncrementalData(String dbName, Integer fromExclusive, Integer toInclusive) {
        if (toInclusive == null || fromExclusive == null || toInclusive <= fromExclusive) {
            return new ArrayList<>();
        }
        return queryEventRowsBetween(dbName, fromExclusive, toInclusive);
    }

    private List<FireJudgeResult> queryEventRowsAtTime(String dbName, int simTime) {
        LambdaQueryWrapper<FireJudgeResult> queryWrapper = Wrappers.<FireJudgeResult>lambdaQuery()
                .eq(FireJudgeResult::getRoomId, validateDbName(dbName))
                .eq(FireJudgeResult::getSimTime, simTime)
                .orderByAsc(FireJudgeResult::getSimTime)
                .orderByAsc(FireJudgeResult::getId);
        return getEventMapper(dbName).selectList(queryWrapper);
    }

    private List<FireJudgeResult> queryEventRowsBetween(String dbName, int fromExclusive, int toInclusive) {
        LambdaQueryWrapper<FireJudgeResult> queryWrapper = Wrappers.<FireJudgeResult>lambdaQuery()
                .eq(FireJudgeResult::getRoomId, validateDbName(dbName))
                .gt(FireJudgeResult::getSimTime, fromExclusive)
                .le(FireJudgeResult::getSimTime, toInclusive)
                .orderByAsc(FireJudgeResult::getSimTime)
                .orderByAsc(FireJudgeResult::getId);
        return getEventMapper(dbName).selectList(queryWrapper);
    }

    private List<FireJudgeResult> buildEventFullSnapshotAtPoint(String dbName, int intervalSeconds, int simTime) {
        int targetTime = Math.max(simTime, 0);
        if (targetTime == 0) {
            return buildZeroPointEventSnapshot(dbName);
        }
        int interval = Math.max(intervalSeconds, 1);
        int previousFullTime = Math.max(targetTime - interval, 0);
        List<FireJudgeResult> previous = queryEventFullDataAtCachePoint(dbName, intervalSeconds, previousFullTime);
        List<FireJudgeResult> merged = new ArrayList<>(previous.size());
        merged.addAll(previous);
        merged.addAll(queryEventIncrementalData(dbName, previousFullTime, targetTime));
        return merged;
    }

    private List<FireJudgeResult> queryEventFullDataAtCachePoint(String dbName, int intervalSeconds, int simTime) {
        preloadFullSnapshots(dbName, intervalSeconds);
        Map<Integer, List<FireJudgeResult>> cache = eventFullSnapshotCache
                .computeIfAbsent(buildFullSnapshotCacheKey(dbName, intervalSeconds), key -> new ConcurrentHashMap<>());
        List<FireJudgeResult> cachedData = cache.computeIfAbsent(simTime, key -> buildEventFullSnapshotAtPoint(dbName, intervalSeconds, simTime));
        return cloneEventDataList(cachedData);
    }

    private List<FireJudgeResult> buildZeroPointEventSnapshot(String dbName) {
        return queryEventRowsAtTime(dbName, 0);
    }

    @Override
    public String queryRoomStartTime(String dbName) {
        String validatedDbName = validateDbName(dbName);
        LambdaQueryWrapper<RoomInfo> byRoomId = Wrappers.<RoomInfo>lambdaQuery()
                .eq(RoomInfo::getRoomId, validatedDbName)
                .orderByAsc(RoomInfo::getId)
                .last("LIMIT 1");
        RoomInfo matched = getRoomInfoMapper(validatedDbName).selectOne(byRoomId);
        if (matched != null) {
            return matched.getStartTime();
        }
        LambdaQueryWrapper<RoomInfo> first = Wrappers.<RoomInfo>lambdaQuery()
                .orderByAsc(RoomInfo::getId)
                .last("LIMIT 1");
        RoomInfo firstRow = getRoomInfoMapper(validatedDbName).selectOne(first);
        return firstRow == null ? null : firstRow.getStartTime();
    }

    /**
     * 给查询结果打上来源标签，并把缓存的静态字段补回动态快照里。
     * 注意：
     * 1. 这里会直接修改传入的 dataList 元素，而不是另建新对象；
     * 2. 静态基础字段来源于 sim_time=0 的缓存快照，因此要求这批基础信息在整个回放期间保持不变；
     * 3. 如果某个 roomObjectId 在基础缓存中不存在，就只保留查询出来的动态字段，不会抛错。
     */
    private List<RoomObjectHis> withPieceBaseAndSourceType(List<RoomObjectHis> dataList, String dbName, String sourceType) {
        Map<Integer, RoomObjectHis> pieceBaseInfoMap = getPieceBaseInfo(dbName);
        dataList.forEach(item -> {
            item.setSourceType(sourceType);
            RoomObjectHis baseInfo = pieceBaseInfoMap.get(item.getRoomObjectId());
            if (baseInfo != null) {
                copyStaticBaseFields(baseInfo, item);
            }
        });
        return dataList;
    }

    /**
     * 构造指定秒点的完整全量快照。
     * 实现方式是：
     * 1. 先取上一个全量点快照作为基座；
     * 2. 再取 (previousFullTime, targetTime] 内每个 roomObjectId 最后一次生效状态覆盖到基座上；
     * 3. 得到 targetTime 时刻的最终完整状态。
     * 注意：
     * 1. 这里返回的是“目标时刻最终态”，不是区间内全部历史；
     * 2. 当 targetTime=0 时，直接返回 0 点基座；
     * 3. 对于第 N 个全量点，这里不会再从 0 点重复扫描，而是基于第 N-1 个全量点继续滚动生成。
     */
    private List<RoomObjectHis> buildFullSnapshotAtPoint(String dbName, int intervalSeconds, int simTime) {
        int targetTime = Math.max(simTime, 0);
        if (targetTime == 0) {
            return buildZeroPointSnapshot(dbName);
        }
        int interval = Math.max(intervalSeconds, 1);
        int previousFullTime = Math.max(targetTime - interval, 0);
        Map<Integer, RoomObjectHis> mergedRows = indexByRoomObjectId(queryCachedFullData(dbName, intervalSeconds, previousFullTime));
        for (RoomObjectHis latestRow : queryLatestRowsByRoomObjectId(dbName, previousFullTime, targetTime)) {
            mergedRows.put(latestRow.getRoomObjectId(), latestRow);
        }
        return withPieceBaseAndSourceType(sortByRoomObjectId(new ArrayList<>(mergedRows.values())), dbName, "FULL");
    }

    private List<RoomObjectHis> buildZeroPointSnapshot(String dbName) {
        return withPieceBaseAndSourceType(sortByRoomObjectId(queryRowsAtTime(dbName, 0)), dbName, "FULL");
    }

    /**
     * 查询并缓存棋子静态字段。
     * 当前实现按 sim_time=0 加载每个棋子的基础信息，后续快照查询只动态取位置、朝向、速度、状态等变化字段。
     * 注意：
     * 1. 缓存按 dbName 隔离，不同库之间不会共享；
     * 2. 如果源表里 sim_time=0 的基础数据不完整，后续所有快照补齐都会受影响；
     * 3. 这里依赖 validateDbName() 统一做库名合法性校验。
     */
    private Map<Integer, RoomObjectHis> getPieceBaseInfo(String dbName) {
        String validatedDbName = validateDbName(dbName);
        return pieceBaseCache.computeIfAbsent(validatedDbName, this::loadPieceBaseInfo);
    }

    /**
     * 从底表加载 sim_time=0 的静态基础字段，并按 roomObjectId 建索引。
     * 注意：
     * 1. 使用 putIfAbsent 是为了在同一个 roomObjectId 有多条记录时保留第一条；
     * 2. 这里假设 sim_time=0 足以代表整条时间轴的基础信息，如果业务规则改变，需要调整建模方式；
     * 3. 只查 STATIC_BASE_CACHE_COLUMNS，避免把运行态变化字段一起缓存造成额外内存占用。
     */
    private Map<Integer, RoomObjectHis> loadPieceBaseInfo(String dbName) {
        QueryWrapper<RoomObjectHis> queryWrapper = Wrappers.<RoomObjectHis>query()
                .select(STATIC_BASE_CACHE_COLUMNS)
                .eq("SIM_TIME", 0)
                .orderByAsc("ROOM_OBJECT_ID");
        List<RoomObjectHis> rows = getMapper(dbName).selectList(queryWrapper);
        Map<Integer, RoomObjectHis> pieceBaseInfoMap = new LinkedHashMap<>();
        for (RoomObjectHis row : rows) {
            pieceBaseInfoMap.putIfAbsent(row.getRoomObjectId(), row);
        }
        return pieceBaseInfoMap;
    }

    /**
     * 查询某个精确秒点上的原始动态记录。
     * 这里不做“最后生效值归并”，用于构造 0 点基座快照。
     */
    private List<RoomObjectHis> queryRowsAtTime(String dbName, int simTime) {
        QueryWrapper<RoomObjectHis> queryWrapper = Wrappers.<RoomObjectHis>query()
                .select(DYNAMIC_STATE_COLUMNS)
                .eq("SIM_TIME", simTime)
                .orderByAsc("ROOM_OBJECT_ID");
        return getMapper(dbName).selectList(queryWrapper);
    }

    /**
     * 查询一个开闭区间内的原始动态变化记录。
     * 结果会完整保留区间历史，不做 roomObjectId 维度去重。
     */
    private List<RoomObjectHis> queryDynamicRows(String dbName, Integer fromExclusive, Integer toInclusive) {
        QueryWrapper<RoomObjectHis> queryWrapper = Wrappers.<RoomObjectHis>query()
                .select(DYNAMIC_STATE_COLUMNS)
                .gt("SIM_TIME", fromExclusive)
                .le("SIM_TIME", toInclusive)
                .orderByAsc("SIM_TIME")
                .orderByAsc("ROOM_OBJECT_ID")
                .orderByAsc("TARGET_ID");
        return getMapper(dbName).selectList(queryWrapper);
    }

    /**
     * 查询区间内每个 roomObjectId 最后一次生效的动态记录。
     * 用于把一个变化过程沉淀成“目标时刻最终态”。
     */
    private List<RoomObjectHis> queryLatestRowsByRoomObjectId(String dbName, Integer fromExclusive, Integer toInclusive) {
        Map<Integer, RoomObjectHis> latestRows = new LinkedHashMap<>();
        for (RoomObjectHis row : queryDynamicRows(dbName, fromExclusive, toInclusive)) {
            latestRows.put(row.getRoomObjectId(), row);
        }
        return sortByRoomObjectId(new ArrayList<>(latestRows.values()));
    }

    private Map<Integer, RoomObjectHis> indexByRoomObjectId(List<RoomObjectHis> rows) {
        Map<Integer, RoomObjectHis> indexedRows = new LinkedHashMap<>();
        for (RoomObjectHis row : rows) {
            indexedRows.put(row.getRoomObjectId(), row);
        }
        return indexedRows;
    }

    /**
     * 按 roomObjectId 排序，必要时再按 targetId 做稳定次序。
     * 这样可以保证快照类结果在联调和测试里顺序稳定。
     */
    private List<RoomObjectHis> sortByRoomObjectId(List<RoomObjectHis> rows) {
        rows.sort(Comparator
                .comparing(RoomObjectHis::getRoomObjectId)
                .thenComparing(RoomObjectHis::getTargetId, Comparator.nullsLast(Comparator.naturalOrder())));
        return rows;
    }

    /**
     * 按库名获取 Mapper。
     * 每个 dbName 都会缓存一个独立的 MyBatis-Plus Mapper，避免重复创建连接配置。
     * 注意：
     * 1. 这里缓存的是 Mapper 实例，不是连接本身，连接池行为仍由底层 DataSource 决定；
     * 2. dbName 会先做合法性校验，避免非法字符进入 JDBC URL 拼装逻辑。
     */
    private RoomObjectHisMapper getMapper(String dbName) {
        String validatedDbName = validateDbName(dbName);
        return mapperCache.computeIfAbsent(validatedDbName, this::createMapper);
    }

    private FireJudgeResultMapper getEventMapper(String dbName) {
        String validatedDbName = validateDbName(dbName);
        return eventMapperCache.computeIfAbsent(validatedDbName, this::createEventMapper);
    }

    private RoomInfoMapper getRoomInfoMapper(String dbName) {
        String validatedDbName = validateDbName(dbName);
        return roomInfoMapperCache.computeIfAbsent(validatedDbName, this::createRoomInfoMapper);
    }

    /**
     * 动态创建某个库对应的 Mapper。
     * 当前项目使用内存 H2，因此这里通过改写 JDBC URL 的库名部分实现“动态切库”。
     * 注意：
     * 1. 这里为每个 dbName 创建独立 DataSource 和 SqlSessionTemplate，适合 demo/轻量场景；
     * 2. 如果未来库数量很多，或者运行时间很长，需要重新评估连接资源回收和缓存上限；
     * 3. 一旦创建失败，会包装成 IllegalStateException 直接向上抛出，便于快速暴露配置问题。
     */
    private RoomObjectHisMapper createMapper(String dbName) {
        try {
            return createMapper(dbName, RoomObjectHisMapper.class, "创建 MyBatis-Plus Mapper 失败");
        } catch (Exception e) {
            throw new IllegalStateException("创建 MyBatis-Plus Mapper 失败, dbName=" + dbName, e);
        }
    }

    private FireJudgeResultMapper createEventMapper(String dbName) {
        try {
            return createMapper(dbName, FireJudgeResultMapper.class, "创建 FireJudgeResult Mapper 失败");
        } catch (Exception e) {
            throw new IllegalStateException("创建 FireJudgeResult Mapper 失败, dbName=" + dbName, e);
        }
    }

    private RoomInfoMapper createRoomInfoMapper(String dbName) {
        try {
            return createMapper(dbName, RoomInfoMapper.class, "创建 RoomInfo Mapper 失败");
        } catch (Exception e) {
            throw new IllegalStateException("创建 RoomInfo Mapper 失败, dbName=" + dbName, e);
        }
    }

    private <T> T createMapper(String dbName, Class<T> mapperClass, String errorMessage) throws Exception {
        SqlSessionTemplate sqlSessionTemplate = createSqlSessionTemplate(dbName, mapperClass);
        try {
            return sqlSessionTemplate.getMapper(mapperClass);
        } catch (Exception e) {
            throw new IllegalStateException(errorMessage + ", dbName=" + dbName, e);
        }
    }

    private SqlSessionTemplate createSqlSessionTemplate(String dbName, Class<?>... mapperClasses) throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName(dataSourceProperties.getDriverClassName());
        dataSource.setUrl(buildJdbcUrl(dbName));
        dataSource.setUsername(dataSourceProperties.getUsername());
        dataSource.setPassword(dataSourceProperties.getPassword());

        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        factoryBean.setTypeAliasesPackage("com.example.plandeduce.model");

        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        Arrays.stream(mapperClasses).forEach(configuration::addMapper);
        factoryBean.setConfiguration(configuration);

        return new SqlSessionTemplate(factoryBean.getObject());
    }

    /**
     * 根据基础数据源 URL 构造指定库名的新 URL。
     * 当前已经兼容 H2 内存库，以及常见的 jdbc://host/dbName 形式。
     * 注意：
     * 1. 这里只做字符串替换，不会主动探测数据库是否真实存在；
     * 2. 如果底层 URL 结构和这里假设的不一致，会直接抛异常，避免悄悄连错库；
     * 3. dbName 入参应当已经经过 validateDbName() 校验，但这里仍应只接收可信调用链传入的值。
     */
    private String buildJdbcUrl(String dbName) {
        String baseUrl = dataSourceProperties.getUrl();
        if (baseUrl == null || baseUrl.isEmpty()) {
            throw new IllegalStateException("未配置 spring.datasource.url");
        }

        if (baseUrl.startsWith("jdbc:h2:mem:")) {
            int start = "jdbc:h2:mem:".length();
            int end = baseUrl.indexOf(';', start);
            if (end < 0) {
                end = baseUrl.length();
            }
            return baseUrl.substring(0, start) + dbName + baseUrl.substring(end);
        }

        int schemeIndex = baseUrl.indexOf("://");
        if (schemeIndex > 0) {
            int dbStart = baseUrl.indexOf('/', schemeIndex + 3);
            if (dbStart > 0) {
                int dbEnd = baseUrl.indexOf('?', dbStart);
                if (dbEnd < 0) {
                    dbEnd = baseUrl.length();
                }
                return baseUrl.substring(0, dbStart + 1) + dbName + baseUrl.substring(dbEnd);
            }
        }

        throw new IllegalArgumentException("当前数据源 URL 不支持按库名动态切换: " + baseUrl);
    }

    /**
     * 校验库名，防止动态切库时被注入非法字符。
     * 注意：
     * 1. 当前只允许字母、数字、下划线；
     * 2. 这个校验同时服务于缓存 key 构造和 JDBC URL 拼装，是整个动态切库链路的第一道防线。
     */
    private String validateDbName(String dbName) {
        if (dbName == null || !DB_NAME_PATTERN.matcher(dbName).matches()) {
            throw new IllegalArgumentException("dbName 非法，仅允许字母、数字和下划线");
        }
        return dbName;
    }

    /**
     * 构造“库名 + 全量间隔”的复合缓存 key。
     * 注意：
     * 1. key 里保留 intervalSeconds，是因为同一个库在不同全量间隔下的快照集合完全不同；
     * 2. 这里会复用 validateDbName()，避免非法库名污染缓存空间。
     */
    private String buildFullSnapshotCacheKey(String dbName, int intervalSeconds) {
        return validateDbName(dbName) + ":" + intervalSeconds;
    }

    /**
     * 克隆缓存中的全量快照列表，避免调用方直接修改缓存对象。
     * 注意：
     * 1. 这里是浅层属性复制，适用于当前实体字段大多为值类型、字符串和时间戳的场景；
     * 2. 如果 RoomObjectHis 以后引入可变嵌套对象，这里需要重新评估是否改成深拷贝；
     * 3. sourceType 会在克隆后统一覆盖为调用方指定的值。
     */
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

    /**
     * 只把静态基础字段从基础缓存对象复制到运行态对象中。
     * 注意：
     * 1. 这里显式忽略所有变化字段，避免把数据库当前查到的 simTime、位置、状态等内容覆盖掉；
     * 2. 复制策略依赖 CHANGING_STATE_COPY_IGNORE_PROPERTIES，后续如果新增变化字段，这个忽略列表必须同步维护；
     * 3. sourceType 也在忽略名单里，避免覆盖调用链前面已经打好的 FULL / INCREMENT 标签。
     */
    private void copyStaticBaseFields(RoomObjectHis from, RoomObjectHis to) {
        BeanUtils.copyProperties(from, to, CHANGING_STATE_COPY_IGNORE_PROPERTIES);
    }
}
