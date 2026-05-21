# 进度条后端测试用例

这份文档面向测试同学，只保留与当前代码一致的断言口径。

## 1. 测试范围

- `/plan/sendPlanDeduce`
- `/plan/skip`
- `/plan/speed`
- `/plan/startOrStop`
- `/plan/fullSaveInterval`
- `/plan/destroy`
- `/ws/planDeduce`

## 2. 当前协议断言基线

### 2.1 任务隔离

当前任务只按 `sessionId` 隔离，不按 `dbName + sessionId` 隔离。

### 2.2 时间字段

所有 WebSocket 消息至少校验：

- `type`
- `dbName`
- `sessionId`
- `realTime`
- `deduceTime`
- `fullTime`
- `speed`
- `running`

### 2.3 快照字段

快照类消息校验：

- `data`
- `eventData`

兼容字段校验：

- `fullData = []`
- `incrementalData = []`

## 3. 核心测试矩阵

### TC-P0-001 初始化任务后首次开始

- 步骤：
  - 调 `sendPlanDeduce`
  - 再调 `startOrStop?flag=1`
- 预期：
  - 初始化 HTTP 返回 `startTime/endTime`
  - 首条 WS 是 `INIT`
  - 后续出现 `PLAY`

### TC-P0-002 1 倍速开始后暂停

- 预期：
  - 收到 `PAUSE`
  - `running=false`
  - 后续不再有 `PLAY`

### TC-P0-003 暂停后恢复播放

- 预期：
  - 收到 `START`
  - 后续重新出现 `PLAY`

### TC-P0-004 播放中切换 3 倍速

- 预期：
  - 收到 `SPEED`
  - `speed=3`
  - 后续 `PLAY.realTime` 仍按 `+1`
  - 后续 `PLAY.deduceTime` 按 `+3`

### TC-P0-005 暂停后调成 3 倍速会自动恢复

- 预期：
  - 先收到 `SPEED`
  - 再收到 `START`
  - 后续重新出现 `PLAY`

### TC-P0-006 speed=0 作为暂停

- 预期：
  - 收到 `SPEED`
  - `speed=0`
  - `running=false`

### TC-P0-007 跳到 13 秒

- 前置：
  - `fullSaveIntervalSeconds=10`
- 预期：
  - 收到 `SKIP`
  - `realTime=13`
  - `deduceTime=13`
  - `fullTime=10`

### TC-P0-008 暂停状态下 skip 会自动恢复

- 预期：
  - 先收到 `START`
  - 再收到 `SKIP`
  - 后续重新出现 `PLAY`

### TC-P0-009 修改全量间隔为 20 秒后跳到 33 秒

- 预期：
  - 先收到 `INTERVAL`
  - 跳点后 `SKIP.fullTime=20`

### TC-P0-010 播放结束时的最终状态

- 预期：
  - 最后一条 `PLAY` 是 `deduceTime=maxSimTime`
  - 随后 `PAUSE`
  - 随后 `FINISH`

### TC-P1-011 destroy 后允许重建

- 预期：
  - 收到 `DESTROY`
  - 销毁后不再收到 `PLAY`
  - 重新初始化后可再次收到 `INIT`

### TC-P1-012 多 sessionId 并发隔离

- 预期：
  - `s1` 只收到 `s1` 的消息
  - `s2` 只收到 `s2` 的消息

### TC-P1-013 高频组合操作稳定性

- 操作示例：
  - `speed`
  - `skip`
  - `pause`
  - `resume`
- 预期：
  - 全程无 `ERROR`
  - 各类消息顺序与当前实现一致

### TC-P2-014 非法全量间隔

- 预期：
  - HTTP `400`
  - 返回“全量保存间隔必须大于 0 秒”

### TC-P2-015 负数 skip 自动收敛到 0

- 预期：
  - 收到 `SKIP`
  - `realTime=0`
  - `deduceTime=0`
  - `fullTime=0`

## 4. 断言注意点

1. 不要再按 `currentTime` 写断言，当前字段是 `deduceTime`。
2. 不要再要求 `fullData` / `incrementalData` 对外携带真实数据。
3. 如果验证 3 倍速，不要写“每次 currentTime + 3”，而应写：
   - `realTime + 1`
   - `deduceTime + 3`
4. 不要再写“多 dbName 隔离”用例，当前任务管理器只按 `sessionId` 隔离。
