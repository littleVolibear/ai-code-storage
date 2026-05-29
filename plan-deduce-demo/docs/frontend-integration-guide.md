# 前端接入说明

这份文档只描述当前代码真实提供的接口和 WebSocket 协议。

## 1. 交互方式

当前系统采用：

- HTTP：发送控制命令
- WebSocket：接收状态和数据

前端必须记住：

- UI 状态以 WebSocket 为准
- HTTP 200 只表示命令提交成功

### 1.1 WebSocket 连接

```text
ws://{host}:{port}/ws/planDeduce?sessionId={sessionId}
```

示例：

```text
ws://localhost:8080/ws/planDeduce?sessionId=s1
```

### 1.2 HTTP 控制接口

- `/plan/sendPlanDeduce`
- `/plan/skip`
- `/plan/speed`
- `/plan/startOrStop`
- `/plan/fullSaveInterval`
- `/plan/destroy`

## 2. 公共参数

### 2.1 `sessionId`

这是当前任务真正的唯一标识。

要求：

- 同一个页面实例内固定
- 不同页面不要复用

### 2.2 `dbName`

表示 `ROOM_INFO` 主键 ID，会原样带回 WebSocket 消息。

需要注意：

- 当前任务不是按 `dbName + sessionId` 隔离
- 而是只按 `sessionId` 隔离

### 2.3 时间字段

当前协议里不要再把时间理解成单字段 `currentTime`。

现在有三类时间：

- `realTime`：真实 tick 时间
- `deduceTime`：推演业务时间
- `fullTime`：最近全量快照秒点

前端通常应该：

- 进度条主时间显示用 `deduceTime`
- 辅助调试或“真实播放节奏”才看 `realTime`

## 3. 接口说明

### 3.1 初始化任务

```text
GET /plan/sendPlanDeduce
```

示例：

```text
/plan/sendPlanDeduce?dbName=1&skip=0&sessionId=s1
```

真实行为：

- 只初始化任务
- 不推 WebSocket
- 返回进度条范围

HTTP 返回体示例：

```json
{
  "dbName": "1",
  "sessionId": "s1",
  "startTime": "2026-01-01 00:00:00",
  "endTime": 1200
}
```

### 3.2 开始 / 暂停

```text
GET /plan/startOrStop
```

示例：

```text
/plan/startOrStop?dbName=1&flag=1&sessionId=s1
/plan/startOrStop?dbName=1&flag=0&sessionId=s1
```

真实行为：

- `flag=0`：推 `PAUSE`
- `flag=1` 首次开始：推 `INIT`
- `flag=1` 暂停后恢复：推 `START`

### 3.3 修改倍速

```text
GET /plan/speed
```

示例：

```text
/plan/speed?dbName=1&speed=3&sessionId=s1
```

真实行为：

- 一定先推 `SPEED`
- 如果 `speed=0`，进入暂停态
- 如果 `speed>0` 且当前 `running=false`，当前代码会自动恢复

因此要按下面几种情况理解：

1. 原本正在播放：
   - `SPEED`
   - 后续 `PLAY` 按新倍速推进
2. 原本已暂停：
   - `SPEED`
   - `START`
   - 后续 `PLAY`
3. 原本还没开始：
   - `SPEED`
   - `INIT`
   - 后续 `PLAY`

### 3.4 跳转到某个秒点

```text
GET /plan/skip
```

示例：

```text
/plan/skip?dbName=1&skip=33&sessionId=s1
```

真实行为：

- 会推 `SKIP`
- 会把 `deduceTime` 和 `realTime` 一起设到目标秒
- 如果当前任务没在运行，会自动恢复

常见顺序：

1. 正在播放：`SKIP -> PLAY`
2. 已暂停：`START -> SKIP -> PLAY`
3. 从未开始：`INIT -> SKIP -> PLAY`

### 3.5 修改全量快照间隔

```text
GET /plan/fullSaveInterval
```

示例：

```text
/plan/fullSaveInterval?dbName=1&fullSaveIntervalSeconds=20&sessionId=s1
```

真实行为：

- 立即推一次 `INTERVAL`
- 后续所有快照的 `fullTime` 计算规则都会按新间隔生效
- 但 `INTERVAL` 自身以及后续 `PLAY` 仍只发送增量数据，不会因为命中整间隔点切到全量查询

### 3.6 销毁任务

```text
GET /plan/destroy
```

示例：

```text
/plan/destroy?dbName=1&sessionId=s1
```

真实行为：

- 推 `DESTROY`
- 停止 worker
- 后续如需继续，必须重新初始化

## 4. WebSocket 消息结构

当前 `PushMessage` 的关键字段如下：

```ts
type PushMessage = {
  type: string
  dbName: string
  sessionId: string
  realTime: number
  deduceTime: number
  fullTime: number | null
  speed: number
  running: boolean
  fullData: any[]
  incrementalData: any[]
  data: any[]
  eventData: any[]
  message: string
  maxSimTime: number
}
```

需要特别注意：

1. 当前主消费字段是 `data`、`eventData`、`indrectFirePlanData`、`commandInfoData`。
2. `fullData` / `incrementalData` 以及对应的 `*FullData` / `*IncrementalData` 现在对外固定为空数组。
3. 只有 `SKIP` 会在后端内部使用“最近全量点 + 区间增量”拼装当前状态。
4. `INIT`、`INTERVAL`、`PLAY` 对前端都只是“当前时间段的数据”，不要按全量点做特殊分支。
5. `message` 只是辅助说明，不要拿它做程序分支。

## 5. 前端消费建议

### 5.1 UI 时间

- 主进度条：用 `deduceTime`
- 辅助节奏显示：可用 `realTime`

### 5.2 状态切换

- `INIT`：首次开始，建立基准状态
- `PLAY`：刷新画面和进度条
- `PAUSE`：切到暂停态
- `START`：从暂停态恢复
- `SPEED`：刷新倍速显示
- `SKIP`：跳点后刷新当前状态
- `INTERVAL`：全量间隔改变，重新刷新当前快照
- `FINISH`：播放结束
- `DESTROY`：任务销毁
- `ERROR`：显示错误

### 5.3 不要这样做

1. 不要自己本地推进播放秒数。
2. 不要假设“暂停后调速不会自动恢复”。
3. 不要假设“skip 在暂停时只刷新数据不恢复播放”。
4. 不要按 `dbName + sessionId` 维护多个任务，当前应按 `sessionId` 管理。
