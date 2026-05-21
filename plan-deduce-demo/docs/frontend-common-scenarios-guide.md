# 前端常见场景调用说明

这份文档只描述当前代码下前端最常见的调用方式和真实行为。

当前系统模型：

- HTTP：发控制命令
- WebSocket：收状态和数据

前端需要遵守两条原则：

1. 发命令靠 HTTP
2. 判断结果和切换 UI 靠 WebSocket

## 1. 关键协议事实

先明确 4 个最容易误解的点：

1. `sendPlanDeduce` 只初始化任务并返回时间范围，不会直接推 `INIT`。
2. 当前时间字段已经分成两条轴：
   - `realTime`：真实 tick 时间
   - `deduceTime`：推演业务时间
3. `data` 和 `eventData` 是前端主消费字段。
4. `fullData` / `incrementalData` 当前对外固定为空数组，仅兼容保留。

## 2. 最常见的业务场景

### 场景一：用户第一次进入页面，准备开始播放

推荐顺序：

1. 先建立 WebSocket
2. 调 `sendPlanDeduce`
3. 再调 `startOrStop?flag=1`

WebSocket：

```text
ws://localhost:8080/ws/planDeduce?sessionId=s1
```

HTTP：

```text
/plan/sendPlanDeduce?dbName=plandeduce&skip=0&sessionId=s1
/plan/startOrStop?dbName=plandeduce&flag=1&sessionId=s1
```

前端应该期待：

- `sendPlanDeduce` 的 HTTP 返回体里有 `startTime` 和 `endTime`
- 开始播放后先收到 `INIT`
- 后续持续收到 `PLAY`

### 场景二：用户点击暂停

HTTP：

```text
/plan/startOrStop?dbName=plandeduce&flag=0&sessionId=s1
```

前端应该期待：

- 收到 `PAUSE`

前端注意：

- 不要点按钮后立刻强制改 UI
- 应等 `PAUSE.running=false` 再切到暂停态

### 场景三：用户点击继续播放

HTTP：

```text
/plan/startOrStop?dbName=plandeduce&flag=1&sessionId=s1
```

前端应该期待：

- 如果这是暂停后恢复，先收到 `START`
- 后续继续收到 `PLAY`

前端注意：

- 第一次开始不是 `START`，而是 `INIT`
- 真正的时间推进看后续 `PLAY.deduceTime`

### 场景四：用户在播放中切换倍速

HTTP：

```text
/plan/speed?dbName=plandeduce&speed=3&sessionId=s1
```

前端应该期待：

- 先收到 `SPEED`
- 如果原本就在播放，后续 `PLAY` 按新倍速推进

前端注意：

- 倍速切换不会回补历史秒点
- `realTime` 仍按 tick 推进，`deduceTime` 才体现业务倍速

### 场景五：用户暂停后改成 3 倍速

HTTP：

```text
/plan/speed?dbName=plandeduce&speed=3&sessionId=s1
```

前端应该期待：

- 先收到 `SPEED`
- 因为当前代码会自动恢复，所以还会再收到 `START`
- 然后继续收到 `PLAY`

前端注意：

- 当前代码不是“暂停后调速只改速度不自动恢复”
- 如果 `speed > 0` 且当前不在运行，`/plan/speed` 会自动触发恢复

### 场景六：用户把倍速调成 0，当成暂停

HTTP：

```text
/plan/speed?dbName=plandeduce&speed=0&sessionId=s1
```

前端应该期待：

- 收到 `SPEED`
- `speed=0`
- `running=false`

前端注意：

- 这是暂停态
- 如果之后直接调 `startOrStop(flag=1)`，后端会把速度兜底恢复到 `1`

### 场景七：用户拖动进度条，跳到某个秒点

HTTP：

```text
/plan/skip?dbName=plandeduce&skip=33&sessionId=s1
```

前端应该期待：

- 收到 `SKIP`
- `deduceTime=33`
- 同时拿到新的 `data` 和 `eventData`

前端注意：

- 当前代码的 `/plan/skip` 在任务未运行时也会自动恢复
- 因此消息顺序与当前状态有关：
  - 正在播放：`SKIP -> PLAY`
  - 已暂停：`START -> SKIP -> PLAY`
  - 从未开始：`INIT -> SKIP -> PLAY`

### 场景八：用户修改全量快照间隔

HTTP：

```text
/plan/fullSaveInterval?dbName=plandeduce&fullSaveIntervalSeconds=20&sessionId=s1
```

前端应该期待：

- 收到 `INTERVAL`

前端注意：

- 不要在前端自己写死“每 10 秒一个全量点”
- 必须以后端消息里的 `fullTime` 为准

### 场景九：用户离开页面或关闭当前任务

HTTP：

```text
/plan/destroy?dbName=plandeduce&sessionId=s1
```

前端应该期待：

- 收到 `DESTROY`

前端注意：

- `destroy` 是彻底销毁，不是暂停
- 销毁后如果要继续，必须重新调 `sendPlanDeduce`

## 3. 前端建议维护的状态

```ts
{
  connected: boolean,
  initialized: boolean,
  running: boolean,
  finished: boolean,
  sessionId: string,
  dbName: string,
  realTime: number,
  deduceTime: number,
  fullTime: number,
  speed: number,
  data: any[],
  eventData: any[],
  lastMessageType: string | null,
  errorMessage: string | null
}
```

更新原则：

- 只要收到 WebSocket，就用消息更新本地状态
- 不要只根据按钮点击结果改状态

## 4. 前端要特别注意的边界

1. 时间显示用 `deduceTime`，不要把 `realTime` 当业务时间。
2. 当前任务实际只按 `sessionId` 隔离，不要试图用同一个 `sessionId` 管多个任务。
3. 不要依赖 `dbName` 做任务隔离，当前它只是兼容字段。
4. 不要在前端本地自增时间，应以后端推送为准。
