package com.example.plandeduce.websocket;

import com.example.plandeduce.model.CommandInfo;
import com.example.plandeduce.model.ControlPoint;
import com.example.plandeduce.model.FireJudgeResult;
import com.example.plandeduce.model.IndrectFirePlan;
import com.example.plandeduce.model.RoomObjectHis;
import com.example.plandeduce.model.PushMessage;
import com.example.plandeduce.model.SkipRenderData;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 负责组装并发送 WebSocket 消息。 */
@Component
public class PlanDeducePush {
    private final PlanDeduceWebSocketHandler webSocketHandler;

    /** 注入 WebSocket 发送器。 */
    public PlanDeducePush(PlanDeduceWebSocketHandler webSocketHandler) {
        this.webSocketHandler = webSocketHandler;
    }

    /**
     * 推送快照消息。
     * 对外只发送合并后的数据字段，兼容字段固定置空。
     */
    public void pushSnapshot(String type,
                             String dbName,
                             String sessionId,
                             int realTime,
                             int deduceTime,
                             int fullTime,
                             int incrementalFromExclusive,
                             int speed,
                             boolean running,
                             int maxSimTime,
                             List<RoomObjectHis> fullData,
                             List<RoomObjectHis> incrementalData,
                             List<FireJudgeResult> eventFullData,
                             List<FireJudgeResult> eventIncrementalData,
                             List<IndrectFirePlan> indrectFirePlanFullData,
                             List<IndrectFirePlan> indrectFirePlanIncrementalData,
                             List<CommandInfo> commandInfoFullData,
                             List<CommandInfo> commandInfoIncrementalData,
                             List<ControlPoint> controlPointFullData,
                             List<ControlPoint> controlPointIncrementalData) {
        pushSnapshot(
                type,
                dbName,
                sessionId,
                realTime,
                deduceTime,
                fullTime,
                incrementalFromExclusive,
                speed,
                running,
                maxSimTime,
                fullData,
                incrementalData,
                eventFullData,
                eventIncrementalData,
                indrectFirePlanFullData,
                indrectFirePlanIncrementalData,
                commandInfoFullData,
                commandInfoIncrementalData,
                controlPointFullData,
                controlPointIncrementalData,
                null
        );
    }

    /**
     * 推送快照消息。
     * SKIP 可额外携带目标秒窗口内的渲染数据。
     */
    public void pushSnapshot(String type,
                             String dbName,
                             String sessionId,
                             int realTime,
                             int deduceTime,
                             int fullTime,
                             int incrementalFromExclusive,
                             int speed,
                             boolean running,
                             int maxSimTime,
                             List<RoomObjectHis> fullData,
                             List<RoomObjectHis> incrementalData,
                             List<FireJudgeResult> eventFullData,
                             List<FireJudgeResult> eventIncrementalData,
                             List<IndrectFirePlan> indrectFirePlanFullData,
                             List<IndrectFirePlan> indrectFirePlanIncrementalData,
                             List<CommandInfo> commandInfoFullData,
                             List<CommandInfo> commandInfoIncrementalData,
                             List<ControlPoint> controlPointFullData,
                             List<ControlPoint> controlPointIncrementalData,
                             SkipRenderData skipRenderData) {
        fullData = safeRoomObjectList(fullData);
        incrementalData = safeRoomObjectList(incrementalData);
        eventFullData = safeEventDataList(eventFullData);
        eventIncrementalData = safeEventDataList(eventIncrementalData);
        indrectFirePlanFullData = safeIndrectFirePlanList(indrectFirePlanFullData);
        indrectFirePlanIncrementalData = safeIndrectFirePlanList(indrectFirePlanIncrementalData);
        commandInfoFullData = safeCommandInfoList(commandInfoFullData);
        commandInfoIncrementalData = safeCommandInfoList(commandInfoIncrementalData);
        controlPointFullData = safeControlPointList(controlPointFullData);
        controlPointIncrementalData = safeControlPointList(controlPointIncrementalData);
        skipRenderData = hydrateSkipRenderData(type, skipRenderData, realTime);
        hydrateRoomObjectRealTime(fullData, realTime);
        hydrateRoomObjectRealTime(incrementalData, realTime);
        hydrateEventRealTime(eventFullData, realTime);
        hydrateEventRealTime(eventIncrementalData, realTime);
        hydrateIndrectFirePlanRealTime(indrectFirePlanFullData, realTime);
        hydrateIndrectFirePlanRealTime(indrectFirePlanIncrementalData, realTime);
        hydrateCommandInfoRealTime(commandInfoFullData, realTime);
        hydrateCommandInfoRealTime(commandInfoIncrementalData, realTime);
        hydrateControlPointRealTime(controlPointFullData, realTime);
        hydrateControlPointRealTime(controlPointIncrementalData, realTime);
        List<RoomObjectHis> mergedData = mergeRoomObjectData(fullData, incrementalData);
        List<FireJudgeResult> mergedEventData = mergeEventData(type, eventFullData, eventIncrementalData);
        List<IndrectFirePlan> mergedIndrectFirePlanData = mergeIndrectFirePlanData(type, indrectFirePlanFullData, indrectFirePlanIncrementalData);
        List<CommandInfo> mergedCommandInfoData = mergeCommandInfoData(type, commandInfoFullData, commandInfoIncrementalData);
        List<ControlPoint> mergedControlPointData = mergeControlPointData(controlPointFullData, controlPointIncrementalData);
        if (skipRenderData != null) {
            skipRenderData.setData(mergedData);
        }

        PushMessage message = buildBaseMessage(type, dbName, sessionId, realTime, deduceTime, fullTime, speed, running, maxSimTime);
        message.setFullData(Collections.emptyList());
        message.setIncrementalData(Collections.emptyList());
        message.setData(mergedData);
        message.setEventData(mergedEventData);
        message.setEventFullData(Collections.emptyList());
        message.setEventIncrementalData(Collections.emptyList());
        message.setIndrectFirePlanData(mergedIndrectFirePlanData);
        message.setIndrectFirePlanFullData(Collections.emptyList());
        message.setIndrectFirePlanIncrementalData(Collections.emptyList());
        message.setCommandInfoData(mergedCommandInfoData);
        message.setCommandInfoFullData(Collections.emptyList());
        message.setCommandInfoIncrementalData(Collections.emptyList());
        message.setControlPointData(mergedControlPointData);
        message.setControlPointFullData(Collections.emptyList());
        message.setControlPointIncrementalData(Collections.emptyList());
        message.setSkipRenderData(skipRenderData);
        message.setMessage(buildSnapshotMessage(type, realTime, deduceTime, fullTime, incrementalFromExclusive));
        webSocketHandler.sendToSession(sessionId, message);
    }

    /** 推送状态消息。 */
    public void pushStatus(String type,
                           String dbName,
                           String sessionId,
                           int realTime,
                           int deduceTime,
                           Integer fullTime,
                           int speed,
                           boolean running,
                           int maxSimTime,
                           String text) {
        PushMessage message = buildBaseMessage(type, dbName, sessionId, realTime, deduceTime, fullTime, speed, running, maxSimTime);
        message.setMessage(text);
        webSocketHandler.sendToSession(sessionId, message);
    }

    /** 构造基础消息。 */
    private PushMessage buildBaseMessage(String type,
                                         String dbName,
                                         String sessionId,
                                         int realTime,
                                         int deduceTime,
                                         Integer fullTime,
                                         int speed,
                                         boolean running,
                                         int maxSimTime) {
        PushMessage message = new PushMessage();
        message.setType(type);
        message.setDbName(dbName);
        message.setSessionId(sessionId);
        message.setRealTime(realTime);
        message.setDeduceTime(deduceTime);
        message.setFullTime(fullTime);
        message.setSpeed(speed);
        message.setRunning(running);
        message.setMaxSimTime(maxSimTime);
        return message;
    }

    /** 生成快照说明文案。 */
    private String buildSnapshotMessage(String type, int realTime, int deduceTime, int fullTime, int incrementalFromExclusive) {
        if ("SKIP".equals(type)) {
            return "当前真实时间 " + realTime + " 秒，推演时间 " + deduceTime + " 秒，返回第 " + deduceTime + " 秒跳点数据";
        }
        int incrementalStart = incrementalFromExclusive + 1;
        if (incrementalStart >= deduceTime) {
            return "当前真实时间 " + realTime + " 秒，推演时间 " + deduceTime + " 秒，返回第 " + deduceTime + " 秒增量数据";
        }
        return "当前真实时间 " + realTime + " 秒，推演时间 " + deduceTime + " 秒，返回第 " + incrementalStart + "-" + deduceTime + " 秒增量数据";
    }

    /** 合并对象数据。 */
    private List<RoomObjectHis> mergeRoomObjectData(List<RoomObjectHis> fullData, List<RoomObjectHis> incrementalData) {
        Map<Integer, RoomObjectHis> rowsByObjectId = new LinkedHashMap<>();
        for (RoomObjectHis row : fullData) {
            if (row != null && row.getRoomObjectId() != null) {
                rowsByObjectId.put(row.getRoomObjectId(), row);
            }
        }
        for (RoomObjectHis row : incrementalData) {
            if (row != null && row.getRoomObjectId() != null) {
                rowsByObjectId.put(row.getRoomObjectId(), row);
            }
        }
        return new ArrayList<>(rowsByObjectId.values());
    }

    /** 设置对象数据的真实时间。 */
    private void hydrateRoomObjectRealTime(List<RoomObjectHis> data, int realTime) {
        for (RoomObjectHis row : data) {
            if (row != null) {
                row.setRealTime(realTime);
            }
        }
    }

    /** 设置事件数据的真实时间。 */
    private void hydrateEventRealTime(List<FireJudgeResult> data, int realTime) {
        for (FireJudgeResult row : data) {
            if (row != null) {
                row.setRealTime(realTime);
            }
        }
    }

    /** 设置间瞄计划数据的真实时间。 */
    private void hydrateIndrectFirePlanRealTime(List<IndrectFirePlan> data, int realTime) {
        for (IndrectFirePlan row : data) {
            if (row != null) {
                row.setRealTime(realTime);
            }
        }
    }

    /** 设置指令数据的真实时间。 */
    private void hydrateCommandInfoRealTime(List<CommandInfo> data, int realTime) {
        for (CommandInfo row : data) {
            if (row != null) {
                row.setRealTime(realTime);
            }
        }
    }

    /** 设置控制点数据的真实时间。 */
    private void hydrateControlPointRealTime(List<ControlPoint> data, int realTime) {
        for (ControlPoint row : data) {
            if (row != null) {
                row.setRealTime(realTime);
            }
        }
    }

    /** 规整对象数据列表。 */
    private List<RoomObjectHis> safeRoomObjectList(List<RoomObjectHis> data) {
        return data == null ? Collections.emptyList() : data;
    }

    /** 规整事件数据列表。 */
    private List<FireJudgeResult> safeEventDataList(List<FireJudgeResult> data) {
        return data == null ? Collections.emptyList() : data;
    }

    /** 规整间瞄计划数据列表。 */
    private List<IndrectFirePlan> safeIndrectFirePlanList(List<IndrectFirePlan> data) {
        return data == null ? Collections.emptyList() : data;
    }

    /** 规整指令数据列表。 */
    private List<CommandInfo> safeCommandInfoList(List<CommandInfo> data) {
        return data == null ? Collections.emptyList() : data;
    }

    /** 规整控制点数据列表。 */
    private List<ControlPoint> safeControlPointList(List<ControlPoint> data) {
        return data == null ? Collections.emptyList() : data;
    }

    /** 规整并设置跳点渲染数据的真实时间。 */
    private SkipRenderData hydrateSkipRenderData(String type, SkipRenderData data, int realTime) {
        if (!"SKIP".equals(type) || data == null) {
            return null;
        }
        data.setData(safeRoomObjectList(data.getData()));
        data.setEventData(safeEventDataList(data.getEventData()));
        data.setCommandInfoData(safeCommandInfoList(data.getCommandInfoData()));
        data.setControlPointData(safeControlPointList(data.getControlPointData()));
        hydrateRoomObjectRealTime(data.getData(), realTime);
        hydrateEventRealTime(data.getEventData(), realTime);
        hydrateCommandInfoRealTime(data.getCommandInfoData(), realTime);
        hydrateControlPointRealTime(data.getControlPointData(), realTime);
        return data;
    }

    /** 合并事件数据；SKIP 时保留 0 到跳点秒的完整事件列表。 */
    private List<FireJudgeResult> mergeEventData(String type, List<FireJudgeResult> fullData, List<FireJudgeResult> incrementalData) {
        if ("SKIP".equals(type)) {
            List<FireJudgeResult> replayData = new ArrayList<>(fullData.size() + incrementalData.size());
            replayData.addAll(fullData);
            replayData.addAll(incrementalData);
            return replayData;
        }
        Map<String, FireJudgeResult> rowsByEventPair = new LinkedHashMap<>();
        for (FireJudgeResult row : fullData) {
            if (row != null && row.getObjId() != null && row.getTarObjId() != null) {
                rowsByEventPair.put(row.getObjId() + "#" + row.getTarObjId(), row);
            }
        }
        for (FireJudgeResult row : incrementalData) {
            if (row != null && row.getObjId() != null && row.getTarObjId() != null) {
                rowsByEventPair.put(row.getObjId() + "#" + row.getTarObjId(), row);
            }
        }
        return new ArrayList<>(rowsByEventPair.values());
    }

    /** 合并间瞄计划数据；SKIP 时保留跳点目标秒内的完整计划列表。 */
    private List<IndrectFirePlan> mergeIndrectFirePlanData(String type, List<IndrectFirePlan> fullData, List<IndrectFirePlan> incrementalData) {
        if ("SKIP".equals(type)) {
            List<IndrectFirePlan> replayData = new ArrayList<>(fullData.size() + incrementalData.size());
            replayData.addAll(fullData);
            replayData.addAll(incrementalData);
            return replayData;
        }
        Map<Integer, IndrectFirePlan> rowsByIfId = new LinkedHashMap<>();
        for (IndrectFirePlan row : fullData) {
            if (row != null && row.getIfId() != null) {
                rowsByIfId.put(row.getIfId(), row);
            }
        }
        for (IndrectFirePlan row : incrementalData) {
            if (row != null && row.getIfId() != null) {
                rowsByIfId.put(row.getIfId(), row);
            }
        }
        return new ArrayList<>(rowsByIfId.values());
    }

    /** 合并指令数据；SKIP 时保留 0 到跳点秒的完整指令列表。 */
    private List<CommandInfo> mergeCommandInfoData(String type, List<CommandInfo> fullData, List<CommandInfo> incrementalData) {
        if ("SKIP".equals(type)) {
            List<CommandInfo> replayData = new ArrayList<>(fullData.size() + incrementalData.size());
            replayData.addAll(fullData);
            replayData.addAll(incrementalData);
            return replayData;
        }
        Map<Integer, CommandInfo> rowsByObjId = new LinkedHashMap<>();
        for (CommandInfo row : fullData) {
            if (row != null && row.getObjId() != null) {
                rowsByObjId.put(row.getObjId(), row);
            }
        }
        for (CommandInfo row : incrementalData) {
            if (row != null && row.getObjId() != null) {
                rowsByObjId.put(row.getObjId(), row);
            }
        }
        return new ArrayList<>(rowsByObjId.values());
    }

    /** 按 controlPointId 合并控制点当前状态和夺控补丁。 */
    private List<ControlPoint> mergeControlPointData(List<ControlPoint> fullData, List<ControlPoint> incrementalData) {
        Map<Integer, ControlPoint> rowsByControlPointId = new LinkedHashMap<>();
        for (ControlPoint row : fullData) {
            if (row != null && row.getControlPointId() != null) {
                rowsByControlPointId.put(row.getControlPointId(), row);
            }
        }
        for (ControlPoint row : incrementalData) {
            if (row != null && row.getControlPointId() != null) {
                rowsByControlPointId.put(row.getControlPointId(), row);
            }
        }
        return new ArrayList<>(rowsByControlPointId.values());
    }
}
