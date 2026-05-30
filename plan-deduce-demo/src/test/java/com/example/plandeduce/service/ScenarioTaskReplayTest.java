package com.example.plandeduce.service;

import com.example.plandeduce.config.PlanDeduceProperties;
import com.example.plandeduce.model.FireJudgeResult;
import com.example.plandeduce.model.IndrectFirePlan;
import com.example.plandeduce.model.CommandInfo;
import com.example.plandeduce.model.ProgressQueryContext;
import com.example.plandeduce.model.ProgressRangeQuery;
import com.example.plandeduce.model.ProgressSnapshotQuery;
import com.example.plandeduce.model.ProgressTimeline;
import com.example.plandeduce.model.PushMessage;
import com.example.plandeduce.model.RoomObjectHis;
import com.example.plandeduce.websocket.PlanDeducePush;
import com.example.plandeduce.websocket.PlanDeduceWebSocketHandler;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScenarioTaskReplayTest {

    @Test
    void shouldReplayFromStartWhenResumeIsCalledAfterFinish() throws Exception {
        CapturingWebSocketHandler handler = new CapturingWebSocketHandler();
        PlanDeducePush push = new PlanDeducePush(handler);
        PlanDeduceProperties properties = new PlanDeduceProperties();
        properties.setTickIntervalMs(60_000L);

        ScenarioTaskManager taskManager = new ScenarioTaskManager(new FakeProgressDataService(), push, properties);
        try {
            ScenarioTask task = taskManager.getOrCreate("1", "session-1");
            task.initialize(5, null);

            task.startOrStop(1);

            PushMessage replayInit = handler.singleMessage();
            assertEquals("INIT", replayInit.getType());
            assertEquals(0, replayInit.getRealTime());
            assertEquals(0, replayInit.getDeduceTime());
            assertEquals(5, replayInit.getMaxSimTime());
            assertEquals(1, replayInit.getSpeed());
            assertTrue(replayInit.getRunning());

            invokeTickSafely(task);

            PushMessage replayPlay = handler.lastMessage();
            assertEquals("PLAY", replayPlay.getType());
            assertEquals(1, replayPlay.getRealTime());
            assertEquals(1, replayPlay.getDeduceTime());
            assertEquals(0, replayPlay.getFullTime());
            assertEquals(1, replayPlay.getSpeed());
            assertTrue(replayPlay.getRunning());
        } finally {
            taskManager.destroy();
        }
    }

    private void invokeTickSafely(ScenarioTask task) throws Exception {
        Method method = ScenarioTask.class.getDeclaredMethod("tickSafely");
        method.setAccessible(true);
        method.invoke(task);
    }

    private static class CapturingWebSocketHandler extends PlanDeduceWebSocketHandler {
        private final List<PushMessage> messages = new ArrayList<>();

        @Override
        public void sendToSession(String sessionId, Object payload) {
            messages.add((PushMessage) payload);
        }

        private PushMessage singleMessage() {
            assertEquals(1, messages.size());
            return messages.get(0);
        }

        private PushMessage lastMessage() {
            return messages.get(messages.size() - 1);
        }
    }

    private static class FakeProgressDataService implements ProgressDataService {
        @Override
        public ProgressTimeline queryProgressTimeline(ProgressQueryContext queryContext) {
            return new ProgressTimeline("2026-01-01 00:00:00", 5);
        }

        @Override
        public void preloadFullSnapshots(ProgressSnapshotQuery snapshotQuery) {
        }

        @Override
        public List<RoomObjectHis> queryCachedFullData(ProgressSnapshotQuery snapshotQuery) {
            return Collections.emptyList();
        }

        @Override
        public List<RoomObjectHis> queryIncrementalData(ProgressRangeQuery rangeQuery) {
            return Collections.emptyList();
        }

        @Override
        public List<RoomObjectHis> querySnapshotIncrementalData(ProgressRangeQuery rangeQuery) {
            return Collections.emptyList();
        }

        @Override
        public List<FireJudgeResult> queryEventFullData(ProgressSnapshotQuery snapshotQuery) {
            return Collections.emptyList();
        }

        @Override
        public List<FireJudgeResult> queryEventIncrementalData(ProgressRangeQuery rangeQuery) {
            return Collections.emptyList();
        }

        @Override
        public List<FireJudgeResult> queryEventSnapshotIncrementalData(ProgressRangeQuery rangeQuery) {
            return Collections.emptyList();
        }

        @Override
        public List<IndrectFirePlan> queryIndrectFirePlanFullData(ProgressSnapshotQuery snapshotQuery) {
            return Collections.emptyList();
        }

        @Override
        public List<IndrectFirePlan> queryIndrectFirePlanIncrementalData(ProgressRangeQuery rangeQuery) {
            return Collections.emptyList();
        }

        @Override
        public List<IndrectFirePlan> queryIndrectFirePlanSnapshotIncrementalData(ProgressRangeQuery rangeQuery) {
            return Collections.emptyList();
        }

        @Override
        public List<CommandInfo> queryCommandInfoFullData(ProgressSnapshotQuery snapshotQuery) {
            return Collections.emptyList();
        }

        @Override
        public List<CommandInfo> queryCommandInfoIncrementalData(ProgressRangeQuery rangeQuery) {
            return Collections.emptyList();
        }

        @Override
        public List<CommandInfo> queryCommandInfoSnapshotIncrementalData(ProgressRangeQuery rangeQuery) {
            return Collections.emptyList();
        }

    }
}
