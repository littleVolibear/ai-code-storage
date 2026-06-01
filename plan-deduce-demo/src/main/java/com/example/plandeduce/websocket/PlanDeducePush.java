package com.example.plandeduce.websocket;

import com.example.plandeduce.model.CommandInfo;
import com.example.plandeduce.model.FireJudgeResult;
import com.example.plandeduce.model.IndrectFirePlan;
import com.example.plandeduce.model.RoomObjectHis;
import com.example.plandeduce.model.PushMessage;
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
                             List<CommandInfo> commandInfoIncrementalData) {
        fullData = safeRoomObjectList(fullData);
        incrementalData = safeRoomObjectList(incrementalData);
        eventFullData = safeEventDataList(eventFullData);
        eventIncrementalData = safeEventDataList(eventIncrementalData);
        indrectFirePlanFullData = safeIndrectFirePlanList(indrectFirePlanFullData);
        indrectFirePlanIncrementalData = safeIndrectFirePlanList(indrectFirePlanIncrementalData);
        commandInfoFullData = safeCommandInfoList(commandInfoFullData);
        commandInfoIncrementalData = safeCommandInfoList(commandInfoIncrementalData);
        hydrateRoomObjectRealTime(fullData, realTime);
        hydrateRoomObjectRealTime(incrementalData, realTime);
        hydrateEventRealTime(eventFullData, realTime);
        hydrateEventRealTime(eventIncrementalData, realTime);
        hydrateIndrectFirePlanRealTime(indrectFirePlanFullData, realTime);
        hydrateIndrectFirePlanRealTime(indrectFirePlanIncrementalData, realTime);
        hydrateCommandInfoRealTime(commandInfoFullData, realTime);
        hydrateCommandInfoRealTime(commandInfoIncrementalData, realTime);
        List<RoomObjectHis> mergedData = mergeRoomObjectData(fullData, incrementalData);
        List<FireJudgeResult> mergedEventData = mergeEventData(eventFullData, eventIncrementalData);
        List<IndrectFirePlan> mergedIndrectFirePlanData = mergeIndrectFirePlanData(indrectFirePlanFullData, indrectFirePlanIncrementalData);
        List<CommandInfo> mergedCommandInfoData = mergeCommandInfoData(commandInfoFullData, commandInfoIncrementalData);

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
        if ("SKIP".equals(type) && fullTime == deduceTime) {
            return "当前真实时间 " + realTime + " 秒，推演时间 " + deduceTime + " 秒，返回第 " + fullTime + " 秒全量数据";
        }
        if ("SKIP".equals(type)) {
            return "当前真实时间 " + realTime + " 秒，推演时间 " + deduceTime + " 秒，返回第 "
                    + fullTime + " 秒全量数据，并叠加第 " + (fullTime + 1) + "-" + deduceTime + " 秒增量数据";
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

    /** 合并事件数据。 */
    private List<FireJudgeResult> mergeEventData(List<FireJudgeResult> fullData, List<FireJudgeResult> incrementalData) {
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

    /** 合并间瞄计划数据。 */
    private List<IndrectFirePlan> mergeIndrectFirePlanData(List<IndrectFirePlan> fullData, List<IndrectFirePlan> incrementalData) {
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

    /** 合并指令数据。 */
    private List<CommandInfo> mergeCommandInfoData(List<CommandInfo> fullData, List<CommandInfo> incrementalData) {
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
}
