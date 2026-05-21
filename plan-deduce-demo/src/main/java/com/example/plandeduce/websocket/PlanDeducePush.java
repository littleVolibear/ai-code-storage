package com.example.plandeduce.websocket;

import com.example.plandeduce.model.FireJudgeResult;
import com.example.plandeduce.model.RoomObjectHis;
import com.example.plandeduce.model.PushMessage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 进度条 WebSocket 推送门面。
 * 负责组装推送报文并发送到对应 session。
 */
@Component
public class PlanDeducePush {
    private final PlanDeduceWebSocketHandler webSocketHandler;

    public PlanDeducePush(PlanDeduceWebSocketHandler webSocketHandler) {
        this.webSocketHandler = webSocketHandler;
    }

    /**
     * 推送带数据的快照消息。
     * 所有需要刷新进度条和数据面板的事件都应经过这个入口，避免业务代码散落拼接 PushMessage。
     * 这里会把 fullData 和 incrementalData 合并到 data 字段，供前端直接消费。
     */
    public void pushSnapshot(String type,
                             String dbName,
                             String sessionId,
                             int realTime,
                             int deduceTime,
                             int fullTime,
                             int speed,
                             boolean running,
                             int maxSimTime,
                             List<RoomObjectHis> fullData,
                             List<RoomObjectHis> incrementalData,
                             List<FireJudgeResult> eventData) {
        fullData = safeRoomObjectList(fullData);
        incrementalData = safeRoomObjectList(incrementalData);
        eventData = safeEventDataList(eventData);
        hydrateRoomObjectRealTime(fullData, realTime);
        hydrateRoomObjectRealTime(incrementalData, realTime);
        hydrateEventRealTime(eventData, realTime);
        List<RoomObjectHis> mergedData = new ArrayList<>(fullData.size() + incrementalData.size());
        mergedData.addAll(fullData);
        mergedData.addAll(incrementalData);

        PushMessage message = buildBaseMessage(type, dbName, sessionId, realTime, deduceTime, fullTime, speed, running, maxSimTime);
        message.setFullData(Collections.emptyList());
        message.setIncrementalData(Collections.emptyList());
        message.setData(mergedData);
        message.setEventData(eventData);
        message.setMessage(buildSnapshotMessage(realTime, deduceTime, fullTime, incrementalData));
        webSocketHandler.sendToSession(sessionId, message);
    }

    /**
     * 推送纯状态消息。
     * 这类消息不包含快照数据，只负责通知前端切换播放状态、结束状态或错误状态。
     */
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

    /**
     * 统一构造基础协议字段，确保所有事件类型结构一致。
     */
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

    /**
     * 统一生成快照类消息的说明文案，方便前端日志和联调时直观看出数据组成。
     */
    private String buildSnapshotMessage(int realTime, int deduceTime, int fullTime, List<RoomObjectHis> incrementalData) {
        if (incrementalData.isEmpty() && fullTime == deduceTime) {
            return "当前真实时间 " + realTime + " 秒，推演时间 " + deduceTime + " 秒，返回第 " + fullTime + " 秒全量数据";
        }
        if (incrementalData.isEmpty()) {
            return "当前真实时间 " + realTime + " 秒，推演时间 " + deduceTime + " 秒，返回第 " + fullTime + " 秒全量数据";
        }
        int startTime = incrementalData.get(0).getSimTime() == null ? (fullTime + 1) : incrementalData.get(0).getSimTime();
        return "当前真实时间 " + realTime + " 秒，推演时间 " + deduceTime + " 秒，返回第 " + startTime + "-" + deduceTime + " 秒增量数据";
    }

    private void hydrateRoomObjectRealTime(List<RoomObjectHis> data, int realTime) {
        for (RoomObjectHis row : data) {
            if (row != null) {
                row.setRealTime(realTime);
            }
        }
    }

    private void hydrateEventRealTime(List<FireJudgeResult> data, int realTime) {
        for (FireJudgeResult row : data) {
            if (row != null) {
                row.setRealTime(realTime);
            }
        }
    }

    private List<RoomObjectHis> safeRoomObjectList(List<RoomObjectHis> data) {
        return data == null ? Collections.emptyList() : data;
    }

    private List<FireJudgeResult> safeEventDataList(List<FireJudgeResult> data) {
        return data == null ? Collections.emptyList() : data;
    }
}
