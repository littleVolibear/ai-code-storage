# 进度条后端业务自测与调试文档

这份文档面向后端开发人员，只描述当前代码真实行为。

## 1. 自测准备

启动应用：

```bash
mvn spring-boot:run
```

建议准备两个终端：

1. WebSocket 监听
2. HTTP 控制

WebSocket：

```bash
wscat -c ws://localhost:8080/ws/planDeduce?sessionId=s1
```

HTTP：

```bash
curl "http://localhost:8080/plan/..."
```

## 2. 先明确当前协议事实

### 2.1 任务唯一维度

当前任务只按 `sessionId` 隔离。

`dbName` 当前表示 `ROOM_INFO.id`：

- 会参与查询参数和回包
- 但不会作为 `ScenarioTaskManager` 的 key

### 2.2 时间字段

当前对外时间字段是：

- `realTime`
- `deduceTime`
- `fullTime`

不是旧文档中的 `currentTime`。

### 2.3 快照字段

当前对外主消费字段是：

- `data`
- `eventData`

虽然内部仍区分全量和增量查询，但 WebSocket 对外目前固定：

- `fullData = []`
- `incrementalData = []`

### 2.4 接口自动恢复行为

当前代码里有两个很关键的自动恢复动作：

1. `/plan/speed`
   - 如果 `speed > 0` 且当前 `running=false`
   - 会在发完 `SPEED` 后自动 `resume()`
2. `/plan/skip`
   - 如果当前 `running=false`
   - 会在登记跳点后自动 `resume()`

## 3. 常见自测场景

### 场景一：初始化后开始播放

```bash
curl "http://localhost:8080/plan/sendPlanDeduce?dbName=1&skip=0&sessionId=s1"
curl "http://localhost:8080/plan/startOrStop?dbName=1&flag=1&sessionId=s1"
```

期望：

- `sendPlanDeduce` 返回 `startTime` / `endTime`
- WS 首条是 `INIT`
- 后续出现 `PLAY`

### 场景二：暂停

```bash
curl "http://localhost:8080/plan/startOrStop?dbName=1&flag=0&sessionId=s1"
```

期望：

- 收到 `PAUSE`
- `running=false`
- 后续不再出现新的 `PLAY`

### 场景三：暂停后恢复

```bash
curl "http://localhost:8080/plan/startOrStop?dbName=1&flag=1&sessionId=s1"
```

期望：

- 收到 `START`
- 后续重新出现 `PLAY`

### 场景四：播放中切换倍速

```bash
curl "http://localhost:8080/plan/speed?dbName=1&speed=3&sessionId=s1"
```

期望：

- 收到 `SPEED`
- 如果原本在播放，后续 `PLAY.realTime` 仍每帧加 `1`
- `PLAY.deduceTime` 按 `+3` 推进

### 场景五：暂停后调成 3 倍速

```bash
curl "http://localhost:8080/plan/speed?dbName=1&speed=3&sessionId=s1"
```

期望：

- 先收到 `SPEED`
- 再收到 `START`
- 后续重新出现 `PLAY`

### 场景六：使用 `speed=0` 作为暂停

```bash
curl "http://localhost:8080/plan/speed?dbName=1&speed=0&sessionId=s1"
```

期望：

- 收到 `SPEED`
- `speed=0`
- `running=false`

### 场景七：跳转到指定秒点

```bash
curl "http://localhost:8080/plan/skip?dbName=1&skip=13&sessionId=s1"
```

期望：

- 收到 `SKIP`
- `realTime=13`
- `deduceTime=13`
- `fullTime=10`

说明：

- 具体棋子主数据看 `data`
- 事件数据看 `eventData`
- 不要再按 `fullData` / `incrementalData` 验证对外报文

### 场景八：修改全量间隔

```bash
curl "http://localhost:8080/plan/fullSaveInterval?dbName=1&fullSaveIntervalSeconds=20&sessionId=s1"
curl "http://localhost:8080/plan/skip?dbName=1&skip=33&sessionId=s1"
```

期望：

- 第一条收到 `INTERVAL`
- 第二条收到 `SKIP`
- `SKIP.fullTime=20`

### 场景九：播放结束

```bash
curl "http://localhost:8080/plan/sendPlanDeduce?dbName=1&skip=1198&sessionId=s1"
curl "http://localhost:8080/plan/startOrStop?dbName=1&flag=1&sessionId=s1"
curl "http://localhost:8080/plan/speed?dbName=1&speed=3&sessionId=s1"
```

期望：

- 最后一条 `PLAY` 是 `realTime=1199, deduceTime=1200`
- 随后 `PAUSE`
- 再随后 `FINISH`

## 4. 排查重点

如果前端说“暂停后改速怎么自动继续了”，先看代码现状：

- `ScenarioTask.setSpeedAndResume()`

如果前端说“暂停状态 skip 怎么又开始跑了”，先看代码现状：

- `ScenarioTask.skipAndResume()`

如果发现文档里还有这些旧说法，说明文档落后于代码：

1. “暂停后调速不会自动恢复”
2. “暂停后 skip 只刷新数据，不会自动恢复”
3. “任务按 dbName + sessionId 隔离”
4. “协议时间字段是 currentTime”
