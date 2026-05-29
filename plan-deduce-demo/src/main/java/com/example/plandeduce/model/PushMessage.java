package com.example.plandeduce.model;

import lombok.Data;

import java.util.List;

@Data
public class PushMessage {
    /*
     * 场景示例：当前先以 1 倍速播放到 2 秒，然后切换到 3 倍速，下一帧 WebSocket 推送示意如下。
     *
     * 含义说明：
     * 1. realTime=3：真实只过去了 1 秒，所以真实播放时间从 2 走到 3；
     * 2. deduceTime=5：推演按 3 倍速推进了 3 个秒点，所以当前推演时间走到 5；
     * 3. data/eventData 里的 simTime 保持数据库表中的原始 simTime，不会都被改成 5；
     * 4. 除 SKIP 外，INIT/INTERVAL/PLAY 都只查询当前时间段增量数据，不单独下发全量数据；
     * 5. data/eventData/indrectFirePlanData/commandInfoData 里的 realTime 和外层 realTime 保持一致，
     *    统一表示“这条消息是在真实第几秒发出的”。
     *
     * {
     *   "type": "PLAY",
     *   "dbName": "1",
     *   "sessionId": "demo-session",
     *   "realTime": 3,
     *   "deduceTime": 5,
     *   "fullTime": 0,
     *   "speed": 3,
     *   "running": true,
     *   "fullData": [],
     *   "incrementalData": [],
     *   "data": [
     *     { "roomObjectId": 101, "simTime": 3, "realTime": 3 },
     *     { "roomObjectId": 102, "simTime": 3, "realTime": 3 },
     *     { "roomObjectId": 103, "simTime": 3, "realTime": 3 },
     *     { "roomObjectId": 101, "simTime": 4, "realTime": 3 },
     *     { "roomObjectId": 102, "simTime": 4, "realTime": 3 },
     *     { "roomObjectId": 103, "simTime": 4, "realTime": 3 },
     *     { "roomObjectId": 101, "simTime": 5, "realTime": 3 },
     *     { "roomObjectId": 102, "simTime": 5, "realTime": 3 },
     *     { "roomObjectId": 103, "simTime": 5, "realTime": 3 }
     *   ],
     *   "eventData": [
     *     { "id": 9001, "roomId": "1", "objId": 101, "tarObjId": 201, "simTime": 3000, "realTime": 3 },
     *     { "id": 9002, "roomId": "1", "objId": 102, "tarObjId": 202, "simTime": 3000, "realTime": 3 }
     *   ],
     *   "eventFullData": [],
     *   "eventIncrementalData": [],
     *   "indrectFirePlanData": [
     *     { "id": 31, "roomId": 501, "objId": 301, "ifId": 701, "simTime": 5000, "realTime": 3 },
     *     { "id": 32, "roomId": 501, "objId": 302, "ifId": 702, "simTime": 5000, "realTime": 3 }
     *   ],
     *   "indrectFirePlanFullData": [],
     *   "indrectFirePlanIncrementalData": [],
     *   "commandInfoData": [
     *     { "id": 51, "roomId": 501, "objId": 1, "simTime": 5000, "realTime": 3 },
     *     { "id": 52, "roomId": 501, "objId": 2, "simTime": 5000, "realTime": 3 },
     *     { "id": 53, "roomId": 501, "objId": 3, "simTime": 5000, "realTime": 3 }
     *   ],
     *   "commandInfoFullData": [],
     *   "commandInfoIncrementalData": [],
     *   "message": "当前真实时间 3 秒，推演时间 5 秒，返回第 3-5 秒增量数据",
     *   "maxSimTime": 1200
     * }
     */
    private String type; // 消息类型，如 INIT/PLAY/PAUSE/SKIP
    private String dbName; // 动态数据库标识，对应本次推演的数据集
    private String sessionId; // 当前前端会话标识
    private Integer realTime; // 当前真实时间轴位置，不受倍速跳跃影响
    private Integer deduceTime; // 当前推演时间轴位置，受倍速和跳点影响
    private Integer fullTime; // 当前快照对应的最近全量秒点
    private Integer speed; // 当前播放倍速
    private Boolean running; // 当前任务是否处于播放态
    private List<RoomObjectHis> fullData; // 兼容保留字段，当前对外固定为空数组
    private List<RoomObjectHis> incrementalData; // 兼容保留字段，当前对外固定为空数组
    private List<RoomObjectHis> data; // 给前端直接消费的合并结果
    private List<FireJudgeResult> eventData; // 当前帧对应的事件数据
    private List<FireJudgeResult> eventFullData; // 兼容保留字段，当前对外固定为空数组
    private List<FireJudgeResult> eventIncrementalData; // 兼容保留字段，当前对外固定为空数组
    private List<IndrectFirePlan> indrectFirePlanData; // 当前帧对应的间瞄计划数据
    private List<IndrectFirePlan> indrectFirePlanFullData; // 兼容保留字段，当前对外固定为空数组
    private List<IndrectFirePlan> indrectFirePlanIncrementalData; // 兼容保留字段，当前对外固定为空数组
    private List<CommandInfo> commandInfoData; // 当前帧对应的指令信息数据
    private List<CommandInfo> commandInfoFullData; // 兼容保留字段，当前对外固定为空数组
    private List<CommandInfo> commandInfoIncrementalData; // 兼容保留字段，当前对外固定为空数组
    private String message; // 辅助说明文案
    private Integer maxSimTime; // 推演最大业务时间（进度条结束时间）
}
