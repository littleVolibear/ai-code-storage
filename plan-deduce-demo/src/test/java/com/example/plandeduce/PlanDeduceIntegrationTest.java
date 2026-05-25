package com.example.plandeduce;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import javax.websocket.ContainerProvider;
import javax.websocket.WebSocketContainer;
import java.io.IOException;
import java.net.URI;
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
    private static final String DB_NAME = "1";
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(3);
    private static final int PIECES_PER_SECOND = 3;

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

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
        assertEquals(11, skipMessage.path("realTime").asInt());
        assertEquals(10, skipMessage.path("fullTime").asInt());
        assertEquals(11, skipMessage.path("deduceTime").asInt());
        assertSimtimes(skipMessage, skipMessage.path("data"), repeatSimtime(11));
        assertEventTimes(skipMessage, skipMessage.path("eventData"), range(0, 11));
        assertRoomObjectFieldsPresent(skipMessage.path("data"));
    }

    @Test
    void shouldReturnFullAndCumulativeIncrementalDataWhenSkippingToThirteen() throws Exception {
        String sessionId = newSessionId();
        TestWebSocketClient socket = connect(sessionId);

        initializeAndStart(socket, DB_NAME, 0, sessionId);
        call("/plan/skip?dbName=" + DB_NAME + "&skip=13&sessionId=" + sessionId);

        JsonNode skipMessage = socket.awaitMessageOfType("SKIP", DEFAULT_TIMEOUT);
        assertEquals(13, skipMessage.path("realTime").asInt());
        assertEquals(10, skipMessage.path("fullTime").asInt());
        assertEquals(13, skipMessage.path("deduceTime").asInt());
        assertSimtimes(skipMessage, skipMessage.path("data"), repeatSimtime(13));
        assertEventTimes(skipMessage, skipMessage.path("eventData"), range(0, 13));
        assertRoomObjectFieldsPresent(skipMessage.path("data"));
    }

    @Test
    void shouldStartPauseAndResumeAtOneX() throws Exception {
        String sessionId = newSessionId();
        TestWebSocketClient socket = connect(sessionId);

        JsonNode init = initializeAndStart(socket, DB_NAME, 0, sessionId);
        assertEquals(0, init.path("realTime").asInt());
        assertEquals(0, init.path("deduceTime").asInt());
        assertEquals(0, init.path("fullTime").asInt());
        assertEquals(1200, init.path("maxSimTime").asInt());
        assertEventTimes(init, init.path("eventData"), 0);

        JsonNode firstPlay = socket.awaitMessageOfType("PLAY", DEFAULT_TIMEOUT);
        assertEquals(1, firstPlay.path("realTime").asInt());
        assertEquals(1, firstPlay.path("deduceTime").asInt());
        assertEquals(0, firstPlay.path("fullTime").asInt());
        assertEventTimes(firstPlay, firstPlay.path("eventData"), 1);

        call("/plan/startOrStop?dbName=" + DB_NAME + "&flag=0&sessionId=" + sessionId);
        JsonNode pause = socket.awaitMessageOfType("PAUSE", DEFAULT_TIMEOUT);
        int pausedAt = pause.path("realTime").asInt();
        socket.assertNoMessageOfType("PLAY", Duration.ofMillis(350));

        call("/plan/startOrStop?dbName=" + DB_NAME + "&flag=1&sessionId=" + sessionId);
        JsonNode start = socket.awaitMessageOfType("START", DEFAULT_TIMEOUT);
        assertEquals(pausedAt, start.path("realTime").asInt());

        JsonNode resumedPlay = socket.awaitMessageOfType("PLAY", DEFAULT_TIMEOUT);
        assertEquals(pausedAt + 1, resumedPlay.path("realTime").asInt());
    }

    @Test
    void shouldHandlePauseThenSwitchToThreeXThenPauseStartPauseSequence() throws Exception {
        String sessionId = newSessionId();
        TestWebSocketClient socket = connect(sessionId);

        initializeAndStart(socket, DB_NAME, 0, sessionId);

        JsonNode firstPlay = socket.awaitMessageOfType("PLAY", DEFAULT_TIMEOUT);
        assertEquals(1, firstPlay.path("realTime").asInt());
        assertEquals(1, firstPlay.path("speed").asInt());
        assertTrue(firstPlay.path("running").asBoolean());

        call("/plan/startOrStop?dbName=" + DB_NAME + "&flag=0&sessionId=" + sessionId);
        JsonNode firstPause = socket.awaitMessageOfType("PAUSE", DEFAULT_TIMEOUT);
        int pausedAt = firstPause.path("realTime").asInt();
        assertEquals(1, pausedAt);
        assertFalse(firstPause.path("running").asBoolean());
        socket.assertNoMessageOfType("PLAY", Duration.ofMillis(350));

        call("/plan/speed?dbName=" + DB_NAME + "&speed=3&sessionId=" + sessionId);
        JsonNode speed = socket.awaitMessageOfType("SPEED", DEFAULT_TIMEOUT);
        assertEquals(3, speed.path("speed").asInt());
        assertFalse(speed.path("running").asBoolean());
        assertEquals(pausedAt, speed.path("realTime").asInt());
        JsonNode firstStart = socket.awaitMessageOfType("START", DEFAULT_TIMEOUT);
        assertEquals(pausedAt, firstStart.path("realTime").asInt());
        assertEquals(3, firstStart.path("speed").asInt());
        assertTrue(firstStart.path("running").asBoolean());

        JsonNode playAtThreeX = socket.awaitMessageOfType("PLAY", DEFAULT_TIMEOUT);
        int afterThreeX = playAtThreeX.path("realTime").asInt();
        assertEquals(pausedAt + 1, afterThreeX);
        assertEquals(3, playAtThreeX.path("speed").asInt());

        call("/plan/startOrStop?dbName=" + DB_NAME + "&flag=0&sessionId=" + sessionId);
        JsonNode secondPause = socket.awaitMessageOfType("PAUSE", DEFAULT_TIMEOUT);
        assertEquals(afterThreeX, secondPause.path("realTime").asInt());
        assertFalse(secondPause.path("running").asBoolean());
        socket.assertNoMessageOfType("PLAY", Duration.ofMillis(350));

        call("/plan/startOrStop?dbName=" + DB_NAME + "&flag=1&sessionId=" + sessionId);
        JsonNode start = socket.awaitMessageOfType("START", DEFAULT_TIMEOUT);
        assertEquals(afterThreeX, start.path("realTime").asInt());
        assertEquals(3, start.path("speed").asInt());
        assertTrue(start.path("running").asBoolean());

        JsonNode resumedPlay = socket.awaitMessageOfType("PLAY", DEFAULT_TIMEOUT);
        int resumedAt = resumedPlay.path("realTime").asInt();
        assertEquals(afterThreeX + 1, resumedAt);
        assertEquals(3, resumedPlay.path("speed").asInt());

        call("/plan/startOrStop?dbName=" + DB_NAME + "&flag=0&sessionId=" + sessionId);
        JsonNode thirdPause = socket.awaitMessageOfType("PAUSE", DEFAULT_TIMEOUT);
        assertEquals(resumedAt, thirdPause.path("realTime").asInt());
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
        assertEquals(1, firstPlay.path("realTime").asInt());
        JsonNode secondPlay = socket.awaitMessageOfType("PLAY", DEFAULT_TIMEOUT);
        assertEquals(2, secondPlay.path("realTime").asInt());
        assertEquals(1, secondPlay.path("speed").asInt());

        call("/plan/startOrStop?dbName=" + DB_NAME + "&flag=0&sessionId=" + sessionId);
        JsonNode pause = socket.awaitMessageOfType("PAUSE", DEFAULT_TIMEOUT);
        assertEquals(2, pause.path("realTime").asInt());
        assertFalse(pause.path("running").asBoolean());
        socket.assertNoMessageOfType("PLAY", Duration.ofMillis(350));

        call("/plan/speed?dbName=" + DB_NAME + "&speed=3&sessionId=" + sessionId);
        JsonNode speed = socket.awaitMessageOfType("SPEED", DEFAULT_TIMEOUT);
        assertEquals(3, speed.path("speed").asInt());
        assertEquals(2, speed.path("realTime").asInt());
        assertFalse(speed.path("running").asBoolean());
        JsonNode start = socket.awaitMessageOfType("START", DEFAULT_TIMEOUT);
        assertEquals(2, start.path("realTime").asInt());
        assertEquals(3, start.path("speed").asInt());
        assertTrue(start.path("running").asBoolean());

        JsonNode resumedPlay = socket.awaitMessageOfType("PLAY", DEFAULT_TIMEOUT);
        assertEquals(3, resumedPlay.path("realTime").asInt());
        assertEquals(5, resumedPlay.path("deduceTime").asInt());
        assertSimtimes(resumedPlay, resumedPlay.path("data"), repeatSimtime(5));
        assertEventTimes(resumedPlay, resumedPlay.path("eventData"), 3, 4, 5);
        assertEquals(3, resumedPlay.path("speed").asInt());

        call("/plan/startOrStop?dbName=" + DB_NAME + "&flag=0&sessionId=" + sessionId);
        JsonNode secondPause = socket.awaitMessageOfType("PAUSE", DEFAULT_TIMEOUT);
        assertEquals(3, secondPause.path("realTime").asInt());
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
        assertEquals(0, speed.path("realTime").asInt());
        assertEquals(3, speed.path("speed").asInt());
        assertFalse(speed.path("running").asBoolean());

        call("/plan/startOrStop?dbName=" + DB_NAME + "&flag=1&sessionId=" + sessionId);
        JsonNode init = socket.awaitMessageOfType("INIT", DEFAULT_TIMEOUT);
        assertEquals(0, init.path("realTime").asInt());
        assertEquals(3, init.path("speed").asInt());
        assertTrue(init.path("running").asBoolean());
        assertEquals(1200, init.path("maxSimTime").asInt());

        JsonNode firstPlay = socket.awaitMessageOfType("PLAY", DEFAULT_TIMEOUT);
        assertEquals(1, firstPlay.path("realTime").asInt());
        assertEquals(3, firstPlay.path("speed").asInt());
        assertEquals(3, firstPlay.path("deduceTime").asInt());
        assertEventTimes(firstPlay, firstPlay.path("eventData"), 1, 2, 3);

        call("/plan/startOrStop?dbName=" + DB_NAME + "&flag=0&sessionId=" + sessionId);
        JsonNode pause = socket.awaitMessageOfType("PAUSE", DEFAULT_TIMEOUT);
        assertEquals(1, pause.path("realTime").asInt());
        assertEquals(3, pause.path("speed").asInt());
        assertFalse(pause.path("running").asBoolean());

        call("/plan/startOrStop?dbName=" + DB_NAME + "&flag=1&sessionId=" + sessionId);
        JsonNode start = socket.awaitMessageOfType("START", DEFAULT_TIMEOUT);
        assertEquals(1, start.path("realTime").asInt());
        assertEquals(3, start.path("speed").asInt());
        assertTrue(start.path("running").asBoolean());

        JsonNode resumedPlay = socket.awaitMessageOfType("PLAY", DEFAULT_TIMEOUT);
        assertEquals(2, resumedPlay.path("realTime").asInt());
        assertEquals(3, resumedPlay.path("speed").asInt());

        call("/plan/startOrStop?dbName=" + DB_NAME + "&flag=0&sessionId=" + sessionId);
        JsonNode finalPause = socket.awaitMessageOfType("PAUSE", DEFAULT_TIMEOUT);
        assertEquals(2, finalPause.path("realTime").asInt());
        assertEquals(3, finalPause.path("speed").asInt());
        assertFalse(finalPause.path("running").asBoolean());
    }

    @Test
    void shouldSupportDocumentedScenarioThreeFlow() throws Exception {
        String sessionId = newSessionId();
        TestWebSocketClient socket = connect(sessionId);

        JsonNode init = initializeAndStart(socket, DB_NAME, 0, sessionId);
        assertEquals(1200, init.path("maxSimTime").asInt());

        JsonNode playAtFive = waitUntilRealTimeAtLeast(socket, 5, Duration.ofSeconds(3));
        assertEquals(5, playAtFive.path("realTime").asInt());
        assertBusinessTime(playAtFive, 5);
        assertEquals(1, playAtFive.path("speed").asInt());

        call("/plan/startOrStop?dbName=" + DB_NAME + "&flag=0&sessionId=" + sessionId);
        JsonNode pause = socket.awaitMessageOfType("PAUSE", DEFAULT_TIMEOUT);
        assertEquals(5, pause.path("realTime").asInt());
        assertEquals(1, pause.path("speed").asInt());
        assertFalse(pause.path("running").asBoolean());

        call("/plan/speed?dbName=" + DB_NAME + "&speed=3&sessionId=" + sessionId);
        JsonNode speed = socket.awaitMessageOfType("SPEED", DEFAULT_TIMEOUT);
        assertEquals(5, speed.path("realTime").asInt());
        assertEquals(3, speed.path("speed").asInt());
        assertFalse(speed.path("running").asBoolean());

        call("/plan/startOrStop?dbName=" + DB_NAME + "&flag=1&sessionId=" + sessionId);
        JsonNode start = socket.awaitMessageOfType("START", DEFAULT_TIMEOUT);
        assertEquals(5, start.path("realTime").asInt());
        assertEquals(3, start.path("speed").asInt());
        assertTrue(start.path("running").asBoolean());

        JsonNode resumedPlay = socket.awaitMessageOfType("PLAY", DEFAULT_TIMEOUT);
        assertEquals(6, resumedPlay.path("realTime").asInt());
        assertEquals(8, resumedPlay.path("deduceTime").asInt());
        assertSimtimes(resumedPlay, resumedPlay.path("data"), repeatSimtime(8));
        assertEventTimes(resumedPlay, resumedPlay.path("eventData"), 6, 7, 8);
        assertBusinessTime(resumedPlay, 8);
        assertEquals(3, resumedPlay.path("speed").asInt());

        call("/plan/startOrStop?dbName=" + DB_NAME + "&flag=0&sessionId=" + sessionId);
        JsonNode finalPause = socket.awaitMessageOfType("PAUSE", DEFAULT_TIMEOUT);
        assertEquals(6, finalPause.path("realTime").asInt());
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

        int businessTime = 0;
        int cycle = 0;
        while (businessTime < 1200) {
            cycle++;

            int targetAfterShortRun = Math.min(businessTime + 60, 1200);
            JsonNode shortRunPlay = waitUntilBusinessTimeAtLeast(socket, targetAfterShortRun, Duration.ofSeconds(7));
            businessTime = extractBusinessTime(shortRunPlay);
            assertEquals(3, shortRunPlay.path("speed").asInt());

            if (businessTime >= 1200) {
                break;
            }

            call("/plan/startOrStop?dbName=" + DB_NAME + "&flag=0&sessionId=" + sessionId);
            JsonNode firstPause = socket.awaitMessageOfType("PAUSE", DEFAULT_TIMEOUT);
            assertEquals(shortRunPlay.path("realTime").asInt(), firstPause.path("realTime").asInt(), "first pause realTime mismatch in cycle " + cycle);
            socket.assertNoMessageOfType("PLAY", Duration.ofMillis(500));

            call("/plan/startOrStop?dbName=" + DB_NAME + "&flag=1&sessionId=" + sessionId);
            JsonNode firstStart = socket.awaitMessageOfType("START", DEFAULT_TIMEOUT);
            assertEquals(firstPause.path("realTime").asInt(), firstStart.path("realTime").asInt(), "first start realTime mismatch in cycle " + cycle);
            assertEquals(3, firstStart.path("speed").asInt());

            int targetAfterLongRun = Math.min(businessTime + 150, 1200);
            JsonNode longRunPlay = waitUntilBusinessTimeAtLeast(socket, targetAfterLongRun, Duration.ofSeconds(12));
            businessTime = extractBusinessTime(longRunPlay);
            assertEquals(3, longRunPlay.path("speed").asInt());

            if (businessTime >= 1200) {
                break;
            }

            call("/plan/startOrStop?dbName=" + DB_NAME + "&flag=0&sessionId=" + sessionId);
            JsonNode secondPause = socket.awaitMessageOfType("PAUSE", DEFAULT_TIMEOUT);
            assertEquals(longRunPlay.path("realTime").asInt(), secondPause.path("realTime").asInt(), "second pause realTime mismatch in cycle " + cycle);
            socket.assertNoMessageOfType("PLAY", Duration.ofMillis(500));

            call("/plan/startOrStop?dbName=" + DB_NAME + "&flag=1&sessionId=" + sessionId);
            JsonNode secondStart = socket.awaitMessageOfType("START", DEFAULT_TIMEOUT);
            assertEquals(secondPause.path("realTime").asInt(), secondStart.path("realTime").asInt(), "second start realTime mismatch in cycle " + cycle);
            assertEquals(3, secondStart.path("speed").asInt());
        }

        JsonNode pauseAtFinish = socket.awaitMessageOfType("PAUSE", DEFAULT_TIMEOUT);
        assertEquals(400, pauseAtFinish.path("realTime").asInt());
        assertFalse(pauseAtFinish.path("running").asBoolean());

        JsonNode finish = socket.awaitMessageOfType("FINISH", DEFAULT_TIMEOUT);
        assertEquals(400, finish.path("realTime").asInt());
        assertEquals(1200, finish.path("fullTime").asInt());
        socket.assertNoMessageOfType("ERROR", Duration.ofMillis(350));
        socket.assertNoMessageOfType("PLAY", Duration.ofMillis(350));
    }

    @Test
    void shouldHandleOneXForFiveSecondsThenPauseSwitchToFiveXAndRunToTwentySeconds() throws Exception {
        String sessionId = newSessionId();
        TestWebSocketClient socket = connect(sessionId);

        initializeAndStart(socket, DB_NAME, 0, sessionId);

        JsonNode playAtFive = waitUntilRealTimeAtLeast(socket, 5, Duration.ofSeconds(3));
        assertEquals(5, playAtFive.path("realTime").asInt());
        assertBusinessTime(playAtFive, 5);
        assertEquals(1, playAtFive.path("speed").asInt());
        assertEquals(0, playAtFive.path("fullTime").asInt());

        call("/plan/startOrStop?dbName=" + DB_NAME + "&flag=0&sessionId=" + sessionId);
        JsonNode pauseAtFive = socket.awaitMessageOfType("PAUSE", DEFAULT_TIMEOUT);
        assertEquals(5, pauseAtFive.path("realTime").asInt());
        assertFalse(pauseAtFive.path("running").asBoolean());
        socket.assertNoMessageOfType("PLAY", Duration.ofMillis(350));

        call("/plan/speed?dbName=" + DB_NAME + "&speed=5&sessionId=" + sessionId);
        JsonNode speedFive = socket.awaitMessageOfType("SPEED", DEFAULT_TIMEOUT);
        assertEquals(5, speedFive.path("speed").asInt());
        assertEquals(5, speedFive.path("realTime").asInt());
        assertFalse(speedFive.path("running").asBoolean());
        JsonNode startAtFive = socket.awaitMessageOfType("START", DEFAULT_TIMEOUT);
        assertEquals(5, startAtFive.path("realTime").asInt());
        assertEquals(5, startAtFive.path("speed").asInt());
        assertTrue(startAtFive.path("running").asBoolean());

        JsonNode playAtTen = socket.awaitMessageOfType("PLAY", DEFAULT_TIMEOUT);
        assertEquals(6, playAtTen.path("realTime").asInt());
        assertBusinessTime(playAtTen, 10);
        assertEquals(10, playAtTen.path("fullTime").asInt());
        assertEquals(5, playAtTen.path("speed").asInt());

        JsonNode playAtFifteen = socket.awaitMessageOfType("PLAY", DEFAULT_TIMEOUT);
        assertEquals(7, playAtFifteen.path("realTime").asInt());
        assertBusinessTime(playAtFifteen, 15);
        assertEquals(10, playAtFifteen.path("fullTime").asInt());
        assertEquals(5, playAtFifteen.path("speed").asInt());

        JsonNode playAtTwenty = socket.awaitMessageOfType("PLAY", DEFAULT_TIMEOUT);
        assertEquals(8, playAtTwenty.path("realTime").asInt());
        assertBusinessTime(playAtTwenty, 20);
        assertEquals(20, playAtTwenty.path("fullTime").asInt());
        assertEquals(5, playAtTwenty.path("speed").asInt());

        call("/plan/startOrStop?dbName=" + DB_NAME + "&flag=0&sessionId=" + sessionId);
        JsonNode pauseAtTwenty = socket.awaitMessageOfType("PAUSE", DEFAULT_TIMEOUT);
        assertEquals(8, pauseAtTwenty.path("realTime").asInt());
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
        assertEquals(1, firstPlay.path("realTime").asInt());

        call("/plan/speed?dbName=" + DB_NAME + "&speed=3&sessionId=" + sessionId);
        JsonNode speed = socket.awaitMessageOfType("SPEED", DEFAULT_TIMEOUT);
        assertEquals(3, speed.path("speed").asInt());

        JsonNode acceleratedPlay = socket.awaitMessageOfType("PLAY", DEFAULT_TIMEOUT);
        assertEquals(2, acceleratedPlay.path("realTime").asInt());
        assertBusinessTime(acceleratedPlay, 4);

        call("/plan/skip?dbName=" + DB_NAME + "&skip=13&sessionId=" + sessionId);
        JsonNode skip = socket.awaitMessageOfType("SKIP", DEFAULT_TIMEOUT);
        assertEquals(13, skip.path("realTime").asInt());
        assertEquals(10, skip.path("fullTime").asInt());

        JsonNode nextPlay = socket.awaitMessageOfType("PLAY", DEFAULT_TIMEOUT);
        assertEquals(14, nextPlay.path("realTime").asInt());
        assertBusinessTime(nextPlay, 16);

        call("/plan/startOrStop?dbName=" + DB_NAME + "&flag=0&sessionId=" + sessionId);
        JsonNode pause = socket.awaitMessageOfType("PAUSE", DEFAULT_TIMEOUT);
        assertEquals(14, pause.path("realTime").asInt());
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
        assertEquals(33, skip.path("realTime").asInt());
        assertEquals(20, skip.path("fullTime").asInt());
    }

    @Test
    void shouldPauseWhenSpeedIsZero() throws Exception {
        String sessionId = newSessionId();
        TestWebSocketClient socket = connect(sessionId);

        initializeAndStart(socket, DB_NAME, 0, sessionId);
        JsonNode firstPlay = socket.awaitMessageOfType("PLAY", DEFAULT_TIMEOUT);
        int realTime = firstPlay.path("realTime").asInt();

        call("/plan/speed?dbName=" + DB_NAME + "&speed=0&sessionId=" + sessionId);
        JsonNode speed = socket.awaitMessageOfType("SPEED", DEFAULT_TIMEOUT);
        assertEquals(0, speed.path("speed").asInt());
        assertFalse(speed.path("running").asBoolean());
        assertEquals(realTime, speed.path("realTime").asInt());
        socket.assertNoMessageOfType("PLAY", Duration.ofMillis(350));
    }

    @Test
    void shouldSendFinishWhenPlaybackReachesEnd() throws Exception {
        String sessionId = newSessionId();
        TestWebSocketClient socket = connect(sessionId);

        JsonNode init = initializeAndStart(socket, DB_NAME, 1198, sessionId);
        assertEquals(1198, init.path("realTime").asInt());

        call("/plan/speed?dbName=" + DB_NAME + "&speed=3&sessionId=" + sessionId);
        JsonNode speed = socket.awaitMessageOfType("SPEED", DEFAULT_TIMEOUT);
        assertEquals(3, speed.path("speed").asInt());

        JsonNode lastPlay = socket.awaitMessageOfType("PLAY", DEFAULT_TIMEOUT);
        assertEquals(1199, lastPlay.path("realTime").asInt());
        assertBusinessTime(lastPlay, 1200);

        JsonNode pause = socket.awaitMessageOfType("PAUSE", DEFAULT_TIMEOUT);
        assertEquals(1199, pause.path("realTime").asInt());

        JsonNode finish = socket.awaitMessageOfType("FINISH", DEFAULT_TIMEOUT);
        assertEquals(1199, finish.path("realTime").asInt());
        assertEquals(1200, finish.path("fullTime").asInt());
        socket.assertNoMessageOfType("PLAY", Duration.ofMillis(350));
    }

    @Test
    void shouldPauseResumeAndStillReachFinish() throws Exception {
        String sessionId = newSessionId();
        TestWebSocketClient socket = connect(sessionId);

        initializeAndStart(socket, DB_NAME, 1197, sessionId);

        JsonNode firstPlay = socket.awaitMessageOfType("PLAY", DEFAULT_TIMEOUT);
        assertEquals(1198, firstPlay.path("realTime").asInt());
        assertBusinessTime(firstPlay, 1198);

        call("/plan/startOrStop?dbName=" + DB_NAME + "&flag=0&sessionId=" + sessionId);
        JsonNode pause = socket.awaitMessageOfType("PAUSE", DEFAULT_TIMEOUT);
        assertEquals(1198, pause.path("realTime").asInt());
        assertFalse(pause.path("running").asBoolean());

        call("/plan/startOrStop?dbName=" + DB_NAME + "&flag=1&sessionId=" + sessionId);
        JsonNode start = socket.awaitMessageOfType("START", DEFAULT_TIMEOUT);
        assertEquals(1198, start.path("realTime").asInt());
        assertTrue(start.path("running").asBoolean());

        JsonNode resumedPlay = socket.awaitMessageOfType("PLAY", DEFAULT_TIMEOUT);
        assertEquals(1199, resumedPlay.path("realTime").asInt());
        assertBusinessTime(resumedPlay, 1199);

        JsonNode finalPlay = socket.awaitMessageOfType("PLAY", DEFAULT_TIMEOUT);
        assertEquals(1200, finalPlay.path("realTime").asInt());
        assertBusinessTime(finalPlay, 1200);

        JsonNode pauseAtFinish = socket.awaitMessageOfType("PAUSE", DEFAULT_TIMEOUT);
        assertEquals(1200, pauseAtFinish.path("realTime").asInt());
        JsonNode finish = socket.awaitMessageOfType("FINISH", DEFAULT_TIMEOUT);
        assertEquals(1200, finish.path("realTime").asInt());
        socket.assertNoMessageOfType("ERROR", Duration.ofMillis(350));
    }

    @Test
    void shouldKeepRunningWhenSpeedChangesToFiveXUntilFinish() throws Exception {
        String sessionId = newSessionId();
        TestWebSocketClient socket = connect(sessionId);

        initializeAndStart(socket, DB_NAME, 1194, sessionId);

        JsonNode firstPlay = socket.awaitMessageOfType("PLAY", DEFAULT_TIMEOUT);
        assertEquals(1195, firstPlay.path("realTime").asInt());
        assertBusinessTime(firstPlay, 1195);

        call("/plan/speed?dbName=" + DB_NAME + "&speed=5&sessionId=" + sessionId);
        JsonNode speed = socket.awaitMessageOfType("SPEED", DEFAULT_TIMEOUT);
        assertEquals(5, speed.path("speed").asInt());
        assertTrue(speed.path("running").asBoolean());

        JsonNode nextMessage = socket.awaitNextMessage(DEFAULT_TIMEOUT);
        assertEquals("PLAY", nextMessage.path("type").asText());
        assertEquals(1196, nextMessage.path("realTime").asInt());
        assertBusinessTime(nextMessage, 1200);
        assertEquals(5, nextMessage.path("speed").asInt());

        JsonNode pauseAtFinish = socket.awaitNextMessage(DEFAULT_TIMEOUT);
        assertEquals("PAUSE", pauseAtFinish.path("type").asText());
        JsonNode finish = socket.awaitNextMessage(DEFAULT_TIMEOUT);
        assertEquals("FINISH", finish.path("type").asText());
        socket.assertNoMessageOfType("ERROR", Duration.ofMillis(350));
    }

    @Test
    void shouldAutoPlayAfterSettingFiveXThenAllowExplicitPlayWithoutPause() throws Exception {
        String sessionId = newSessionId();
        TestWebSocketClient socket = connect(sessionId);

        initializeOnly(socket, DB_NAME, 1194, sessionId);

        call("/plan/speed?dbName=" + DB_NAME + "&speed=5&sessionId=" + sessionId);
        JsonNode speed = socket.awaitMessageOfType("SPEED", DEFAULT_TIMEOUT);
        assertEquals(5, speed.path("speed").asInt());
        assertFalse(speed.path("running").asBoolean());

        JsonNode init = socket.awaitMessageOfType("INIT", DEFAULT_TIMEOUT);
        assertEquals(1194, init.path("realTime").asInt());
        assertEquals(5, init.path("speed").asInt());
        assertTrue(init.path("running").asBoolean());

        call("/plan/startOrStop?dbName=" + DB_NAME + "&flag=1&sessionId=" + sessionId);
        JsonNode start = socket.awaitMessageOfType("START", DEFAULT_TIMEOUT);
        assertEquals(1194, start.path("realTime").asInt());
        assertEquals(5, start.path("speed").asInt());
        assertTrue(start.path("running").asBoolean());

        JsonNode play = socket.awaitMessageOfType("PLAY", DEFAULT_TIMEOUT);
        assertEquals(1195, play.path("realTime").asInt());
        assertBusinessTime(play, 1199);

        JsonNode finalPlay = socket.awaitMessageOfType("PLAY", DEFAULT_TIMEOUT);
        assertEquals(1196, finalPlay.path("realTime").asInt());
        assertBusinessTime(finalPlay, 1200);

        JsonNode pauseAtFinish = socket.awaitMessageOfType("PAUSE", DEFAULT_TIMEOUT);
        assertEquals(1196, pauseAtFinish.path("realTime").asInt());
        JsonNode finish = socket.awaitMessageOfType("FINISH", DEFAULT_TIMEOUT);
        assertEquals(1196, finish.path("realTime").asInt());
        socket.assertNoMessageOfType("ERROR", Duration.ofMillis(350));
    }

    @Test
    void shouldPauseResumeAfterFiveXAndStillFinishWithoutError() throws Exception {
        String sessionId = newSessionId();
        TestWebSocketClient socket = connect(sessionId);

        initializeOnly(socket, DB_NAME, 1193, sessionId);

        call("/plan/speed?dbName=" + DB_NAME + "&speed=5&sessionId=" + sessionId);
        socket.awaitMessageOfType("SPEED", DEFAULT_TIMEOUT);
        socket.awaitMessageOfType("INIT", DEFAULT_TIMEOUT);

        JsonNode playAtFiveX = socket.awaitMessageOfType("PLAY", DEFAULT_TIMEOUT);
        assertEquals(1194, playAtFiveX.path("realTime").asInt());
        assertBusinessTime(playAtFiveX, 1198);

        call("/plan/startOrStop?dbName=" + DB_NAME + "&flag=0&sessionId=" + sessionId);
        JsonNode pause = socket.awaitMessageOfType("PAUSE", DEFAULT_TIMEOUT);
        assertEquals(1194, pause.path("realTime").asInt());
        assertFalse(pause.path("running").asBoolean());

        call("/plan/startOrStop?dbName=" + DB_NAME + "&flag=1&sessionId=" + sessionId);
        JsonNode start = socket.awaitMessageOfType("START", DEFAULT_TIMEOUT);
        assertEquals(1194, start.path("realTime").asInt());
        assertEquals(5, start.path("speed").asInt());

        JsonNode finalPlay = socket.awaitMessageOfType("PLAY", DEFAULT_TIMEOUT);
        assertEquals(1195, finalPlay.path("realTime").asInt());
        assertBusinessTime(finalPlay, 1200);

        JsonNode pauseAtFinish = socket.awaitMessageOfType("PAUSE", DEFAULT_TIMEOUT);
        assertEquals(1195, pauseAtFinish.path("realTime").asInt());
        JsonNode finish = socket.awaitMessageOfType("FINISH", DEFAULT_TIMEOUT);
        assertEquals(1195, finish.path("realTime").asInt());
        socket.assertNoMessageOfType("ERROR", Duration.ofMillis(350));
    }

    @Test
    void shouldReplayFromStartAfterFinishWhenStartingAgain() throws Exception {
        String sessionId = newSessionId();
        TestWebSocketClient socket = connect(sessionId);

        JsonNode init = initializeAndStart(socket, DB_NAME, 1199, sessionId);
        assertEquals(1199, init.path("realTime").asInt());

        JsonNode finalPlay = socket.awaitMessageOfType("PLAY", DEFAULT_TIMEOUT);
        assertEquals(1200, finalPlay.path("realTime").asInt());
        JsonNode pauseAtFinish = socket.awaitMessageOfType("PAUSE", DEFAULT_TIMEOUT);
        assertEquals(1200, pauseAtFinish.path("realTime").asInt());
        JsonNode finish = socket.awaitMessageOfType("FINISH", DEFAULT_TIMEOUT);
        assertEquals(1200, finish.path("realTime").asInt());

        call("/plan/startOrStop?dbName=" + DB_NAME + "&flag=1&sessionId=" + sessionId);
        JsonNode replayInit = socket.awaitMessageOfType("INIT", DEFAULT_TIMEOUT);
        assertEquals(0, replayInit.path("realTime").asInt());
        assertEquals(0, replayInit.path("deduceTime").asInt());
        assertEquals(0, replayInit.path("fullTime").asInt());
        assertTrue(replayInit.path("running").asBoolean());
        assertCompatibilityArraysEmpty(replayInit);

        JsonNode replayPlay = socket.awaitMessageOfType("PLAY", DEFAULT_TIMEOUT);
        assertEquals(1, replayPlay.path("realTime").asInt());
        assertBusinessTime(replayPlay, 1);
        assertCompatibilityArraysEmpty(replayPlay);
    }

    @Test
    void shouldKeepCompatibilityArraysEmptyWhileUsingMergedDataAndEventData() throws Exception {
        String sessionId = newSessionId();
        TestWebSocketClient socket = connect(sessionId);

        JsonNode init = initializeAndStart(socket, DB_NAME, 0, sessionId);
        assertCompatibilityArraysEmpty(init);
        assertTrue(init.path("data").isArray());
        assertTrue(init.path("eventData").isArray());

        JsonNode firstPlay = socket.awaitMessageOfType("PLAY", DEFAULT_TIMEOUT);
        assertCompatibilityArraysEmpty(firstPlay);
        assertSimtimes(firstPlay, firstPlay.path("data"), repeatSimtime(1));
        assertEventTimes(firstPlay, firstPlay.path("eventData"), 1);

        call("/plan/skip?dbName=" + DB_NAME + "&skip=13&sessionId=" + sessionId);
        JsonNode skip = socket.awaitMessageOfType("SKIP", DEFAULT_TIMEOUT);
        assertCompatibilityArraysEmpty(skip);
        assertSimtimes(skip, skip.path("data"), repeatSimtime(13));
        assertEventTimes(skip, skip.path("eventData"), range(0, 13));
    }

    @Test
    void shouldUseFullSnapshotOnlyAtIntervalPointAndIncrementOnlyOffInterval() throws Exception {
        String sessionId = newSessionId();
        TestWebSocketClient socket = connect(sessionId);

        initializeAndStart(socket, DB_NAME, 9, sessionId);

        JsonNode intervalPlay = socket.awaitMessageOfType("PLAY", DEFAULT_TIMEOUT);
        assertEquals(10, intervalPlay.path("realTime").asInt());
        assertEquals(10, intervalPlay.path("fullTime").asInt());
        assertCompatibilityArraysEmpty(intervalPlay);
        assertSimtimes(intervalPlay, intervalPlay.path("data"), repeatSimtime(10));
        assertEventTimes(intervalPlay, intervalPlay.path("eventData"), range(0, 10));

        JsonNode offIntervalPlay = socket.awaitMessageOfType("PLAY", DEFAULT_TIMEOUT);
        assertEquals(11, offIntervalPlay.path("realTime").asInt());
        assertEquals(10, offIntervalPlay.path("fullTime").asInt());
        assertCompatibilityArraysEmpty(offIntervalPlay);
        assertSimtimes(offIntervalPlay, offIntervalPlay.path("data"), repeatSimtime(11));
        assertEventTimes(offIntervalPlay, offIntervalPlay.path("eventData"), 11);
    }

    @Test
    void shouldAutoResumeAfterPauseThenSkipAndStillFinish() throws Exception {
        String sessionId = newSessionId();
        TestWebSocketClient socket = connect(sessionId);

        initializeAndStart(socket, DB_NAME, 1190, sessionId);

        JsonNode firstPlay = socket.awaitMessageOfType("PLAY", DEFAULT_TIMEOUT);
        assertEquals(1191, firstPlay.path("realTime").asInt());

        call("/plan/startOrStop?dbName=" + DB_NAME + "&flag=0&sessionId=" + sessionId);
        JsonNode pause = socket.awaitMessageOfType("PAUSE", DEFAULT_TIMEOUT);
        assertEquals(1191, pause.path("realTime").asInt());

        call("/plan/skip?dbName=" + DB_NAME + "&skip=1199&sessionId=" + sessionId);
        JsonNode start = socket.awaitMessageOfType("START", DEFAULT_TIMEOUT);
        assertEquals(1191, start.path("realTime").asInt());
        assertTrue(start.path("running").asBoolean());

        JsonNode skip = socket.awaitMessageOfType("SKIP", DEFAULT_TIMEOUT);
        assertEquals(1199, skip.path("realTime").asInt());
        assertEquals(1199, skip.path("deduceTime").asInt());

        JsonNode finalPlay = socket.awaitMessageOfType("PLAY", DEFAULT_TIMEOUT);
        assertEquals(1200, finalPlay.path("realTime").asInt());
        assertBusinessTime(finalPlay, 1200);

        JsonNode pauseAtFinish = socket.awaitMessageOfType("PAUSE", DEFAULT_TIMEOUT);
        assertEquals(1200, pauseAtFinish.path("realTime").asInt());
        JsonNode finish = socket.awaitMessageOfType("FINISH", DEFAULT_TIMEOUT);
        assertEquals(1200, finish.path("realTime").asInt());
        socket.assertNoMessageOfType("ERROR", Duration.ofMillis(350));
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
        assertEquals(0, init1.path("realTime").asInt());
        assertEquals(10, init2.path("realTime").asInt());

        call("/plan/speed?dbName=" + DB_NAME + "&speed=3&sessionId=" + sessionId2);
        JsonNode speed2 = socket2.awaitMessageOfType("SPEED", DEFAULT_TIMEOUT);
        assertEquals(3, speed2.path("speed").asInt());

        JsonNode play1 = socket1.awaitMessageOfType("PLAY", DEFAULT_TIMEOUT);
        JsonNode play2 = socket2.awaitMessageOfType("PLAY", DEFAULT_TIMEOUT);
        assertEquals(sessionId1, play1.path("sessionId").asText());
        assertEquals(sessionId2, play2.path("sessionId").asText());
        assertEquals(1, play1.path("realTime").asInt());
        assertEquals(11, play2.path("realTime").asInt());
        assertBusinessTime(play2, 13);

        call("/plan/startOrStop?dbName=" + DB_NAME + "&flag=0&sessionId=" + sessionId1);
        JsonNode pause1 = socket1.awaitMessageOfType("PAUSE", DEFAULT_TIMEOUT);
        assertEquals(sessionId1, pause1.path("sessionId").asText());
        socket1.assertNoMessageOfType("PLAY", Duration.ofMillis(350));

        JsonNode nextPlay2 = socket2.awaitMessageOfType("PLAY", DEFAULT_TIMEOUT);
        assertEquals(sessionId2, nextPlay2.path("sessionId").asText());
        assertTrue(extractBusinessTime(nextPlay2) >= 16);
    }

    @Test
    void shouldReleaseResourcesAfterDestroy() throws Exception {
        String sessionId = newSessionId();
        TestWebSocketClient socket = connect(sessionId);

        initializeAndStart(socket, DB_NAME, 0, sessionId);
        JsonNode firstPlay = socket.awaitMessageOfType("PLAY", DEFAULT_TIMEOUT);
        assertEquals(1, firstPlay.path("realTime").asInt());

        call("/plan/destroy?dbName=" + DB_NAME + "&sessionId=" + sessionId);
        JsonNode destroy = socket.awaitMessageOfType("DESTROY", DEFAULT_TIMEOUT);
        assertEquals(1, destroy.path("realTime").asInt());
        socket.assertNoMessageOfType("PLAY", Duration.ofMillis(350));

        JsonNode restartedInit = initializeAndStart(socket, DB_NAME, 0, sessionId);
        assertEquals(0, restartedInit.path("realTime").asInt());
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
        assertEquals(13, skip13.path("realTime").asInt());

        call("/plan/speed?dbName=" + DB_NAME + "&speed=4&sessionId=" + sessionId);
        JsonNode speed4 = socket.awaitMessageOfType("SPEED", DEFAULT_TIMEOUT);
        assertEquals(4, speed4.path("speed").asInt());

        JsonNode play17 = socket.awaitMessageOfType("PLAY", DEFAULT_TIMEOUT);
        assertEquals(14, play17.path("realTime").asInt());
        assertBusinessTime(play17, 17);

        call("/plan/speed?dbName=" + DB_NAME + "&speed=0&sessionId=" + sessionId);
        JsonNode speed0 = socket.awaitMessageOfType("SPEED", DEFAULT_TIMEOUT);
        assertEquals(0, speed0.path("speed").asInt());
        socket.assertNoMessageOfType("PLAY", Duration.ofMillis(350));

        call("/plan/startOrStop?dbName=" + DB_NAME + "&flag=1&sessionId=" + sessionId);
        JsonNode start = socket.awaitMessageOfType("START", DEFAULT_TIMEOUT);
        assertEquals(1, start.path("speed").asInt());

        JsonNode play18 = socket.awaitMessageOfType("PLAY", DEFAULT_TIMEOUT);
        assertEquals(15, play18.path("realTime").asInt());
        assertBusinessTime(play18, 18);

        call("/plan/skip?dbName=" + DB_NAME + "&skip=22&sessionId=" + sessionId);
        JsonNode skip22 = socket.awaitMessageOfType("SKIP", DEFAULT_TIMEOUT);
        assertEquals(22, skip22.path("realTime").asInt());
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
        assertEquals(0, skip.path("realTime").asInt());
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
        assertEquals("1200", body.path("endTime").asText());
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

    private void assertSimtimes(JsonNode messageNode, JsonNode dataNode, int... expected) {
        assertNotNull(dataNode);
        assertEquals(expected.length, dataNode.size());
        int expectedRealTime = messageNode.path("realTime").asInt();
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], dataNode.get(i).path("simTime").asInt(), "simTime mismatch at index " + i);
            assertEquals(expectedRealTime, dataNode.get(i).path("realTime").asInt(), "realTime mismatch at index " + i);
        }
    }

    private void assertEventTimes(JsonNode messageNode, JsonNode dataNode, int... expected) {
        assertNotNull(dataNode);
        assertEquals(expected.length, dataNode.size());
        int expectedRealTime = messageNode.path("realTime").asInt();
        for (int i = 0; i < expected.length; i++) {
            int expectedMillisecond = expected[i] * 1000;
            assertEquals(expectedMillisecond, dataNode.get(i).path("simTime").asInt(), "simTime mismatch at index " + i);
            assertEquals(expectedMillisecond, dataNode.get(i).path("physicalTime").asInt(), "physicalTime mismatch at index " + i);
            assertEquals(expectedRealTime, dataNode.get(i).path("realTime").asInt(), "realTime mismatch at index " + i);
            assertEquals(DB_NAME, dataNode.get(i).path("roomId").asText(), "roomId mismatch at index " + i);
        }
    }

    private void assertRoomObjectFieldsPresent(JsonNode dataNode) {
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
        assertTrue(first.hasNonNull("realTime"));
        assertTrue(first.hasNonNull("targetId"));
    }

    private void assertCompatibilityArraysEmpty(JsonNode message) {
        assertTrue(message.path("fullData").isArray());
        assertTrue(message.path("incrementalData").isArray());
        assertEquals(0, message.path("fullData").size());
        assertEquals(0, message.path("incrementalData").size());
    }

    private void assertBusinessTime(JsonNode message, int expected) {
        assertEquals(expected, extractBusinessTime(message));
    }

    private int extractBusinessTime(JsonNode message) {
        int businessTime = maxSimTime(message.path("data"));
        businessTime = Math.max(businessTime, maxSimTime(message.path("eventData")));
        businessTime = Math.max(businessTime, message.path("fullTime").asInt(0));
        return businessTime;
    }

    private int maxSimTime(JsonNode dataNode) {
        if (dataNode == null || !dataNode.isArray() || dataNode.isEmpty()) {
            return -1;
        }
        int max = -1;
        for (JsonNode node : dataNode) {
            int rawSimTime = node.path("simTime").asInt(-1);
            max = Math.max(max, rawSimTime < 0 ? rawSimTime : rawSimTime / 1000);
        }
        return max;
    }

    private int[] repeatSimtime(int simtime) {
        int[] values = new int[PIECES_PER_SECOND];
        for (int i = 0; i < PIECES_PER_SECOND; i++) {
            values[i] = simtime * 1000;
        }
        return values;
    }

    private int[] rangeRepeated(int startInclusive, int endInclusive) {
        int[] values = new int[(endInclusive - startInclusive + 1) * PIECES_PER_SECOND];
        int index = 0;
        for (int simtime = startInclusive; simtime <= endInclusive; simtime++) {
            for (int i = 0; i < PIECES_PER_SECOND; i++) {
                values[index++] = simtime * 1000;
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

    private JsonNode waitUntilRealTimeAtLeast(TestWebSocketClient socket, int expectedRealTime, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        JsonNode lastPlay = null;
        while (System.nanoTime() < deadline) {
            JsonNode play = socket.awaitMessageOfType("PLAY", Duration.ofSeconds(1));
            lastPlay = play;
            if (play.path("realTime").asInt() >= expectedRealTime) {
                return play;
            }
        }
        fail("Timed out waiting realTime to reach at least " + expectedRealTime + ", lastPlay=" + lastPlay);
        return null;
    }

    private JsonNode waitUntilBusinessTimeAtLeast(TestWebSocketClient socket, int expectedBusinessTime, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        JsonNode lastPlay = null;
        while (System.nanoTime() < deadline) {
            JsonNode play = socket.awaitMessageOfType("PLAY", Duration.ofSeconds(1));
            lastPlay = play;
            if (extractBusinessTime(play) >= expectedBusinessTime) {
                return play;
            }
        }
        fail("Timed out waiting business time to reach at least " + expectedBusinessTime + ", lastPlay=" + lastPlay);
        return null;
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

        private JsonNode awaitNextMessage(Duration timeout) throws InterruptedException {
            JsonNode message = messages.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
            assertNotNull(message, "Timed out waiting for next websocket message");
            return message;
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
