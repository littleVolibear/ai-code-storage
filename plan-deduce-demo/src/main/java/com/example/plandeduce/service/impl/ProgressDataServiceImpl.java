package com.example.plandeduce.service.impl;

import cn.hutool.core.date.DatePattern;
import cn.hutool.core.date.DateUtil;
import com.example.plandeduce.config.DynamicDataSourceContextHolder;
import com.example.plandeduce.mapper.RoomInfoMapper;
import com.example.plandeduce.model.CommandInfo;
import com.example.plandeduce.model.FireJudgeResult;
import com.example.plandeduce.model.IndrectFirePlan;
import com.example.plandeduce.model.ProgressQueryContext;
import com.example.plandeduce.model.ProgressRangeQuery;
import com.example.plandeduce.model.ProgressSnapshotQuery;
import com.example.plandeduce.model.ProgressTimeline;
import com.example.plandeduce.model.RoomInfo;
import com.example.plandeduce.model.RoomObjectHis;
import com.example.plandeduce.service.CommandInfoDataService;
import com.example.plandeduce.service.FireJudgeResultDataService;
import com.example.plandeduce.service.IndrectFirePlanDataService;
import com.example.plandeduce.service.ProgressDataService;
import com.example.plandeduce.service.RoomObjectHisDataService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ProgressDataServiceImpl implements ProgressDataService {
    private final RoomInfoMapper roomInfoMapper;
    private final RoomObjectHisDataService roomObjectHisDataService;
    private final FireJudgeResultDataService fireJudgeResultDataService;
    private final IndrectFirePlanDataService indrectFirePlanDataService;
    private final CommandInfoDataService commandInfoDataService;

    /** 注入进度汇总层依赖的子服务与房间信息查询器。 */
    public ProgressDataServiceImpl(RoomInfoMapper roomInfoMapper,
                                   RoomObjectHisDataService roomObjectHisDataService,
                                   FireJudgeResultDataService fireJudgeResultDataService,
                                   IndrectFirePlanDataService indrectFirePlanDataService,
                                   CommandInfoDataService commandInfoDataService) {
        this.roomInfoMapper = roomInfoMapper;
        this.roomObjectHisDataService = roomObjectHisDataService;
        this.fireJudgeResultDataService = fireJudgeResultDataService;
        this.indrectFirePlanDataService = indrectFirePlanDataService;
        this.commandInfoDataService = commandInfoDataService;
    }

    /** 查询进度条展示所需的起始时间和总时长。 */
    @Override
    public ProgressTimeline queryProgressTimeline(ProgressQueryContext queryContext) {
        String dbName = queryContext.getDbName();
        DynamicDataSourceContextHolder.set(dbName);
        try {
            RoomInfo roomInfo = requireRoomInfo(dbName);
            return new ProgressTimeline(formatStartTime(roomInfo.getStartTime()), minutesToSeconds(roomInfo.getTotalTime()));
        } finally {
            DynamicDataSourceContextHolder.clear();
        }
    }

    /** 统一预热三类业务数据在指定全量点下的基础快照。 */
    @Override
    public void preloadFullSnapshots(ProgressSnapshotQuery snapshotQuery) {
        roomObjectHisDataService.preloadSnapshots(snapshotQuery);
        fireJudgeResultDataService.preloadSnapshots(snapshotQuery);
        indrectFirePlanDataService.preloadSnapshots(snapshotQuery);
        commandInfoDataService.preloadSnapshots(snapshotQuery);
    }

    /** 转发对象全量快照查询。 */
    @Override
    public List<RoomObjectHis> queryCachedFullData(ProgressSnapshotQuery snapshotQuery) {
        return roomObjectHisDataService.queryCachedFullData(snapshotQuery);
    }

    /** 转发对象播放增量查询。 */
    @Override
    public List<RoomObjectHis> queryIncrementalData(ProgressRangeQuery rangeQuery) {
        return roomObjectHisDataService.queryIncrementalData(rangeQuery);
    }

    /** 转发对象快照补丁查询。 */
    @Override
    public List<RoomObjectHis> querySnapshotIncrementalData(ProgressRangeQuery rangeQuery) {
        return roomObjectHisDataService.querySnapshotIncrementalData(rangeQuery);
    }

    /** 转发射击裁决全量快照查询。 */
    @Override
    public List<FireJudgeResult> queryEventFullData(ProgressSnapshotQuery snapshotQuery) {
        return fireJudgeResultDataService.queryFullData(snapshotQuery);
    }

    /** 转发射击裁决增量查询。 */
    @Override
    public List<FireJudgeResult> queryEventIncrementalData(ProgressRangeQuery rangeQuery) {
        return fireJudgeResultDataService.queryIncrementalData(rangeQuery);
    }

    /** 转发间瞄计划全量快照查询。 */
    @Override
    public List<IndrectFirePlan> queryIndrectFirePlanFullData(ProgressSnapshotQuery snapshotQuery) {
        return indrectFirePlanDataService.queryFullData(snapshotQuery);
    }

    /** 转发间瞄计划增量查询。 */
    @Override
    public List<IndrectFirePlan> queryIndrectFirePlanIncrementalData(ProgressRangeQuery rangeQuery) {
        return indrectFirePlanDataService.queryIncrementalData(rangeQuery);
    }

    /** 转发指令信息全量快照查询。 */
    @Override
    public List<CommandInfo> queryCommandInfoFullData(ProgressSnapshotQuery snapshotQuery) {
        return commandInfoDataService.queryFullData(snapshotQuery);
    }

    /** 转发指令信息增量查询。 */
    @Override
    public List<CommandInfo> queryCommandInfoIncrementalData(ProgressRangeQuery rangeQuery) {
        return commandInfoDataService.queryIncrementalData(rangeQuery);
    }

    /** 校验并读取当前库对应的房间配置记录。 */
    private RoomInfo requireRoomInfo(String dbName) {
        if (dbName == null || dbName.trim().isEmpty()) {
            throw new IllegalArgumentException("dbName 不能为空");
        }

        Long roomInfoId;
        try {
            roomInfoId = Long.valueOf(dbName);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("dbName 必须是动态数据库标识，同时满足 ROOM_INFO.id 的数字字符串约定");
        }
        RoomInfo roomInfo = roomInfoMapper.selectById(roomInfoId);
        if (roomInfo == null) {
            throw new IllegalArgumentException("未找到对应的 ROOM_INFO 记录: id=" + roomInfoId);
        }
        return roomInfo;
    }

    /** 将房间总时长从分钟换算为秒。 */
    private Integer minutesToSeconds(Integer totalTimeMinutes) {
        if (totalTimeMinutes == null || totalTimeMinutes <= 0) {
            return 0;
        }
        return Math.multiplyExact(totalTimeMinutes, 60);
    }

    /** 按统一格式输出房间起始时间。 */
    private String formatStartTime(java.util.Date startTime) {
        if (startTime == null) {
            return null;
        }
        return DateUtil.format(startTime, DatePattern.NORM_DATETIME_PATTERN);
    }
}
