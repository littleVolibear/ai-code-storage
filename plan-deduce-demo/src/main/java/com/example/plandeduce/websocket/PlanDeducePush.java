package com.example.plandeduce.websocket;

import com.example.plandeduce.model.RoomObjectHis;
import com.example.plandeduce.model.PushMessage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 进度条 WebSocket 推送门面。
 * 业务侧只需要提供当前运行态和数据快照，这里统一负责组装消息协议、生成说明文案并下发到对应 session。
 * 这样 ScenarioTask 之类的业务类只关心“当前该推什么”，不用关心“消息具体长什么样、怎么发出去”。
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
     */
    public void pushSnapshot(String type,
                             String dbName,
                             String sessionId,
                             int currentTime,
                             int fullTime,
                             int speed,
                             boolean running,
                             int maxSimTime,
                             List<RoomObjectHis> fullData,
                             List<RoomObjectHis> incrementalData) {
        // data 是给前端直接消费的完整结果，内部统一按”全量 + 增量”顺序拼装。
        List<RoomObjectHis> mergedData = new ArrayList<>(fullData.size() + incrementalData.size());
        mergedData.addAll(fullData);
        mergedData.addAll(incrementalData);

        // 所有快照类事件沿用同一份协议结构，避免 INIT/PLAY/SKIP/INTERVAL 的字段漂移。
        PushMessage message = buildBaseMessage(type, dbName, sessionId, currentTime, fullTime, speed, running, maxSimTime);
        message.setFullData(Collections.emptyList());
        message.setIncrementalData(Collections.emptyList());
        message.setData(mergedData);
        message.setMessage(buildSnapshotMessage(currentTime, fullTime, incrementalData));
        webSocketHandler.sendToSession(sessionId, message);
    }

    /**
     * 推送纯状态消息。
     * 这类消息不包含快照数据，只负责通知前端切换播放状态、结束状态或错误状态。
     */
    public void pushStatus(String type,
                           String dbName,
                           String sessionId,
                           int currentTime,
                           Integer fullTime,
                           int speed,
                           boolean running,
                           int maxSimTime,
                           String text) {
        // 状态类事件同样保留 currentTime/fullTime/speed/running，方便前端只维护一套状态同步逻辑。
        PushMessage message = buildBaseMessage(type, dbName, sessionId, currentTime, fullTime, speed, running, maxSimTime);
        message.setMessage(text);
        webSocketHandler.sendToSession(sessionId, message);
    }

    /**
     * 统一构造基础协议字段，确保所有事件类型结构一致。
     */
    private PushMessage buildBaseMessage(String type,
                                         String dbName,
                                         String sessionId,
                                         int currentTime,
                                         Integer fullTime,
                                         int speed,
                                         boolean running,
                                         int maxSimTime) {
        PushMessage message = new PushMessage();
        message.setType(type);
        message.setDbName(dbName);
        message.setSessionId(sessionId);
        message.setCurrentTime(currentTime);
        message.setFullTime(fullTime);
        message.setSpeed(speed);
        message.setRunning(running);
        message.setMaxSimTime(maxSimTime);
        return message;
    }

    /**
     * 统一生成快照类消息的说明文案，方便前端日志和联调时直观看出数据组成。
     */
    private String buildSnapshotMessage(int currentTime, int fullTime, List<RoomObjectHis> incrementalData) {
        if (incrementalData.isEmpty() && fullTime == currentTime) {
            return "当前时间 " + currentTime + " 秒，返回第 " + fullTime + " 秒全量数据";
        }
        if (incrementalData.isEmpty()) {
            return "当前时间 " + currentTime + " 秒，返回第 " + fullTime + " 秒全量数据";
        }
        int startTime = incrementalData.get(0).getSimTime() == null ? (fullTime + 1) : incrementalData.get(0).getSimTime();
        return "当前时间 " + currentTime + " 秒，返回第 " + startTime + "-" + currentTime + " 秒增量数据";
    }
}
