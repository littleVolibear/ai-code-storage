package com.example.plandeduce.service;

import com.example.plandeduce.model.ProgressTimeline;
import com.example.plandeduce.model.RoomInfo;

import java.util.Date;

public interface RoomInfoService {
    /** 查询房间信息。 */
    RoomInfo queryRequiredRoomInfo(String dbName);

    /** 查询房间开始时间。 */
    Date queryRequiredStartTime(String dbName);

    /** 查询进度条时间范围。 */
    ProgressTimeline queryProgressTimeline(String dbName);
}
