package com.example.plandeduce.service.impl;

import com.example.plandeduce.model.CommandInfo;
import com.example.plandeduce.model.ControlPoint;
import com.example.plandeduce.model.FireJudgeResult;
import com.example.plandeduce.model.IndrectFirePlan;
import com.example.plandeduce.model.ProgressQueryContext;
import com.example.plandeduce.model.ProgressRangeQuery;
import com.example.plandeduce.model.ProgressSnapshotQuery;
import com.example.plandeduce.model.ProgressTimeline;
import com.example.plandeduce.model.RoomObjectHis;
import com.example.plandeduce.service.CommandInfoDataService;
import com.example.plandeduce.service.ControlPointDataService;
import com.example.plandeduce.service.FireJudgeResultDataService;
import com.example.plandeduce.service.IndrectFirePlanDataService;
import com.example.plandeduce.service.ProgressDataService;
import com.example.plandeduce.service.RoomInfoService;
import com.example.plandeduce.service.RoomObjectHisDataService;
import org.springframework.stereotype.Service;

import java.util.List;

/** 汇总进度条相关数据查询。 */
@Service
public class ProgressDataServiceImpl implements ProgressDataService {
    private final RoomInfoService roomInfoService;
    private final RoomObjectHisDataService roomObjectHisDataService;
    private final FireJudgeResultDataService fireJudgeResultDataService;
    private final IndrectFirePlanDataService indrectFirePlanDataService;
    private final CommandInfoDataService commandInfoDataService;
    private final ControlPointDataService controlPointDataService;

    /** 注入依赖。 */
    public ProgressDataServiceImpl(RoomInfoService roomInfoService,
                                   RoomObjectHisDataService roomObjectHisDataService,
                                   FireJudgeResultDataService fireJudgeResultDataService,
                                   IndrectFirePlanDataService indrectFirePlanDataService,
                                   CommandInfoDataService commandInfoDataService,
                                   ControlPointDataService controlPointDataService) {
        this.roomInfoService = roomInfoService;
        this.roomObjectHisDataService = roomObjectHisDataService;
        this.fireJudgeResultDataService = fireJudgeResultDataService;
        this.indrectFirePlanDataService = indrectFirePlanDataService;
        this.commandInfoDataService = commandInfoDataService;
        this.controlPointDataService = controlPointDataService;
    }

    /** 查询进度条时间范围。 */
    @Override
    public ProgressTimeline queryProgressTimeline(ProgressQueryContext queryContext) {
        return roomInfoService.queryProgressTimeline(queryContext);
    }

    /** 预热基础快照。 */
    @Override
    public void preloadFullSnapshots(ProgressSnapshotQuery snapshotQuery) {
        roomObjectHisDataService.preloadSnapshots(snapshotQuery);
    }

    /** 转发对象全量快照查询。 */
    @Override
    public List<RoomObjectHis> queryFullData(ProgressSnapshotQuery snapshotQuery) {
        return roomObjectHisDataService.queryFullData(snapshotQuery);
    }

    /** 转发对象增量数据查询。 */
    @Override
    public List<RoomObjectHis> queryIncrementalData(ProgressRangeQuery rangeQuery) {
        return roomObjectHisDataService.queryIncrementalData(rangeQuery);
    }

    /** 转发对象快照补丁查询。 */
    @Override
    public List<RoomObjectHis> querySnapshotIncrementalData(ProgressRangeQuery rangeQuery) {
        return roomObjectHisDataService.querySnapshotIncrementalData(rangeQuery);
    }

    /** 转发射击裁决增量数据查询。 */
    @Override
    public List<FireJudgeResult> queryEventIncrementalData(ProgressRangeQuery rangeQuery) {
        return fireJudgeResultDataService.queryIncrementalData(rangeQuery);
    }

    /** 转发间瞄计划增量数据查询。 */
    @Override
    public List<IndrectFirePlan> queryIndrectFirePlanIncrementalData(ProgressRangeQuery rangeQuery) {
        return indrectFirePlanDataService.queryIncrementalData(rangeQuery);
    }

    /** 转发指令信息增量数据查询。 */
    @Override
    public List<CommandInfo> queryCommandInfoIncrementalData(ProgressRangeQuery rangeQuery) {
        return commandInfoDataService.queryIncrementalData(rangeQuery);
    }

    /** 转发控制点增量数据查询。 */
    @Override
    public List<ControlPoint> queryControlPointIncrementalData(ProgressRangeQuery rangeQuery) {
        return controlPointDataService.queryIncrementalData(rangeQuery);
    }

}
