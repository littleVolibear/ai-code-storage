package com.example.plandeduce.service.impl;

import cn.hutool.core.date.DatePattern;
import cn.hutool.core.date.DateUtil;
import com.example.plandeduce.config.DynamicDataSourceContextHolder;
import com.example.plandeduce.mapper.RoomInfoMapper;
import com.example.plandeduce.model.ProgressTimeline;
import com.example.plandeduce.model.RoomInfo;
import com.example.plandeduce.service.RoomInfoService;
import org.springframework.stereotype.Service;

import java.util.Date;

@Service
public class RoomInfoServiceImpl implements RoomInfoService {
    private final RoomInfoMapper roomInfoMapper;

    /** 注入依赖。 */
    public RoomInfoServiceImpl(RoomInfoMapper roomInfoMapper) {
        this.roomInfoMapper = roomInfoMapper;
    }

    /** 查询房间信息。 */
    @Override
    public RoomInfo queryRequiredRoomInfo(String dbName) {
        DynamicDataSourceContextHolder.set(dbName);
        try {
            return loadRequiredRoomInfo(dbName);
        } finally {
            DynamicDataSourceContextHolder.clear();
        }
    }

    /** 查询房间开始时间。 */
    @Override
    public Date queryRequiredStartTime(String dbName) {
        RoomInfo roomInfo = queryRequiredRoomInfo(dbName);
        if (roomInfo.getStartTime() == null) {
            throw new IllegalArgumentException("未找到 ROOM_INFO.startTime: id=" + parseRoomInfoId(dbName));
        }
        return roomInfo.getStartTime();
    }

    /** 查询进度条时间范围。 */
    @Override
    public ProgressTimeline queryProgressTimeline(String dbName) {
        RoomInfo roomInfo = queryRequiredRoomInfo(dbName);
        return new ProgressTimeline(formatStartTime(roomInfo.getStartTime()), minutesToSeconds(roomInfo.getTotalTime()));
    }

    /** 读取房间信息。 */
    private RoomInfo loadRequiredRoomInfo(String dbName) {
        if (dbName == null || dbName.trim().isEmpty()) {
            throw new IllegalArgumentException("dbName 不能为空");
        }
        Long roomInfoId = parseRoomInfoId(dbName);
        RoomInfo roomInfo = roomInfoMapper.selectById(roomInfoId);
        if (roomInfo == null) {
            throw new IllegalArgumentException("未找到对应的 ROOM_INFO 记录: id=" + roomInfoId);
        }
        return roomInfo;
    }

    /** 解析房间 ID。 */
    private Long parseRoomInfoId(String dbName) {
        try {
            return Long.valueOf(dbName);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("dbName 必须是动态数据库标识，同时满足 ROOM_INFO.id 的数字字符串约定");
        }
    }

    /** 分钟转秒。 */
    private Integer minutesToSeconds(Integer totalTimeMinutes) {
        if (totalTimeMinutes == null || totalTimeMinutes <= 0) {
            return 0;
        }
        return Math.multiplyExact(totalTimeMinutes, 60);
    }

    /** 格式化开始时间。 */
    private String formatStartTime(Date startTime) {
        if (startTime == null) {
            return null;
        }
        return DateUtil.format(startTime, DatePattern.NORM_DATETIME_PATTERN);
    }
}
