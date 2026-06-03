package com.example.plandeduce.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.plandeduce.config.DynamicDataSourceContextHolder;
import com.example.plandeduce.mapper.ControlPointMapper;
import com.example.plandeduce.model.ControlPoint;
import com.example.plandeduce.model.ProgressQueryContext;
import com.example.plandeduce.model.ProgressRangeQuery;
import com.example.plandeduce.service.ControlPointDataService;
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
/** 实现控制点数据查询。 */
public class ControlPointDataServiceImpl implements ControlPointDataService {
    private final ControlPointMapper controlPointMapper;
    private final RoomInfoService roomInfoService;
    private final Map<String, Date> roomStartTimeCache = new ConcurrentHashMap<>();

    /** 注入依赖。 */
    public ControlPointDataServiceImpl(ControlPointMapper controlPointMapper, RoomInfoService roomInfoService) {
        this.controlPointMapper = controlPointMapper;
        this.roomInfoService = roomInfoService;
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

}
