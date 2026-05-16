# 进度条后端业务自测与调试文档

这份文档面向后端开发人员，用于在没有完整前端参与时，自行验证进度条相关代码逻辑是否正确。

当前系统模型：

- HTTP：发送控制命令
- WebSocket：接收状态和数据

## 1. 自测准备

### 1.1 启动应用

```bash
mvn spring-boot:run
```

### 1.2 调试工具

建议准备两个终端：

1. WebSocket 监听终端
2. HTTP 控制终端

WebSocket：

```bash
wscat -c ws://localhost:8080/ws/planDeduce?sessionId=s1
```

或：

```bash
websocat ws://localhost:8080/ws/planDeduce?sessionId=s1
```

HTTP：

```bash
curl "http://localhost:8080/plan/..."
```

### 1.3 当前版本的排查方式说明

当前项目里的业务日志已经去掉，因此排查时不要再依赖历史文档里提到的 `http.* / task.* / ws.*` 日志关键字。

当前建议的排查顺序是：

1. 看 HTTP 状态码是否为 `200`
2. 看 WebSocket 是否收到预期消息
3. 必要时直接用 H2 控制台核对数据

## 2. 公共参数说明

### 2.1 `dbName`

业务含义：

- 数据库名

当前行为：

- 根据 `dbName` 动态切库
- 固定查询 `OBJ_ROOM_HIS` 表

### 2.2 `sessionId`

业务含义：

- 当前前端会话标识

作用：

- 后端用它把 WebSocket 消息推给对应连接

### 2.3 `skip`

业务含义：

- 起播秒点或跳转目标秒点

说明：

- 单位是秒
- 负数会被收敛成 `0`

### 2.4 `speed`

业务含义：

- 播放倍速

说明：

- `1`：正常播放
- `0`：暂停
- 其他正整数：自定义倍速

### 2.5 `fullSaveIntervalSeconds`

业务含义：

- 全量快照间隔，单位秒

示例：

- `10`
- `20`
- `600`

## 3. 当前数据模型说明

后端播放读取的是 `OBJ_ROOM_HIS`。

当前数据处理分两类：

### 3.1 静态字段

这些字段按棋子只加载一次并缓存，例如：

- `OBJ_CODE`
- `OBJ_NAME`
- `SIDE`
- `OBJ_TYPE`
- `COMBAT_GROUP`
- `AIR_DEFENSE_RANGE`

### 3.2 动态字段

这些字段按 `SIM_TIME` 每次动态查询，例如：

- `CURRENT_POS`
- `NEXT_POS`
- `DIRECTION`
- `CURRENT_SPEED`
- `MOVING`
- `STOPPING`
- `VISIBLE`
- `OBJ_CURRENT_VEHICLE_NUM`
- `OBJ_SON_NUM`

### 3.3 快照拼装规则

如果：

- `currentTime=13`
- `fullSaveIntervalSeconds=10`

则应得到：

- `fullTime=10`
- `fullData=10秒全量`
- `incrementalData=11~13秒增量`
- `data=fullData+incrementalData`

补充说明：

- `INIT` / `SKIP` / `INTERVAL` 走的是上面这套“最近全量 + 之后累计增量”的规则
- `PLAY` 不是每次都回整段累计增量
- 对于普通播放帧，当前实现返回的是本次推进步长内的增量，即 `(previousTime, currentTime]`
- 如果 `PLAY.currentTime` 正好落在全量秒点，则当前帧只返回该秒点 `fullData`

## 4. 业务场景与自测方法

### 场景一：首次进入页面，初始化任务并开始播放

调试步骤：

1. 建立 WebSocket
2. 调初始化接口

```bash
curl "http://localhost:8080/plan/sendPlanDeduce?dbName=plandeduce&skip=0&sessionId=s1"
```

期望：

- HTTP 状态码为 `200`
- 首条 WebSocket 消息是 `INIT`
- 后续出现 `PLAY`

### 场景二：正常 1 倍速播放后暂停

```bash
curl "http://localhost:8080/plan/startOrStop?dbName=plandeduce&flag=0&sessionId=s1"
```

期望：

- 收到 `PAUSE`
- `running=false`
- 后续不再出现新的 `PLAY`

### 场景三：暂停后恢复播放

```bash
curl "http://localhost:8080/plan/startOrStop?dbName=plandeduce&flag=1&sessionId=s1"
```

期望：

- 收到 `START`
- 后续重新出现 `PLAY`

### 场景四：播放中切换倍速

```bash
curl "http://localhost:8080/plan/speed?dbName=plandeduce&speed=3&sessionId=s1"
```

期望：

- 收到 `SPEED`
- `speed=3`
- `running=true`
- 后续 `PLAY.currentTime` 按 `+3` 推进

### 场景五：暂停后切换倍速，但不自动恢复播放

```bash
curl "http://localhost:8080/plan/speed?dbName=plandeduce&speed=3&sessionId=s1"
```

期望：

- 收到 `SPEED`
- `speed=3`
- `running=false`
- 后续不自动出现新的 `PLAY`

### 场景六：使用 `speed=0` 作为暂停

```bash
curl "http://localhost:8080/plan/speed?dbName=plandeduce&speed=0&sessionId=s1"
```

期望：

- 收到 `SPEED`
- `speed=0`
- `running=false`

注意：

- 如果之后直接调 `startOrStop(flag=1)` 恢复，速度会兜底回 `1`
- 如果要恢复到别的倍速，前端或调试脚本需要重新设置 `speed`

### 场景七：跳转到指定秒点

```bash
curl "http://localhost:8080/plan/skip?dbName=plandeduce&skip=13&sessionId=s1"
```

期望：

- 收到 `SKIP`
- `currentTime=13`
- `fullTime=10`
- `fullData=10秒快照`
- `incrementalData=11~13秒增量`

### 场景八：修改全量快照间隔

```bash
curl "http://localhost:8080/plan/fullSaveInterval?dbName=plandeduce&fullSaveIntervalSeconds=20&sessionId=s1"
curl "http://localhost:8080/plan/skip?dbName=plandeduce&skip=33&sessionId=s1"
```

期望：

- 收到 `INTERVAL`
- 跳到 33 秒后 `fullTime=20`
- `incrementalData=21~33秒`

### 场景九：播放到结束

```bash
curl "http://localhost:8080/plan/sendPlanDeduce?dbName=plandeduce&skip=1198&sessionId=s1"
curl "http://localhost:8080/plan/speed?dbName=plandeduce&speed=3&sessionId=s1"
```

期望：

- 最后一条 `PLAY.currentTime=1200`
- 随后收到 `PAUSE`
- 再收到 `FINISH`

### 场景十：销毁任务并重新创建

```bash
curl "http://localhost:8080/plan/destroy?dbName=plandeduce&sessionId=s1"
curl "http://localhost:8080/plan/sendPlanDeduce?dbName=plandeduce&skip=0&sessionId=s1"
```

期望：

- 先收到 `DESTROY`
- 销毁后不再出现 `PLAY`
- 重新初始化后再次收到 `INIT`

### 场景十一：多 sessionId 隔离

期望：

- `s1` 只收到 `s1` 的消息
- `s2` 只收到 `s2` 的消息

### 场景十二：多 dbName 隔离

期望：

- 同一个 `sessionId` 下，不同 `dbName` 的任务独立推进
- WebSocket 消息可通过 `dbName` 区分来源

## 5. 常见问题排查

### 5.1 点了开始但没有收到消息

排查顺序：

1. WebSocket 是否已先建立
2. `sessionId` 是否一致
3. 如果 HTTP 已返回 `200`，再看 WebSocket 客户端是否真的收到消息

### 5.2 暂停后还在继续推进

排查顺序：

1. HTTP 是否真的调了 `startOrStop?flag=0`
2. WebSocket 是否收到 `PAUSE`
3. 后续是否仍收到新的 `PLAY`

### 5.3 暂停后改倍速，任务自己又跑起来了

当前正确行为：

- 不应该自动恢复

排查顺序：

1. 看 `SPEED.running` 是否仍为 `false`
2. 看是否有额外的 `startOrStop(flag=1)` 被调用

### 5.4 跳点后的数据不对

优先检查：

- `currentTime`
- `fullTime`
- `fullData`
- `incrementalData`

判断原则：

- `fullTime` 必须是最近全量秒点
- `incrementalData` 必须覆盖 `(fullTime, currentTime]`

### 5.5 接口报 400

当前常见参数错误：

- `fullSaveIntervalSeconds <= 0`
- `dbName` 含非法字符

## 6. 与前端交付时必须说明的规则

1. 先连 WebSocket，再发初始化请求
2. UI 以 WebSocket 推送为准
3. `sendPlanDeduce` 是初始化，不是普通恢复
4. `startOrStop(flag=0/1)` 才是常规暂停/恢复
5. 暂停后改倍速不会自动恢复播放
6. `speed=0` 表示暂停
7. `skip` 单位是秒
8. `fullSaveIntervalSeconds` 单位是秒
9. `destroy` 是彻底销毁，不是暂停
10. 任务唯一维度是 `dbName + sessionId`

## 7. 推荐自测顺序

1. 初始化并开始播放
2. 1 倍速暂停 / 恢复
3. 播放中切换倍速
4. 暂停后调速不自动恢复
5. `speed=0` 暂停
6. 跳 11 秒和 13 秒验证全量 / 增量
7. 修改全量间隔到 20 秒后跳 33 秒
8. 从 1198 秒开始验证结束
9. `destroy` 后重建
10. 多 `sessionId` 隔离
11. 多 `dbName` 隔离
