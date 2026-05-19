# 前端常见场景调用说明

这份文档专门给前端同学使用，回答两个核心问题：

1. 常见业务场景下，前端应该调哪个接口
2. 调接口时，前端需要注意哪些状态、缓存和边界条件

当前系统模型：

- HTTP：发控制命令
- WebSocket：收状态和数据

因此前端必须遵守一条原则：

- 发命令靠 HTTP
- 判断结果和切换 UI 靠 WebSocket

## 1. 最常见的业务场景

### 场景一：用户第一次进入页面，准备开始播放

前端操作顺序：

1. 先建立 WebSocket
2. 再调用初始化接口

WebSocket：

```text
ws://localhost:8080/ws/planDeduce?sessionId=s1
```

HTTP：

```text
/plan/sendPlanDeduce?dbName=plandeduce&skip=0&sessionId=s1
```

前端应该期待：

- 首条 WebSocket 消息：`INIT`
- 后续开始收到：`PLAY`

前端注意：

- 不要先调 `sendPlanDeduce` 再连 WebSocket，否则首条 `INIT` 可能丢
- 这个接口是“初始化任务”，不是普通恢复播放接口

### 场景二：用户点击暂停

HTTP：

```text
/plan/startOrStop?dbName=plandeduce&flag=0&sessionId=s1
```

前端应该期待：

- 收到 `PAUSE`

前端注意：

- 不要在按钮点击后立刻把页面强制切成暂停态
- 应该等 WebSocket 的 `PAUSE` 到了，再把 `running` 设为 `false`

### 场景三：用户点击继续播放

HTTP：

```text
/plan/startOrStop?dbName=plandeduce&flag=1&sessionId=s1
```

前端应该期待：

- 先收到 `START`
- 后续继续收到 `PLAY`

前端注意：

- `START` 表示“恢复播放”
- 真正的时间推进要看后续 `PLAY.currentTime`

### 场景四：用户在播放中切换倍速

HTTP：

```text
/plan/speed?dbName=plandeduce&speed=3&sessionId=s1
```

前端应该期待：

- 收到 `SPEED`
- `speed=3`
- 如果原本在播放，后续 `PLAY` 按新倍速推进

前端注意：

- 倍速是对后续推进生效，不会回补历史秒点
- 倍速显示应以后端返回的 `speed` 为准

### 场景五：用户先暂停，再切换倍速

HTTP：

```text
/plan/speed?dbName=plandeduce&speed=3&sessionId=s1
```

前端应该期待：

- 收到 `SPEED`
- `speed=3`
- `running=false`

前端注意：

- 暂停后改倍速不会自动恢复播放
- 如果产品期望“改完倍速就直接继续”，前端必须再调一次开始接口

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

- 这也是暂停
- 如果之后直接调 `startOrStop(flag=1)` 恢复，当前后端会把速度兜底恢复到 `1`
- 如果前端希望恢复到原先的 `3` 或 `5`，要在恢复前重新调一次 `speed`

### 场景七：用户拖动进度条，跳到某个秒点

HTTP：

```text
/plan/skip?dbName=plandeduce&skip=33&sessionId=s1
```

前端应该期待：

- 收到 `SKIP`
- `currentTime=33`
- 同时拿到新的 `fullData/incrementalData/data`

前端注意：

- 跳点只改当前推演时间，不强制修改运行态
- 如果原本在播放，跳点后会继续播放
- 如果原本暂停，跳点后只刷新数据，不自动开始

### 场景八：用户修改全量快照间隔

HTTP：

```text
/plan/fullSaveInterval?dbName=plandeduce&fullSaveIntervalSeconds=20&sessionId=s1
```

前端应该期待：

- 收到 `INTERVAL`

前端注意：

- 后续快照规则已经变了
- 不要在前端自己写死“每 10 秒一个全量点”
- 必须以后端每条消息里的 `fullTime` 为准

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

## 2. 前端该怎么维护状态

建议维护一个统一状态对象：

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

更新原则：

- 只要收到 WebSocket，就用消息更新本地状态
- 不要只根据按钮点击来改状态

## 3. 前端应该缓存什么

建议缓存这些内容：

- 当前 `dbName`
- 当前 `sessionId`
- 当前 `currentTime`
- 当前 `fullTime`
- 当前 `speed`
- 当前 `running`
- 当前 `data/fullData/incrementalData`
- 当前是否已经 `initialized`
- 当前是否已经 `finished`

## 4. 前端不应该缓存什么

不要长期缓存这些推断结论：

- 不要缓存“全量点永远是每 10 秒”
- 不要缓存“暂停后恢复一定还是上一次倍速”
- 不要缓存“HTTP 成功就等于状态已经变更成功”
- 不要缓存“当前页面只有一个任务”

这些都应该以后端返回为准。

## 5. 前端调用接口时的注意事项

### 5.1 `sessionId` 必须稳定

同一个页面实例内，`sessionId` 要固定。

否则会出现：

- 控制命令发给一个任务
- WebSocket 却在监听另一个任务

### 5.2 先连 WebSocket，再调初始化

这是最重要的一条。

否则可能出现：

- 后端已经推了 `INIT`
- 前端还没连上
- 页面状态初始化不完整

### 5.3 HTTP 成功不代表 UI 就该立即切换

例如：

- 点暂停后，HTTP 状态码为 `200`
- 但前端仍应等 `PAUSE` 消息到了，再切换界面

### 5.4 进度条不要自己本地计时推进

应该以后端的 `PLAY.currentTime` 为准。

否则容易出现：

- 本地时间和后端时间不一致
- 跳点后 UI 和数据错位
- 暂停后本地动画还在偷偷走

### 5.5 多标签页不要共用同一个 `sessionId`

否则消息会串。

建议每个页面实例生成自己的 `sessionId`。

### 5.6 如果前端支持多任务，必须按 `dbName + sessionId` 维度缓存

因为当前任务唯一键就是：

- `dbName`
- `sessionId`

## 6. 推荐的前端调用流程

### 流程一：正常开始播放

1. 建立 WebSocket
2. 调 `sendPlanDeduce`
3. 等 `INIT`
4. 等 `PLAY`

### 流程二：暂停再恢复

1. 调 `startOrStop(flag=0)`
2. 等 `PAUSE`
3. 调 `startOrStop(flag=1)`
4. 等 `START`
5. 等后续 `PLAY`

### 流程三：暂停后改倍速再恢复

1. 调 `startOrStop(flag=0)`
2. 等 `PAUSE`
3. 调 `speed`
4. 等 `SPEED`
5. 调 `startOrStop(flag=1)`
6. 等 `START`
7. 等后续 `PLAY`

### 流程四：跳点

1. 调 `skip`
2. 等 `SKIP`
3. 用返回的新快照刷新页面

### 流程五：退出页面

1. 调 `destroy`
2. 等 `DESTROY`
3. 清理本地状态
4. 关闭 WebSocket

## 7. 最容易出问题的点

最常见的几个坑：

1. 先调初始化，再连 WebSocket
2. HTTP 成功后前端自己猜状态
3. 暂停后改倍速，前端误以为会自动恢复
4. 前端自己本地推进 `currentTime`
5. 多标签页复用同一个 `sessionId`
6. 前端缓存旧的 `fullTime` 规则，不跟随后端消息更新

## 8. 给前端的最终建议

最稳妥的做法是：

1. 前端把 HTTP 只当“发命令”
2. 前端把 WebSocket 只当“唯一状态源”
3. 页面里只维护一个统一状态对象
4. 所有 UI 渲染都从这个状态对象读取

这样最不容易出现“按钮状态、进度条、数据内容三者不一致”的问题。
