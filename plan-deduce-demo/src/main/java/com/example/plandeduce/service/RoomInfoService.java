package com.example.plandeduce.service;

import com.example.plandeduce.model.ProgressQueryContext;
import com.example.plandeduce.model.ProgressTimeline;
import com.example.plandeduce.model.RoomInfo;

import java.util.Date;

/** 定义房间信息查询能力。 */
public interface RoomInfoService {
    /** 查询房间信息。 */
    RoomInfo queryRequiredRoomInfo(ProgressQueryContext queryContext);

    /** 查询房间开始时间。 */
    Date queryRequiredStartTime(ProgressQueryContext queryContext);

    /** 查询进度条时间范围。 */
    ProgressTimeline queryProgressTimeline(ProgressQueryContext queryContext);
}
