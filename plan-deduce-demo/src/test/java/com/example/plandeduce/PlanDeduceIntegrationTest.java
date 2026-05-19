package com.example.plandeduce;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import javax.websocket.ContainerProvider;
import javax.websocket.WebSocketContainer;
import java.io.IOException;
import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "plan.deduce.tick-interval-ms=100",
                "plan.deduce.default-full-save-interval-seconds=10"
        }
)
class PlanDeduceIntegrationTest {
    private static final String DB_NAME = "plandeduce";
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(3);
    private static final int PIECES_PER_SECOND = 3;

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DataSourceProperties dataSourceProperties;

    private final List<TestWebSocketClient> sockets = new ArrayList<>();
    private final List<TaskRef> tasksToDestroy = new ArrayList<>();

    @AfterEach
    void tearDown() throws Exception {
        for (TaskRef taskRef : tasksToDestroy) {
            restTemplate.getForEntity(httpUrl("/plan/destroy?dbName=" + taskRef.dbName + "&sessionId=" + taskRef.sessionId), String.class);
        }
        for (TestWebSocketClient socket : sockets) {
            socket.close();
        }
        tasksToDestroy.clear();
        sockets.clear();
    }

    @Test
    void shouldReturnFullAndIncrementalDataWhenSkippingToEleven() throws Exception {
        String sessionId = newSessionId();
        TestWebSocketClient socket = connect(sessionId);

        initializeAndStart(socket, DB_NAME, 0, sessionId);
        call("/plan/skip?dbName=" + DB_NAME + "&skip=11&sessionId=" + sessionId);

        JsonNode skipMessage = socket.awaitMessageOfType("SKIP", DEFAULT_TIMEOUT);
        assertEquals(11, skipMessage.path("currentTime").asInt());
        assertEquals(10, skipMessage.path("fullTime").asInt());
        assertSimtimes(skipMessage.path("data"), concat(repeatSimtime(10), repeatSimtime(11)));
        assertEventTimes(skipMessage.path("eventData"), range(0, 11));
        assertPieceFieldsPresent(skipMessage.path("data"));
    }

    @Test
    void shouldReturnFullAndCumulativeIncrementalDataWhenSkippingToThirteen() throws Exception {
        String sessionId = newSessionId();
        TestWebSocketClient socket = connect(sessionId);

        initializeAndStart(socket, DB_NAME, 0, sessionId);
        call("/plan/skip?dbName=" + DB_NAME + "&skip=13&sessionId=" + sessionId);

        JsonNode skipMessage = socket.awaitMessageOfType("SKIP", DEFAULT_TIMEOUT);
        assertEquals(13, skipMessage.path("currentTime").asInt());
        assertEquals(10, skipMessage.path("fullTime").asInt());
        assertSimtimes(skipMessage.path("data"), concat(repeatSimtime(10), repeatSimtime(13)));
        assertEventTimes(skipMessage.path("eventData"), range(0, 13));
        assertPieceFieldsPresent(skipMessage.path("data"));
    }

    @Test
    void shouldStartPauseAndResumeAtOneX() throws Exception {
        String sessionId = newSessionId();
        TestWebSocketClient socket = connect(sessionId);

        JsonNode init = initializeAndStart(socket, DB_NAME, 0, sessionId);
        assertEquals(0, init.path("currentTime").asInt());
        assertEquals(0, init.path("fullTime").asInt());
        assertEquals(1200, init.path("maxSimTime").asInt());
        assertEventTimes(init.path("eventData"), 0);

        JsonNode firstPlay = socket.awaitMessageOfType("PLAY", DEFAULT_TIMEOUT);
        assertEquals(1, firstPlay.path("currentTime").asInt());
        assertEquals(0, firstPlay.path("fullTime").asInt());
        assertEventTimes(firstPlay.path("eventData"), 1);

        call("/plan/startOrStop?dbName=" + DB_NAME + "&flag=0&sessionId=" + sessionId);
        JsonNode pause = socket.awaitMessageOfType("PAUSE", DEFAULT_TIMEOUT);
        int pausedAt = pause.path("currentTime").asInt();
        socket.assertNoMessageOfType("PLAY", Duration.ofMillis(350));

        call("/plan/startOrStop?dbName=" + DB_NAME + "&flag=1&sessionId=" + sessionId);
        JsonNode start = socket.awaitMessageOfType("START", DEFAULT_TIMEOUT);
        assertEquals(pausedAt, start.path("currentTime").asInt());

        JsonNode resumedPlay = socket.awaitMessageOfType("PLAY", DEFAULT_TIMEOUT);
        assertEquals(pausedAt + 1, resumedPlay.path("currentTime").asInt());
    }

    @Test
    void shouldHandlePauseThenSwitchToThreeXThenPauseStartPauseSequence() throws Exception {
        String sessionId = newSessionId();
        TestWebSocketClient socket = connect(sessionId);

        initializeAndStart(socket, DB_NAME, 0, sessionId);

        JsonNode firstPlay = socket.awaitMessageOfType("PLAY", DEFAULT_TIMEOUT);
        assertEquals(1, firstPlay.path("currentTime").asInt());
        assertEquals(1, firstPlay.path("speed").asInt());
        assertTrue(firstPlay.path("running").asBoolean());

        call("/plan/startOrStop?dbName=" + DB_NAME + "&flag=0&sessionId=" + sessionId);
        JsonNode firstPause = socket.awaitMessageOfType("PAUSE", DEFAULT_TIMEOUT);
        int pausedAt = firstPause.path("currentTime").asInt();
        assertEquals(1, pausedAt);
        assertFalse(firstPause.path("running").asBoolean());
        socket.assertNoMessageOfType("PLAY", Duration.ofMillis(350));

        call("/plan/speed?dbName=" + DB_NAME + "&speed=3&sessionId=" + sessionId);
        JsonNode speed = socket.awaitMessageOfType("SPEED", DEFAULT_TIMEOUT);
        assertEquals(3, speed.path("speed").asInt());
        assertFalse(speed.path("running").asBoolean());
        assertEquals(pausedAt, speed.path("currentTime").asInt());
        JsonNode firstStart = socket.awaitMessageOfType("START", DEFAULT_TIMEOUT);
        assertEquals(pausedAt, firstStart.path("currentTime").asInt());
        assertEquals(3, firstStart.path("speed").asInt());
        assertTrue(firstStart.path("running").asBoolean());

        JsonNode playAtThreeX = socket.awaitMessageOfType("PLAY", DEFAULT_TIMEOUT);
        int afterThreeX = playAtThreeX.path("currentTime").asInt();
        assertEquals(pausedAt + 3, afterThreeX);
        assertEquals(3, playAtThreeX.path("speed").asInt());

        call("/plan/startOrStop?dbName=" + DB_NAME + "&flag=0&sessionId=" + sessionId);
        JsonNode secondPause = socket.awaitMessageOfType("PAUSE", DEFAULT_TIMEOUT);
        assertEquals(afterThreeX, secondPause.path("currentTime").asInt());
        assertFalse(secondPause.path("running").asBoolean());
        socket.assertNoMessageOfType("PLAY", Duration.ofMillis(350));

        call("/plan/startOrStop?dbName=" + DB_NAME + "&flag=1&sessionId=" + sessionId);
        JsonNode start = socket.awaitMessageOfType("START", DEFAULT_TIMEOUT);
        assertEquals(afterThreeX, start.path("currentTime").asInt());
        assertEquals(3, start.path("speed").asInt());
        assertTrue(start.path("running").asBoolean());

        JsonNode resumedPlay = socket.awaitMessageOfType("PLAY", DEFAULT_TIMEOUT);
        int resumedAt = resumedPlay.path("currentTime").asInt();
        assertEquals(afterThreeX + 3, resumedAt);
        assertEquals(3, resumedPlay.path("speed").asInt());

        call("/plan/startOrStop?dbName=" + DB_NAME + "&flag=0&sessionId=" + sessionId);
        JsonNode thirdPause = socket.awaitMessageOfType("PAUSE", DEFAULT_TIMEOUT);
        assertEquals(resumedAt, thirdPause.path("currentTime").asInt());
        assertFalse(thirdPause.path("running").asBoolean());
        socket.assertNoMessageOfType("PLAY", Duration.ofMillis(350));
        socket.assertNoMessageOfType("ERROR", Duration.ofMillis(350));
    }

    @Test
    void shouldHandleOneXRunForTwoSecondsThenPauseChangeToThreeXStartAndPause() throws Exception {
        String sessionId = newSessionId();
        TestWebSocketClient socket = connect(sessionId);

        initializeAndStart(socket, DB_NAME, 0, sessionId);

        JsonNode firstPlay = socket.awaitMessageOfType("PLAY", DEFAULT_TIMEOUT);
        assertEquals(1, firstPlay.path("currentTime").asInt());
        JsonNode secondPlay = socket.awaitMessageOfType("PLAY", DEFAULT_TIMEOUT);
        assertEquals(2, secondPlay.path("currentTime").asInt());
        assertEquals(1, secondPlay.path("speed").asInt());

        call("/plan/startOrStop?dbName=" + DB_NAME + "&flag=0&sessionId=" + sessionId);
        JsonNode pause = socket.awaitMessageOfType("PAUSE", DEFAULT_TIMEOUT);
        assertEquals(2, pause.path("currentTime").asInt());
        assertFalse(pause.path("running").asBoolean());
        socket.assertNoMessageOfType("PLAY", Duration.ofMillis(350));

        call("/plan/speed?dbName=" + DB_NAME + "&speed=3&sessionId=" + sessionId);
        JsonNode speed = socket.awaitMessageOfType("SPEED", DEFAULT_TIMEOUT);
        assertEquals(3, speed.path("speed").asInt());
        assertEquals(2, speed.path("currentTime").asInt());
        assertFalse(speed.path("running").asBoolean());
        JsonNode start = socket.awaitMessageOfType("START", DEFAULT_TIMEOUT);
        assertEquals(2, start.path("currentTime").asInt());
        assertEquals(3, start.path("speed").asInt());
        assertTrue(start.path("running").asBoolean());

        JsonNode resumedPlay = socket.awaitMessageOfType("PLAY", DEFAULT_TIMEOUT);
        assertEquals(5, resumedPlay.path("currentTime").asInt());
        assertEquals(3, resumedPlay.path("speed").asInt());

        call("/plan/startOrStop?dbName=" + DB_NAME + "&flag=0&sessionId=" + sessionId);
        JsonNode secondPause = socket.awaitMessageOfType("PAUSE", DEFAULT_TIMEOUT);
        assertEquals(5, secondPause.path("currentTime").asInt());
        assertFalse(secondPause.path("running").asBoolean());
        socket.assertNoMessageOfType("PLAY", Duration.ofMillis(350));
        socket.assertNoMessageOfType("ERROR", Duration.ofMillis(350));
    }

    @Test
    void shouldSupportDocumentedScenarioTwoFlow() throws Exception {
        String sessionId = newSessionId();
        TestWebSocketClient socket = connect(sessionId);

        call("/plan/speed?dbName=" + DB_NAME + "&speed=3&sessionId=" + sessionId);
        JsonNode speed = socket.awaitMessageOfType("SPEED", DEFAULT_TIMEOUT);
        assertEquals(0, speed.path("currentTime").asInt());
        assertEquals(3, speed.path("speed").asInt());
        assertFalse(speed.path("running").asBoolean());

        call("/plan/startOrStop?dbName=" + DB_NAME + "&flag=1&sessionId=" + sessionId);
        JsonNode init = socket.awaitMessageOfType("INIT", DEFAULT_TIMEOUT);
        assertEquals(0, init.path("currentTime").asInt());
        assertEquals(3, init.path("speed").asInt());
        assertTrue(init.path("running").asBoolean());
        assertEquals(1200, init.path("maxSimTime").asInt());

        JsonNode firstPlay = socket.awaitMessageOfType("PLAY", DEFAULT_TIMEOUT);
        assertEquals(3, firstPlay.path("currentTime").asInt());
        assertEquals(3, firstPlay.path("speed").asInt());
        assertEventTimes(firstPlay.path("eventData"), 1, 2, 3);

        call("/plan/startOrStop?dbName=" + DB_NAME + "&flag=0&sessionId=" + sessionId);
        JsonNode pause = socket.awaitMessageOfType("PAUSE", DEFAULT_TIMEOUT);
        assertEquals(3, pause.path("currentTime").asInt());
        assertEquals(3, pause.path("speed").asInt());
        assertFalse(pause.path("running").asBoolean());

        call("/plan/startOrStop?dbName=" + DB_NAME + "&flag=1&sessionId=" + sessionId);
        JsonNode start = socket.awaitMessageOfType("START", DEFAULT_TIMEOUT);
        assertEquals(3, start.path("currentTime").asInt());
        assertEquals(3, start.path("speed").asInt());
        assertTrue(start.path("running").asBoolean());

        JsonNode resumedPlay = socket.awaitMessageOfType("PLAY", DEFAULT_TIMEOUT);
        assertEquals(6, resumedPlay.path("currentTime").asInt());
        assertEquals(3, resumedPlay.path("speed").asInt());

        call("/plan/startOrStop?dbName=" + DB_NAME + "&flag=0&sessionId=" + sessionId);
        JsonNode finalPause = socket.awaitMessageOfType("PAUSE", DEFAULT_TIMEOUT);
        assertEquals(6, finalPause.path("currentTime").asInt());
        assertEquals(3, finalPause.path("speed").asInt());
        assertFalse(finalPause.path("running").asBoolean());
    }

    @Test
    void shouldSupportDocumentedScenarioThreeFlow() throws Exception {
        String sessionId = newSessionId();
        TestWebSocketClient socket = connect(sessionId);

        JsonNode init = initializeAndStart(socket, DB_NAME, 0, sessionId);
        assertEquals(1200, init.path("maxSimTime").asInt());

        JsonNode playAtFive = waitUntilCurrentTimeAtLeast(socket, 5, Duration.ofSeconds(3));
        assertEquals(5, playAtFive.path("currentTime").asInt());
        assertEquals(1, playAtFive.path("speed").asInt());

        call("/plan/startOrStop?dbName=" + DB_NAME + "&flag=0&sessionId=" + sessionId);
        JsonNode pause = socket.awaitMessageOfType("PAUSE", DEFAULT_TIMEOUT);
        assertEquals(5, pause.path("currentTime").asInt());
        assertEquals(1, pause.path("speed").asInt());
        assertFalse(pause.path("running").asBoolean());

        call("/plan/speed?dbName=" + DB_NAME + "&speed=3&sessionId=" + sessionId);
        JsonNode speed = socket.awaitMessageOfType("SPEED", DEFAULT_TIMEOUT);
        assertEquals(5, speed.path("currentTime").asInt());
        assertEquals(3, speed.path("speed").asInt());
        assertFalse(speed.path("running").asBoolean());

        call("/plan/startOrStop?dbName=" + DB_NAME + "&flag=1&sessionId=" + sessionId);
        JsonNode start = socket.awaitMessageOfType("START", DEFAULT_TIMEOUT);
        assertEquals(5, start.path("currentTime").asInt());
        assertEquals(3, start.path("speed").asInt());
        assertTrue(start.path("running").asBoolean());

        JsonNode resumedPlay = socket.awaitMessageOfType("PLAY", DEFAULT_TIMEOUT);
        assertEquals(8, resumedPlay.path("currentTime").asInt());
        assertEquals(3, resumedPlay.path("speed").asInt());

        call("/plan/startOrStop?dbName=" + DB_NAME + "&flag=0&sessionId=" + sessionId);
        JsonNode finalPause = socket.awaitMessageOfType("PAUSE", DEFAULT_TIMEOUT);
        assertEquals(8, finalPause.path("currentTime").asInt());
        assertEquals(3, finalPause.path("speed").asInt());
        assertFalse(finalPause.path("running").asBoolean());
    }

    @Test
    void shouldHandleRepeatedThreeXPauseResumeCyclesUntilTwentyMinutesFinish() throws Exception {
        String sessionId = newSessionId();
        TestWebSocketClient socket = connect(sessionId);

        initializeAndStart(socket, DB_NAME, 0, sessionId);

        call("/plan/speed?dbName=" + DB_NAME + "&speed=3&sessionId=" + sessionId);
        JsonNode speed = socket.awaitMessageOfType("SPEED", DEFAULT_TIMEOUT);
        assertEquals(3, speed.path("speed").asInt());
        assertTrue(speed.path("running").asBoolean());

        int currentTime = 0;
        int cycle = 0;
        while (currentTime < 1200) {
            cycle++;

            int targetAfterShortRun = Math.min(currentTime + 60, 1200);
            JsonNode shortRunPlay = waitUntilCurrentTimeAtLeast(socket, targetAfterShortRun, Duration.ofSeconds(5));
            currentTime = shortRunPlay.path("currentTime").asInt();
            assertEquals(3, shortRunPlay.path("speed").asInt());

            if (currentTime >= 1200) {
                break;
            }

            call("/plan/startOrStop?dbName=" + DB_NAME + "&flag=0&sessionId=" + sessionId);
            JsonNode firstPause = socket.awaitMessageOfType("PAUSE", DEFAULT_TIMEOUT);
            assertEquals(currentTime, firstPause.path("currentTime").asInt(), "first pause currentTime mismatch in cycle " + cycle);
            socket.assertNoMessageOfType("PLAY", Duration.ofMillis(500));

            call("/plan/startOrStop?dbName=" + DB_NAME + "&flag=1&sessionId=" + sessionId);
            JsonNode firstStart = socket.awaitMessageOfType("START", DEFAULT_TIMEOUT);
            assertEquals(currentTime, firstStart.path("currentTime").asInt(), "first start currentTime mismatch in cycle " + cycle);
            assertEquals(3, firstStart.path("speed").asInt());

            int targetAfterLongRun = Math.min(currentTime + 150, 1200);
            JsonNode longRunPlay = waitUntilCurrentTimeAtLeast(socket, targetAfterLongRun, Duration.ofSeconds(8));
            currentTime = longRunPlay.path("currentTime").asInt();
            assertEquals(3, longRunPlay.path("speed").asInt());

            if (currentTime >= 1200) {
                break;
            }

            call("/plan/startOrStop?dbName=" + DB_NAME + "&flag=0&sessionId=" + sessionId);
            JsonNode secondPause = socket.awaitMessageOfType("PAUSE", DEFAULT_TIMEOUT);
            assertEquals(currentTime, secondPause.path("currentTime").asInt(), "second pause currentTime mismatch in cycle " + cycle);
            socket.assertNoMessageOfType("PLAY", Duration.ofMillis(500));

            call("/plan/startOrStop?dbName=" + DB_NAME + "&flag=1&sessionId=" + sessionId);
            JsonNode secondStart = socket.awaitMessageOfType("START", DEFAULT_TIMEOUT);
            assertEquals(currentTime, secondStart.path("currentTime").asInt(), "second start currentTime mismatch in cycle " + cycle);
            assertEquals(3, secondStart.path("speed").asInt());
        }

        JsonNode pauseAtFinish = socket.awaitMessageOfType("PAUSE", DEFAULT_TIMEOUT);
        assertEquals(1200, pauseAtFinish.path("currentTime").asInt());
        assertFalse(pauseAtFinish.path("running").asBoolean());

        JsonNode finish = socket.awaitMessageOfType("FINISH", DEFAULT_TIMEOUT);
        assertEquals(1200, finish.path("currentTime").asInt());
        assertEquals(1200, finish.path("fullTime").asInt());
        socket.assertNoMessageOfType("ERROR", Duration.ofMillis(350));
        socket.assertNoMessageOfType("PLAY", Duration.ofMillis(350));
    }

    @Test
    void shouldHandleOneXForFiveSecondsThenPauseSwitchToFiveXAndRunToTwentySeconds() throws Exception {
        String sessionId = newSessionId();
        TestWebSocketClient socket = connect(sessionId);

        initializeAndStart(socket, DB_NAME, 0, sessionId);

        JsonNode playAtFive = waitUntilCurrentTimeAtLeast(socket, 5, Duration.ofSeconds(3));
        assertEquals(5, playAtFive.path("currentTime").asInt());
        assertEquals(1, playAtFive.path("speed").asInt());
        assertEquals(0, playAtFive.path("fullTime").asInt());

        call("/plan/startOrStop?dbName=" + DB_NAME + "&flag=0&sessionId=" + sessionId);
        JsonNode pauseAtFive = socket.awaitMessageOfType("PAUSE", DEFAULT_TIMEOUT);
        assertEquals(5, pauseAtFive.path("currentTime").asInt());
        assertFalse(pauseAtFive.path("running").asBoolean());
        socket.assertNoMessageOfType("PLAY", Duration.ofMillis(350));

        call("/plan/speed?dbName=" + DB_NAME + "&speed=5&sessionId=" + sessionId);
        JsonNode speedFive = socket.awaitMessageOfType("SPEED", DEFAULT_TIMEOUT);
        assertEquals(5, speedFive.path("speed").asInt());
        assertEquals(5, speedFive.path("currentTime").asInt());
        assertFalse(speedFive.path("running").asBoolean());
        JsonNode startAtFive = socket.awaitMessageOfType("START", DEFAULT_TIMEOUT);
        assertEquals(5, startAtFive.path("currentTime").asInt());
        assertEquals(5, startAtFive.path("speed").asInt());
        assertTrue(startAtFive.path("running").asBoolean());

        JsonNode playAtTen = socket.awaitMessageOfType("PLAY", DEFAULT_TIMEOUT);
        assertEquals(10, playAtTen.path("currentTime").asInt());
        assertEquals(10, playAtTen.path("fullTime").asInt());
        assertEquals(5, playAtTen.path("speed").asInt());

        JsonNode playAtFifteen = socket.awaitMessageOfType("PLAY", DEFAULT_TIMEOUT);
        assertEquals(15, playAtFifteen.path("currentTime").asInt());
        assertEquals(10, playAtFifteen.path("fullTime").asInt());
        assertEquals(5, playAtFifteen.path("speed").asInt());

        JsonNode playAtTwenty = socket.awaitMessageOfType("PLAY", DEFAULT_TIMEOUT);
        assertEquals(20, playAtTwenty.path("currentTime").asInt());
        assertEquals(20, playAtTwenty.path("fullTime").asInt());
        assertEquals(5, playAtTwenty.path("speed").asInt());

        call("/plan/startOrStop?dbName=" + DB_NAME + "&flag=0&sessionId=" + sessionId);
        JsonNode pauseAtTwenty = socket.awaitMessageOfType("PAUSE", DEFAULT_TIMEOUT);
        assertEquals(20, pauseAtTwenty.path("currentTime").asInt());
        assertFalse(pauseAtTwenty.path("running").asBoolean());
        socket.assertNoMessageOfType("PLAY", Duration.ofMillis(350));
        socket.assertNoMessageOfType("ERROR", Duration.ofMillis(350));
    }

    @Test
    void shouldHonorCustomSpeedPauseAndSkip() throws Exception {
        String sessionId = newSessionId();
        TestWebSocketClient socket = connect(sessionId);

        initializeAndStart(socket, DB_NAME, 0, sessionId);
        JsonNode firstPlay = socket.awaitMessageOfType("PLAY", DEFAULT_TIMEOUT);
        assertEquals(1, firstPlay.path("currentTime").asInt());

        call("/plan/speed?dbName=" + DB_NAME + "&speed=3&sessionId=" + sessionId);
        JsonNode speed = socket.awaitMessageOfType("SPEED", DEFAULT_TIMEOUT);
        assertEquals(3, speed.path("speed").asInt());

        JsonNode acceleratedPlay = socket.awaitMessageOfType("PLAY", DEFAULT_TIMEOUT);
        assertEquals(4, acceleratedPlay.path("currentTime").asInt());

        call("/plan/skip?dbName=" + DB_NAME + "&skip=13&sessionId=" + sessionId);
        JsonNode skip = socket.awaitMessageOfType("SKIP", DEFAULT_TIMEOUT);
        assertEquals(13, skip.path("currentTime").asInt());
        assertEquals(10, skip.path("fullTime").asInt());

        JsonNode nextPlay = socket.awaitMessageOfType("PLAY", DEFAULT_TIMEOUT);
        assertEquals(16, nextPlay.path("currentTime").asInt());

        call("/plan/startOrStop?dbName=" + DB_NAME + "&flag=0&sessionId=" + sessionId);
        JsonNode pause = socket.awaitMessageOfType("PAUSE", DEFAULT_TIMEOUT);
        assertEquals(16, pause.path("currentTime").asInt());
        socket.assertNoMessageOfType("PLAY", Duration.ofMillis(350));
    }

    @Test
    void shouldUseTwentyMinuteFullSnapshotWhenIntervalChanges() throws Exception {
        String sessionId = newSessionId();
        TestWebSocketClient socket = connect(sessionId);

        initializeAndStart(socket, DB_NAME, 0, sessionId);

        call("/plan/fullSaveInterval?dbName=" + DB_NAME + "&fullSaveIntervalSeconds=20&sessionId=" + sessionId);
        JsonNode interval = socket.awaitMessageOfType("INTERVAL", DEFAULT_TIMEOUT);
        assertEquals(0, interval.path("fullTime").asInt());

        call("/plan/skip?dbName=" + DB_NAME + "&skip=33&sessionId=" + sessionId);
        JsonNode skip = socket.awaitMessageOfType("SKIP", DEFAULT_TIMEOUT);
        assertEquals(33, skip.path("currentTime").asInt());
        assertEquals(20, skip.path("fullTime").asInt());
    }

    @Test
    void shouldPauseWhenSpeedIsZero() throws Exception {
        String sessionId = newSessionId();
        TestWebSocketClient socket = connect(sessionId);

        initializeAndStart(socket, DB_NAME, 0, sessionId);
        JsonNode firstPlay = socket.awaitMessageOfType("PLAY", DEFAULT_TIMEOUT);
        int currentTime = firstPlay.path("currentTime").asInt();

        call("/plan/speed?dbName=" + DB_NAME + "&speed=0&sessionId=" + sessionId);
        JsonNode speed = socket.awaitMessageOfType("SPEED", DEFAULT_TIMEOUT);
        assertEquals(0, speed.path("speed").asInt());
        assertFalse(speed.path("running").asBoolean());
        assertEquals(currentTime, speed.path("currentTime").asInt());
        socket.assertNoMessageOfType("PLAY", Duration.ofMillis(350));
    }

    @Test
    void shouldSendFinishWhenPlaybackReachesEnd() throws Exception {
        String sessionId = newSessionId();
        TestWebSocketClient socket = connect(sessionId);

        JsonNode init = initializeAndStart(socket, DB_NAME, 1198, sessionId);
        assertEquals(1198, init.path("currentTime").asInt());

        call("/plan/speed?dbName=" + DB_NAME + "&speed=3&sessionId=" + sessionId);
        JsonNode speed = socket.awaitMessageOfType("SPEED", DEFAULT_TIMEOUT);
        assertEquals(3, speed.path("speed").asInt());

        JsonNode lastPlay = socket.awaitMessageOfType("PLAY", DEFAULT_TIMEOUT);
        assertEquals(1200, lastPlay.path("currentTime").asInt());

        JsonNode pause = socket.awaitMessageOfType("PAUSE", DEFAULT_TIMEOUT);
        assertEquals(1200, pause.path("currentTime").asInt());

        JsonNode finish = socket.awaitMessageOfType("FINISH", DEFAULT_TIMEOUT);
        assertEquals(1200, finish.path("currentTime").asInt());
        assertEquals(1200, finish.path("fullTime").asInt());
        socket.assertNoMessageOfType("PLAY", Duration.ofMillis(350));
    }

    @Test
    void shouldKeepMultipleSessionsIsolated() throws Exception {
        String sessionId1 = newSessionId();
        String sessionId2 = newSessionId();
        TestWebSocketClient socket1 = connect(sessionId1);
        TestWebSocketClient socket2 = connect(sessionId2);

        initializeOnly(socket1, DB_NAME, 0, sessionId1);
        initializeOnly(socket2, DB_NAME, 10, sessionId2);
        call("/plan/startOrStop?dbName=" + DB_NAME + "&flag=1&sessionId=" + sessionId1);
        call("/plan/startOrStop?dbName=" + DB_NAME + "&flag=1&sessionId=" + sessionId2);

        JsonNode init1 = socket1.awaitMessage("INIT", DB_NAME, DEFAULT_TIMEOUT);
        JsonNode init2 = socket2.awaitMessage("INIT", DB_NAME, DEFAULT_TIMEOUT);
        assertEquals(sessionId1, init1.path("sessionId").asText());
        assertEquals(sessionId2, init2.path("sessionId").asText());
        assertEquals(0, init1.path("currentTime").asInt());
        assertEquals(10, init2.path("currentTime").asInt());

        call("/plan/speed?dbName=" + DB_NAME + "&speed=3&sessionId=" + sessionId2);
        JsonNode speed2 = socket2.awaitMessageOfType("SPEED", DEFAULT_TIMEOUT);
        assertEquals(3, speed2.path("speed").asInt());

        JsonNode play1 = socket1.awaitMessageOfType("PLAY", DEFAULT_TIMEOUT);
        JsonNode play2 = socket2.awaitMessageOfType("PLAY", DEFAULT_TIMEOUT);
        assertEquals(sessionId1, play1.path("sessionId").asText());
        assertEquals(sessionId2, play2.path("sessionId").asText());
        assertEquals(1, play1.path("currentTime").asInt());
        assertEquals(13, play2.path("currentTime").asInt());

        call("/plan/startOrStop?dbName=" + DB_NAME + "&flag=0&sessionId=" + sessionId1);
        JsonNode pause1 = socket1.awaitMessageOfType("PAUSE", DEFAULT_TIMEOUT);
        assertEquals(sessionId1, pause1.path("sessionId").asText());
        socket1.assertNoMessageOfType("PLAY", Duration.ofMillis(350));

        JsonNode nextPlay2 = socket2.awaitMessageOfType("PLAY", DEFAULT_TIMEOUT);
        assertEquals(sessionId2, nextPlay2.path("sessionId").asText());
        assertTrue(nextPlay2.path("currentTime").asInt() >= 16);
    }

    @Test
    void shouldReleaseResourcesAfterDestroy() throws Exception {
        String sessionId = newSessionId();
        TestWebSocketClient socket = connect(sessionId);

        initializeAndStart(socket, DB_NAME, 0, sessionId);
        JsonNode firstPlay = socket.awaitMessageOfType("PLAY", DEFAULT_TIMEOUT);
        assertEquals(1, firstPlay.path("currentTime").asInt());

        call("/plan/destroy?dbName=" + DB_NAME + "&sessionId=" + sessionId);
        JsonNode destroy = socket.awaitMessageOfType("DESTROY", DEFAULT_TIMEOUT);
        assertEquals(1, destroy.path("currentTime").asInt());
        socket.assertNoMessageOfType("PLAY", Duration.ofMillis(350));

        JsonNode restartedInit = initializeAndStart(socket, DB_NAME, 0, sessionId);
        assertEquals(0, restartedInit.path("currentTime").asInt());
    }

    @Test
    void shouldKeepMultipleDbNamesIsolated() throws Exception {
        String altDbName = "plandeduce_alt";
        initializeDatabase(altDbName);

        String sharedSessionId = newSessionId();
        registerTask(altDbName, sharedSessionId);
        TestWebSocketClient socket = connect(sharedSessionId);

        initializeOnly(socket, DB_NAME, 0, sharedSessionId);
        initializeOnly(socket, altDbName, 10, sharedSessionId);
        call("/plan/startOrStop?dbName=" + DB_NAME + "&flag=1&sessionId=" + sharedSessionId);
        call("/plan/startOrStop?dbName=" + altDbName + "&flag=1&sessionId=" + sharedSessionId);

        JsonNode initDefault = socket.awaitMessage("INIT", DB_NAME, DEFAULT_TIMEOUT);
        JsonNode initAlt = socket.awaitMessage("INIT", altDbName, DEFAULT_TIMEOUT);
        assertEquals(0, initDefault.path("currentTime").asInt());
        assertEquals(10, initAlt.path("currentTime").asInt());

        call("/plan/startOrStop?dbName=" + DB_NAME + "&flag=0&sessionId=" + sharedSessionId);
        JsonNode pauseDefault = socket.awaitMessage("PAUSE", DB_NAME, DEFAULT_TIMEOUT);
        assertEquals(DB_NAME, pauseDefault.path("dbName").asText());

        JsonNode playAlt = socket.awaitMessage("PLAY", altDbName, DEFAULT_TIMEOUT);
        assertEquals(11, playAlt.path("currentTime").asInt());
        socket.assertNoMessage("PLAY", DB_NAME, Duration.ofMillis(350));

        call("/plan/skip?dbName=" + altDbName + "&skip=13&sessionId=" + sharedSessionId);
        JsonNode skipAlt = socket.awaitMessage("SKIP", altDbName, DEFAULT_TIMEOUT);
        assertEquals(13, skipAlt.path("currentTime").asInt());
    }

    @Test
    void shouldHandleRapidSkipSpeedPauseCombinations() throws Exception {
        String sessionId = newSessionId();
        TestWebSocketClient socket = connect(sessionId);

        initializeAndStart(socket, DB_NAME, 0, sessionId);
        socket.awaitMessageOfType("PLAY", DEFAULT_TIMEOUT);

        call("/plan/speed?dbName=" + DB_NAME + "&speed=2&sessionId=" + sessionId);
        JsonNode speed2 = socket.awaitMessageOfType("SPEED", DEFAULT_TIMEOUT);
        assertEquals(2, speed2.path("speed").asInt());

        call("/plan/skip?dbName=" + DB_NAME + "&skip=13&sessionId=" + sessionId);
        JsonNode skip13 = socket.awaitMessageOfType("SKIP", DEFAULT_TIMEOUT);
        assertEquals(13, skip13.path("currentTime").asInt());

        call("/plan/speed?dbName=" + DB_NAME + "&speed=4&sessionId=" + sessionId);
        JsonNode speed4 = socket.awaitMessageOfType("SPEED", DEFAULT_TIMEOUT);
        assertEquals(4, speed4.path("speed").asInt());

        JsonNode play17 = socket.awaitMessageOfType("PLAY", DEFAULT_TIMEOUT);
        assertEquals(17, play17.path("currentTime").asInt());

        call("/plan/speed?dbName=" + DB_NAME + "&speed=0&sessionId=" + sessionId);
        JsonNode speed0 = socket.awaitMessageOfType("SPEED", DEFAULT_TIMEOUT);
        assertEquals(0, speed0.path("speed").asInt());
        socket.assertNoMessageOfType("PLAY", Duration.ofMillis(350));

        call("/plan/startOrStop?dbName=" + DB_NAME + "&flag=1&sessionId=" + sessionId);
        JsonNode start = socket.awaitMessageOfType("START", DEFAULT_TIMEOUT);
        assertEquals(1, start.path("speed").asInt());

        JsonNode play18 = socket.awaitMessageOfType("PLAY", DEFAULT_TIMEOUT);
        assertEquals(18, play18.path("currentTime").asInt());

        call("/plan/skip?dbName=" + DB_NAME + "&skip=22&sessionId=" + sessionId);
        JsonNode skip22 = socket.awaitMessageOfType("SKIP", DEFAULT_TIMEOUT);
        assertEquals(22, skip22.path("currentTime").asInt());
        assertEquals(20, skip22.path("fullTime").asInt());

        call("/plan/startOrStop?dbName=" + DB_NAME + "&flag=0&sessionId=" + sessionId);
        socket.awaitMessageOfType("PAUSE", DEFAULT_TIMEOUT);
        socket.assertNoMessageOfType("ERROR", Duration.ofMillis(350));
        socket.assertNoMessageOfType("PLAY", Duration.ofMillis(350));
    }

    @Test
    void shouldRejectInvalidFullSaveInterval() {
        String sessionId = newSessionId();

        ResponseEntity<String> response = call("/plan/fullSaveInterval?dbName=" + DB_NAME + "&fullSaveIntervalSeconds=0&sessionId=" + sessionId);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertTrue(response.getBody() != null && response.getBody().contains("全量保存间隔必须大于 0 秒"));
    }

    @Test
    void shouldClampNegativeSkipToZero() throws Exception {
        String sessionId = newSessionId();
        TestWebSocketClient socket = connect(sessionId);

        initializeAndStart(socket, DB_NAME, 0, sessionId);
        call("/plan/skip?dbName=" + DB_NAME + "&skip=-5&sessionId=" + sessionId);

        JsonNode skip = socket.awaitMessageOfType("SKIP", DEFAULT_TIMEOUT);
        assertEquals(0, skip.path("currentTime").asInt());
        assertEquals(0, skip.path("fullTime").asInt());
    }

    @Test
    void shouldReturnProgressBarStartAndEndTimeWhenInitializing() throws Exception {
        String sessionId = newSessionId();
        TestWebSocketClient socket = connect(sessionId);

        ResponseEntity<String> response = call("/plan/sendPlanDeduce?dbName=" + DB_NAME + "&skip=0&sessionId=" + sessionId);
        assertEquals(HttpStatus.OK, response.getStatusCode());

        JsonNode body = objectMapper.readTree(response.getBody());
        assertEquals(DB_NAME, body.path("dbName").asText());
        assertEquals(sessionId, body.path("sessionId").asText());
        assertEquals("2026-01-01 00:00:00", body.path("startTime").asText());
        assertEquals(1200, body.path("endTime").asInt());
        socket.assertNoMessage(Duration.ofMillis(350));
    }

    @Test
    void shouldAcceptControlCommandsWithoutWebSocketConnection() {
        String sessionId = newSessionId();

        ResponseEntity<String> initResponse = call("/plan/sendPlanDeduce?dbName=" + DB_NAME + "&skip=0&sessionId=" + sessionId);
        ResponseEntity<String> speedResponse = call("/plan/speed?dbName=" + DB_NAME + "&speed=3&sessionId=" + sessionId);
        ResponseEntity<String> pauseResponse = call("/plan/startOrStop?dbName=" + DB_NAME + "&flag=0&sessionId=" + sessionId);

        assertEquals(HttpStatus.OK, initResponse.getStatusCode());
        assertEquals(HttpStatus.OK, speedResponse.getStatusCode());
        assertEquals(HttpStatus.OK, pauseResponse.getStatusCode());
    }

    @Test
    void shouldRejectReinitializingTaskWhileItIsExecuting() throws Exception {
        String sessionId = newSessionId();
        TestWebSocketClient socket = connect(sessionId);

        ResponseEntity<String> firstInit = call("/plan/sendPlanDeduce?dbName=" + DB_NAME + "&skip=0&sessionId=" + sessionId);
        assertEquals(HttpStatus.OK, firstInit.getStatusCode());
        socket.assertNoMessage(Duration.ofMillis(350));
        call("/plan/startOrStop?dbName=" + DB_NAME + "&flag=1&sessionId=" + sessionId);
        socket.awaitMessageOfType("INIT", DEFAULT_TIMEOUT);

        ResponseEntity<String> secondInit = call("/plan/sendPlanDeduce?dbName=" + DB_NAME + "&skip=5&sessionId=" + sessionId);
        assertEquals(HttpStatus.BAD_REQUEST, secondInit.getStatusCode());
        assertTrue(secondInit.getBody() != null && secondInit.getBody().contains("当前任务正在执行"));
    }

    private JsonNode initializeAndStart(TestWebSocketClient socket, String dbName, int skip, String sessionId) throws Exception {
        initializeOnly(socket, dbName, skip, sessionId);
        call("/plan/startOrStop?dbName=" + dbName + "&flag=1&sessionId=" + sessionId);
        return socket.awaitMessage("INIT", dbName, DEFAULT_TIMEOUT);
    }

    private void initializeOnly(TestWebSocketClient socket, String dbName, int skip, String sessionId) throws Exception {
        ResponseEntity<String> response = call("/plan/sendPlanDeduce?dbName=" + dbName + "&skip=" + skip + "&sessionId=" + sessionId);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        socket.assertNoMessage(Duration.ofMillis(350));
    }

    private ResponseEntity<String> call(String path) {
        return restTemplate.getForEntity(httpUrl(path), String.class);
    }

    private String httpUrl(String path) {
        return "http://localhost:" + port + path;
    }

    private String wsUrl(String sessionId) {
        return "ws://localhost:" + port + "/ws/planDeduce?sessionId=" + sessionId;
    }

    private String newSessionId() {
        String sessionId = "test-" + UUID.randomUUID();
        registerTask(DB_NAME, sessionId);
        return sessionId;
    }

    private void registerTask(String dbName, String sessionId) {
        tasksToDestroy.add(new TaskRef(dbName, sessionId));
    }

    private TestWebSocketClient connect(String sessionId) throws Exception {
        TestWebSocketClient client = new TestWebSocketClient(objectMapper);
        client.connect(wsUrl(sessionId));
        sockets.add(client);
        return client;
    }

    private void assertSimtimes(JsonNode dataNode, int... expected) {
        assertNotNull(dataNode);
        assertEquals(expected.length, dataNode.size());
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], dataNode.get(i).path("simTime").asInt(), "simTime mismatch at index " + i);
        }
    }

    private void assertEventTimes(JsonNode dataNode, int... expected) {
        assertNotNull(dataNode);
        assertEquals(expected.length, dataNode.size());
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], dataNode.get(i).path("simTime").asInt(), "simTime mismatch at index " + i);
            assertEquals(expected[i], dataNode.get(i).path("physicalTime").asInt(), "physicalTime mismatch at index " + i);
            assertEquals(DB_NAME, dataNode.get(i).path("roomId").asText(), "roomId mismatch at index " + i);
        }
    }

    private void assertPieceFieldsPresent(JsonNode dataNode) {
        assertTrue(dataNode.isArray());
        assertTrue(dataNode.size() > 0);
        JsonNode first = dataNode.get(0);
        assertTrue(first.hasNonNull("roomObjectId"));
        assertTrue(first.hasNonNull("scenarioId"));
        assertTrue(first.hasNonNull("roomId"));
        assertTrue(first.hasNonNull("objCode"));
        assertTrue(first.hasNonNull("side"));
        assertTrue(first.hasNonNull("objName"));
        assertTrue(first.hasNonNull("currentPos"));
        assertTrue(first.hasNonNull("nextPos"));
        assertTrue(first.hasNonNull("direction"));
        assertTrue(first.hasNonNull("currentSpeed"));
        assertTrue(first.hasNonNull("visible"));
        assertTrue(first.hasNonNull("simTime"));
        assertTrue(first.hasNonNull("targetId"));
    }

    private int[] repeatSimtime(int simtime) {
        int[] values = new int[PIECES_PER_SECOND];
        for (int i = 0; i < PIECES_PER_SECOND; i++) {
            values[i] = simtime;
        }
        return values;
    }

    private int[] rangeRepeated(int startInclusive, int endInclusive) {
        int[] values = new int[(endInclusive - startInclusive + 1) * PIECES_PER_SECOND];
        int index = 0;
        for (int simtime = startInclusive; simtime <= endInclusive; simtime++) {
            for (int i = 0; i < PIECES_PER_SECOND; i++) {
                values[index++] = simtime;
            }
        }
        return values;
    }

    private int[] range(int startInclusive, int endInclusive) {
        int[] values = new int[endInclusive - startInclusive + 1];
        int index = 0;
        for (int simtime = startInclusive; simtime <= endInclusive; simtime++) {
            values[index++] = simtime;
        }
        return values;
    }

    private int[] concat(int[] left, int[] right) {
        int[] merged = new int[left.length + right.length];
        System.arraycopy(left, 0, merged, 0, left.length);
        System.arraycopy(right, 0, merged, left.length, right.length);
        return merged;
    }

    private JsonNode waitUntilCurrentTimeAtLeast(TestWebSocketClient socket, int expectedCurrentTime, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        JsonNode lastPlay = null;
        while (System.nanoTime() < deadline) {
            JsonNode play = socket.awaitMessageOfType("PLAY", Duration.ofSeconds(1));
            lastPlay = play;
            if (play.path("currentTime").asInt() >= expectedCurrentTime) {
                return play;
            }
        }
        fail("Timed out waiting currentTime to reach at least " + expectedCurrentTime + ", lastPlay=" + lastPlay);
        return null;
    }

    private void initializeDatabase(String dbName) throws Exception {
        try (Connection connection = DriverManager.getConnection(buildJdbcUrl(dbName), dataSourceProperties.getUsername(), dataSourceProperties.getPassword())) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("data.sql"));
        }
    }

    private String buildJdbcUrl(String dbName) {
        String baseUrl = dataSourceProperties.getUrl();
        int start = "jdbc:h2:mem:".length();
        int end = baseUrl.indexOf(';', start);
        if (end < 0) {
            end = baseUrl.length();
        }
        return baseUrl.substring(0, start) + dbName + baseUrl.substring(end);
    }

    private static class TaskRef {
        private final String dbName;
        private final String sessionId;

        private TaskRef(String dbName, String sessionId) {
            this.dbName = dbName;
            this.sessionId = sessionId;
        }
    }

    private static class TestWebSocketClient extends TextWebSocketHandler {
        private final StandardWebSocketClient client;
        private final ObjectMapper objectMapper;
        private final BlockingQueue<JsonNode> messages = new LinkedBlockingQueue<>();
        private WebSocketSession session;

        private TestWebSocketClient(ObjectMapper objectMapper) {
            WebSocketContainer container = ContainerProvider.getWebSocketContainer();
            container.setDefaultMaxTextMessageBufferSize(1024 * 1024);
            this.client = new StandardWebSocketClient(container);
            this.objectMapper = objectMapper;
        }

        private void connect(String url) throws ExecutionException, InterruptedException, TimeoutException {
            CompletableFuture<WebSocketSession> future = client.doHandshake(this, new WebSocketHttpHeaders(), URI.create(url)).completable();
            this.session = future.get(3, TimeUnit.SECONDS);
        }

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) throws IOException {
            messages.add(objectMapper.readTree(message.getPayload()));
        }

        private JsonNode awaitMessageOfType(String type, Duration timeout) throws InterruptedException {
            long deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadline) {
                JsonNode message = messages.poll(100, TimeUnit.MILLISECONDS);
                if (message == null) {
                    continue;
                }
                if (type.equals(message.path("type").asText())) {
                    return message;
                }
            }
            fail("Timed out waiting for message type " + type);
            return null;
        }

        private JsonNode awaitMessage(String type, String dbName, Duration timeout) throws InterruptedException {
            long deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadline) {
                JsonNode message = messages.poll(100, TimeUnit.MILLISECONDS);
                if (message == null) {
                    continue;
                }
                if (type.equals(message.path("type").asText()) && dbName.equals(message.path("dbName").asText())) {
                    return message;
                }
            }
            fail("Timed out waiting for message type " + type + " and dbName " + dbName);
            return null;
        }

        private void assertNoMessageOfType(String type, Duration duration) throws InterruptedException {
            long deadline = System.nanoTime() + duration.toNanos();
            while (System.nanoTime() < deadline) {
                JsonNode message = messages.poll(50, TimeUnit.MILLISECONDS);
                if (message == null) {
                    continue;
                }
                assertFalse(type.equals(message.path("type").asText()), "Unexpected message type: " + type + ", payload=" + message);
            }
        }

        private void assertNoMessage(String type, String dbName, Duration duration) throws InterruptedException {
            long deadline = System.nanoTime() + duration.toNanos();
            while (System.nanoTime() < deadline) {
                JsonNode message = messages.poll(50, TimeUnit.MILLISECONDS);
                if (message == null) {
                    continue;
                }
                assertFalse(type.equals(message.path("type").asText()) && dbName.equals(message.path("dbName").asText()),
                        "Unexpected message type: " + type + ", dbName=" + dbName + ", payload=" + message);
            }
        }

        private void assertNoMessage(Duration duration) throws InterruptedException {
            JsonNode message = messages.poll(duration.toMillis(), TimeUnit.MILLISECONDS);
            assertTrue(message == null, "Unexpected websocket message: " + message);
        }

        private void close() throws Exception {
            if (session != null && session.isOpen()) {
                session.close();
            }
        }
    }
}
