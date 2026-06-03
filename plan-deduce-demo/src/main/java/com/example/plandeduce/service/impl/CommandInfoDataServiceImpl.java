package com.example.plandeduce.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.plandeduce.config.DynamicDataSourceContextHolder;
import com.example.plandeduce.mapper.CommandInfoMapper;
import com.example.plandeduce.model.CommandInfo;
import com.example.plandeduce.model.ProgressQueryContext;
import com.example.plandeduce.model.ProgressRangeQuery;
import com.example.plandeduce.service.CommandInfoDataService;
import com.example.plandeduce.service.RoomInfoService;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
/** 实现指令信息数据查询。 */
public class CommandInfoDataServiceImpl implements CommandInfoDataService {
    private final CommandInfoMapper commandInfoMapper;
    private final RoomInfoService roomInfoService;
    private final Map<String, Date> roomStartTimeCache = new ConcurrentHashMap<>();

    /** 注入依赖。 */
    public CommandInfoDataServiceImpl(CommandInfoMapper commandInfoMapper, RoomInfoService roomInfoService) {
        this.commandInfoMapper = commandInfoMapper;
        this.roomInfoService = roomInfoService;
    }

    /** 查询指令信息增量数据。 */
    @Override
    public List<CommandInfo> queryIncrementalData(ProgressRangeQuery rangeQuery) {
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

    /** 查询区间指令信息数据。 */
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

    /** 复制指令信息结果数据。 */
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

}
