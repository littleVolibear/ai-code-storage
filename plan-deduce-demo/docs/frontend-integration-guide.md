# 前端接入说明

这份文档给前端同学直接使用。当前系统采用：

- HTTP：发送控制命令
- WebSocket：接收状态和数据

前端必须记住一条规则：

- UI 状态以 WebSocket 推送为准，不要只根据按钮点击或 HTTP 成功返回切换界面

## 1. 交互方式

### 1.1 WebSocket 连接

进入页面后，先建立 WebSocket：

```text
ws://{host}:{port}/ws/planDeduce?sessionId={sessionId}
```

示例：

```text
ws://localhost:8080/ws/planDeduce?sessionId=s1
```

### 1.2 HTTP 控制接口

前端通过 HTTP 调这些接口：

- `/plan/sendPlanDeduce`
- `/plan/skip`
- `/plan/speed`
- `/plan/startOrStop`
- `/plan/fullSaveInterval`
- `/plan/destroy`

## 2. 公共参数

### 2.1 `dbName`

数据库名，例如：

```text
plandeduce
```

后端会按 `dbName` 动态切库，但固定查询 `OBJ_ROOM_HIS` 表。

### 2.2 `sessionId`

前端会话标识。后端用它把 WebSocket 消息推到正确连接。

要求：

- 同一个页面实例内保持固定
- 不同标签页、不同页面实例不要共用

### 2.3 时间单位

当前系统里的推演时间统一按秒处理：

- `skip`
- `deduceTime`
- `fullTime`
- `fullSaveIntervalSeconds`

### 2.4 倍速

倍速没有白名单限制，当前常见用法是：

- `1`：正常播放
- `2`、`3`、`5`：自定义倍速
- `0`：暂停

## 3. 接口说明

### 3.1 初始化任务并开始播放

接口：

```text
GET /plan/sendPlanDeduce
```

调用时机：

- 页面首次进入后第一次开始播放
- 当前任务已经 `destroy`，需要重新创建
- 需要从某个秒点重新初始化

参数：

- `dbName`：必填
- `skip`：可选，默认 `0`
- `sessionId`：可选，默认 `default`

示例：

```text
/plan/sendPlanDeduce?dbName=plandeduce&skip=0&sessionId=s1
```

HTTP 返回：

- `200 OK`
- 响应体为空

对应 WebSocket：

- 首条：`INIT`
- 后续：`PLAY`

前端处理：

- 收到 `INIT` 后设置 `initialized=true`
- 用 `currentTime/fullTime/fullData/incrementalData/data` 初始化页面
- 后续用 `PLAY` 持续刷新

注意：

- 当前初始化接口只支持起播秒点 `skip`，不支持直接传 `fullSaveIntervalSeconds`
- 如果需要修改全量快照间隔，应在初始化前后单独调用 `/plan/fullSaveInterval`

### 3.2 跳转到某个秒点

接口：

```text
GET /plan/skip
```

调用时机：

- 用户拖动进度条
- 用户点击跳转到某个秒点

参数：

- `dbName`：必填
- `skip`：必填
- `sessionId`：可选

示例：

```text
/plan/skip?dbName=plandeduce&skip=13&sessionId=s1
```

HTTP 返回：

- `200 OK`
- 响应体为空

对应 WebSocket：

- `SKIP`

前端处理：

- 刷新 `currentTime`
- 刷新 `fullTime`
- 刷新 `fullData`
- 刷新 `incrementalData`
- 刷新 `data`

### 3.3 修改倍速

接口：

```text
GET /plan/speed
```

调用时机：

- 用户切换倍速
- 用户把倍速设置为 `0` 暂停

参数：

- `dbName`：必填
- `speed`：必填
- `sessionId`：可选

示例：

```text
/plan/speed?dbName=plandeduce&speed=3&sessionId=s1
```

HTTP 返回：

- `200 OK`
- 响应体为空

对应 WebSocket：

- `SPEED`

前端处理：

- 更新倍速显示
- 用消息里的 `running` 判断当前是否仍在播放

关键规则：

- 播放中调速：后续按新倍速推进
- 暂停后调成非 `0`：只改倍速，不自动恢复播放
- `speed=0`：进入暂停态

### 3.4 开始 / 暂停

接口：

```text
GET /plan/startOrStop
```

参数：

- `dbName`：必填
- `flag`：必填
- `sessionId`：可选

说明：

- `flag=0`：暂停
- `flag=1`：开始 / 恢复

示例：

```text
/plan/startOrStop?dbName=plandeduce&flag=0&sessionId=s1
/plan/startOrStop?dbName=plandeduce&flag=1&sessionId=s1
```

对应 WebSocket：

- `flag=0`：`PAUSE`
- `flag=1`：`START`
- 恢复后继续出现 `PLAY`

前端处理：

- 收到 `PAUSE`：`running=false`
- 收到 `START`：`running=true`

### 3.5 修改全量快照间隔

接口：

```text
GET /plan/fullSaveInterval
```

参数：

- `dbName`：必填
- `fullSaveIntervalSeconds`：必填
- `sessionId`：可选

示例：

```text
/plan/fullSaveInterval?dbName=plandeduce&fullSaveIntervalSeconds=20&sessionId=s1
```

对应 WebSocket：

- `INTERVAL`

前端处理：

- 用新的 `fullTime` 规则重新理解快照
- 不要在前端自己硬编码“永远每 10 秒一个全量点”

### 3.6 销毁任务

接口：

```text
GET /plan/destroy
```

调用时机：

- 离开页面
- 关闭当前播放任务
- 当前任务不再使用

示例：

```text
/plan/destroy?dbName=plandeduce&sessionId=s1
```

对应 WebSocket：

- `DESTROY`

前端处理：

- 清空当前任务态
- 后续如要继续，必须重新调 `sendPlanDeduce`

## 4. WebSocket 消息结构

快照返回规则要先区分清楚：

- `INIT` / `SKIP` / `INTERVAL`：返回“最近全量秒点的 `fullData` + 该全量点之后到当前秒的 `incrementalData`”
- `PLAY`：
  - 如果当前帧正好落在全量秒点，只返回该秒点 `fullData`
  - 否则只返回本次播放步长内的增量，即 `(previousTime, currentTime]`

因此前端不要把所有快照类消息都理解成“永远返回从 `fullTime+1` 到 `currentTime` 的累计增量”。

消息示例：

```json
{
  "type": "PLAY",
  "dbName": "plandeduce",
  "sessionId": "s1",
  "currentTime": 15,
  "fullTime": 10,
  "speed": 3,
  "running": true,
  "fullData": [],
  "incrementalData": [
    { "simTime": 13, "sourceType": "INCREMENT" },
    { "simTime": 14, "sourceType": "INCREMENT" },
    { "simTime": 15, "sourceType": "INCREMENT" }
  ],
  "data": [
    { "simTime": 13, "sourceType": "INCREMENT" },
    { "simTime": 14, "sourceType": "INCREMENT" },
    { "simTime": 15, "sourceType": "INCREMENT" }
  ],
  "message": "当前时间 15 秒，返回第 13-15 秒增量数据"
}
```

关键字段：

- `type`
- `dbName`
- `sessionId`
- `currentTime`
- `fullTime`
- `speed`
- `running`
- `fullData`
- `incrementalData`
- `data`
- `message`

说明：

- `message` 只是联调辅助文案，不建议前端把它当结构化协议解析
- 是否有 `fullData`、`incrementalData`，应以前面的正式字段内容为准

### 4.1 快照数据里的业务字段

`data/fullData/incrementalData` 里的元素来自 `OBJ_ROOM_HIS`，当前前端最常用字段通常是：

- `roomObjectId`：棋子ID
- `objCode`：棋子编号
- `side`：推演方
- `objName`：棋子名称
- `currentPos`：当前位置ID
- `nextPos`：下一位置ID
- `direction`：棋子朝向
- `currentSpeed`：当前机动速度
- `visible`：是否可见
- `simTime`：当前秒点
- `targetId`：当前历史记录主键的一部分
- `sourceType`：`FULL` 或 `INCREMENT`

如果前端后续要展示更多棋子属性，可以继续直接读取同一条数据上的其他字段。

## 5. WebSocket 消息类型与前端动作

- `INIT`：任务初始化完成，初始化 UI 和状态
- `PLAY`：播放推进一帧，刷新时间和数据
- `PAUSE`：暂停，切 UI 到暂停态
- `START`：恢复播放，切 UI 到播放态
- `SPEED`：倍速已变更，刷新倍速显示和 `running`
- `SKIP`：跳点成功，刷新时间和快照
- `INTERVAL`：全量间隔已修改，刷新快照解释
- `FINISH`：播放结束，切 UI 到结束态
- `DESTROY`：任务销毁，清空当前任务态
- `ERROR`：后端处理异常，提示错误并停止依赖本地假设推进

## 6. 推荐前端状态结构

```ts
{
  connected: boolean,
  initialized: boolean,
  running: boolean,
  finished: boolean,
  dbName: string,
  sessionId: string,
  currentTime: number,
  fullTime: number,
  speed: number,
  data: any[],
  fullData: any[],
  incrementalData: any[],
  lastMessageType: string | null,
  errorMessage: string | null
}
```

## 7. 前端必须做的事情

1. 先建立 WebSocket，再调 `sendPlanDeduce`
2. 同一个页面实例内固定 `sessionId`
3. 只以 WebSocket 推送更新 UI 状态
4. 暂停后改倍速时，不要自己把 UI 切回播放态
5. 不要在前端本地自己推进秒数，应以后端 `currentTime` 为准
6. 页面退出时主动调 `destroy`
7. 多标签页不要复用同一个 `sessionId`
8. 如果同一页面同时管理多任务，必须按 `dbName + sessionId` 维度区分

## 8. 为什么 `currentTime` 必须以后端为准

前端不要自己用本地定时器推进播放秒数，而应该始终以后端 WebSocket 消息里的 `currentTime` 为准。

原因：

1. `currentTime` 不是单纯的前端动画值，而是后端播放状态机里的真实时间游标
2. 它会受到暂停、恢复、倍速切换、跳点、播放结束等动作影响
3. 快照数据 `fullData/incrementalData/data` 也是围绕这个时间游标计算的，时间和数据必须严格对应

如果前端自己本地推进时间，容易出现：

- 浏览器本地时间和后端真实推进节奏不一致
- 暂停后本地还在走
- 倍速切换后本地推演和后端不一致
- 跳点后时间和数据快照错位

### 8.1 后端什么时候会推 `currentTime`

当前协议里，几乎所有消息都会带 `currentTime`，前端可以统一消费：

- `INIT`：初始化后立刻返回当前快照和当前时间
- `PLAY`：每次播放推进一帧时返回新的当前时间
- `SKIP`：跳点成功后返回跳到的目标时间
- `START` / `PAUSE` / `SPEED`：状态变化时也会带上当前时间
- `INTERVAL`：修改全量快照间隔后，会带当前时间和新规则下的快照
- `FINISH` / `DESTROY` / `ERROR`：结束、销毁、异常时同样会带当前时间

因此前端推荐做法是：

- 发命令靠 HTTP
- 更新 `currentTime` 靠 WebSocket
- 每次收到消息，都直接用消息里的 `currentTime` 刷新进度条和时间显示

### 8.2 正确理解方式

例如：

- 用户点“开始”后，前端不要自己先把时间从 `10` 改成 `11`
- 应该等后端发来 `PLAY.currentTime=11`
- 用户点“跳到 33 秒”后，前端也不要只根据本地拖动结果强行认定已经到 `33`
- 应该以后端发回的 `SKIP.currentTime=33` 和对应快照为准

## 9. 典型调用顺序

场景：进入页面 -> 开始 -> 暂停 -> 改 3 倍速 -> 恢复 -> 跳到 33 秒 -> 退出

1. 建立 WebSocket

```text
/ws/planDeduce?sessionId=s1
```

2. 初始化

```text
/plan/sendPlanDeduce?dbName=plandeduce&skip=0&sessionId=s1
```

3. 暂停

```text
/plan/startOrStop?dbName=plandeduce&flag=0&sessionId=s1
```

4. 改 3 倍速

```text
/plan/speed?dbName=plandeduce&speed=3&sessionId=s1
```

5. 恢复

```text
/plan/startOrStop?dbName=plandeduce&flag=1&sessionId=s1
```

6. 跳点

```text
/plan/skip?dbName=plandeduce&skip=33&sessionId=s1
```

7. 退出

```text
/plan/destroy?dbName=plandeduce&sessionId=s1
```

## 10. 特定场景：3 倍速开始，暂停后切回 1 倍速，再重复切换直到结束

这个场景指的是：

1. 一开始按 `3x` 播放
2. 播放一段时间后暂停
3. 切回 `1x`
4. 再恢复播放
5. 后续继续反复执行“暂停 -> 改倍速 -> 恢复”
6. 直到播放结束

### 10.1 前端应该怎么调接口

假设：

- `dbName=plandeduce`
- `sessionId=s1`

推荐顺序：

1. 先建立 WebSocket

```text
/ws/planDeduce?sessionId=s1
```

2. 初始化任务

```text
/plan/sendPlanDeduce?dbName=plandeduce&skip=0&sessionId=s1
```

3. 等收到 `INIT`

4. 把倍速切到 `3`

```text
/plan/speed?dbName=plandeduce&speed=3&sessionId=s1
```

5. 后续正常接收 `PLAY`

第一次暂停时：

```text
/plan/startOrStop?dbName=plandeduce&flag=0&sessionId=s1
```

期望：

- 收到 `PAUSE`
- `running=false`

切回正常倍速：

```text
/plan/speed?dbName=plandeduce&speed=1&sessionId=s1
```

期望：

- 收到 `SPEED`
- `speed=1`
- 如果当前原本已经暂停，则 `running` 仍然是 `false`

然后恢复播放：

```text
/plan/startOrStop?dbName=plandeduce&flag=1&sessionId=s1
```

期望：

- 收到 `START`
- 后续继续收到 `PLAY`

之后如果再切回 `3x` 或再次切回 `1x`，都遵循同样的顺序：

1. 先暂停
2. 等 `PAUSE`
3. 调 `/plan/speed`
4. 等 `SPEED`
5. 调 `/plan/startOrStop?flag=1`
6. 等 `START`
7. 后续继续收 `PLAY`

注意：

- 当前后端实现里，暂停后改倍速不会自动恢复播放
- 所以“改倍速”和“恢复播放”必须分成两步

### 10.2 前端需要维护哪些状态

建议至少维护这些状态：

```ts
{
  connected: boolean,
  initialized: boolean,
  finished: boolean,

  dbName: string,
  sessionId: string,

  running: boolean,
  speed: number,
  currentTime: number,
  fullTime: number,

  data: any[],
  fullData: any[],
  incrementalData: any[],

  lastMessageType: string | null,
  errorMessage: string | null
}
```

如果页面上有频繁点击按钮的风险，建议再补几个本地命令态，例如：

```ts
{
  pendingPause: boolean,
  pendingResume: boolean,
  pendingSpeedChange: boolean
}
```

作用：

- 避免用户连续点同一个按钮
- 避免前端在后端状态未确认前重复发命令

关键规则：

- `running` 以后端消息为准
- `speed` 以后端消息为准
- `currentTime` 以后端消息为准
- 不要把“按钮刚点下去”当成“状态已经切成功”

### 10.3 前端具体应该怎么实现

推荐实现方式：

#### 1. HTTP 只负责发命令

例如：

```ts
async function pause(dbName: string, sessionId: string) {
  await fetch(`/plan/startOrStop?dbName=${dbName}&flag=0&sessionId=${sessionId}`)
}

async function resume(dbName: string, sessionId: string) {
  await fetch(`/plan/startOrStop?dbName=${dbName}&flag=1&sessionId=${sessionId}`)
}

async function changeSpeed(dbName: string, sessionId: string, speed: number) {
  await fetch(`/plan/speed?dbName=${dbName}&speed=${speed}&sessionId=${sessionId}`)
}
```

#### 2. WebSocket 作为唯一状态源

收到消息后统一更新本地状态：

```ts
ws.onmessage = (event) => {
  const msg = JSON.parse(event.data)

  state.dbName = msg.dbName
  state.sessionId = msg.sessionId
  state.currentTime = msg.currentTime
  state.fullTime = msg.fullTime
  state.speed = msg.speed
  state.running = msg.running
  state.lastMessageType = msg.type

  if (msg.fullData) state.fullData = msg.fullData
  if (msg.incrementalData) state.incrementalData = msg.incrementalData
  if (msg.data) state.data = msg.data

  if (msg.type === 'INIT') state.initialized = true
  if (msg.type === 'FINISH') state.finished = true
  if (msg.type === 'ERROR') state.errorMessage = msg.message ?? 'unknown error'
}
```

#### 3. 不要自己本地推进时间

错误做法：

- 点“开始”后自己每秒给 `currentTime + 1`
- 切到 `3x` 后自己本地每秒加 `3`

正确做法：

- 永远等后端推送新的 `PLAY.currentTime`
- 进度条、时间显示、数据快照统一根据收到的消息刷新

#### 4. 推荐的循环控制方式

如果业务流程是“暂停 -> 切倍速 -> 恢复 -> 再暂停 -> 再切倍速 -> 再恢复”，推荐始终按事件确认来推进，而不是按本地假设推进：

1. 发暂停命令
2. 等 `PAUSE`
3. 发调速命令
4. 等 `SPEED`
5. 发恢复命令
6. 等 `START`
7. 等后续 `PLAY`

这样做最稳，因为它和当前后端状态机完全一致。

### 10.4 这个场景下最容易犯的错

1. 暂停后切到 `1x`，前端以为会自动继续播放
2. 前端自己本地推进 `currentTime`
3. 收到 HTTP `200` 就立即改最终 UI，不等 WebSocket
4. 只更新了时间，没有同步更新 `data/fullData/incrementalData`
5. 多次切换倍速后，前端仍然缓存旧的 `speed` 或旧的 `running`

### 10.5 结束时前端应该怎么处理

播放到结尾时，当前后端会依次推送：

1. 最后一条 `PLAY`
2. `PAUSE`
3. `FINISH`

因此前端建议：

- 收到最后一条 `PLAY` 时更新最终时间和数据
- 收到 `PAUSE` 时把 `running=false`
- 收到 `FINISH` 时把 `finished=true`
- 结束后不要再本地尝试继续推进时间
