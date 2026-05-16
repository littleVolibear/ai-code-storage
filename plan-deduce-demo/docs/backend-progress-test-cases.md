# 进度条后端测试用例

这份文档面向测试同学，目标是为进度条后端编写自动化测试或联调用例，覆盖核心播放逻辑、状态切换逻辑、快照拼装逻辑，以及多会话隔离逻辑。

当前系统模型：

- HTTP：控制命令
- WebSocket：状态和数据

## 1. 测试目标

本轮测试重点验证：

1. 初始化、播放、暂停、恢复
2. 倍速切换与 `speed=0`
3. 跳点后的全量 / 增量快照拼装
4. 全量间隔修改后的 `fullTime` 计算
5. 结束、销毁、重建
6. 多 `sessionId` 与多 `dbName` 隔离
7. 高频组合操作稳定性

## 2. 测试范围

在范围内：

- `/plan/sendPlanDeduce`
- `/plan/skip`
- `/plan/speed`
- `/plan/startOrStop`
- `/plan/fullSaveInterval`
- `/plan/destroy`
- `/ws/planDeduce`

## 3. 测试环境与前置条件

### 3.1 环境要求

- Spring Boot 应用正常启动
- WebSocket 可连接
- H2 内存库可初始化
- 测试环境建议 `plan.deduce.tick-interval-ms=100`

### 3.2 数据前置条件

当前样例数据要求：

- 固定表名：`OBJ_ROOM_HIS`
- 时间单位：秒
- `SIM_TIME` 覆盖 `0..1200`
- 每秒 3 条棋子历史记录
- 动态字段按秒变化，静态字段在缓存回填后应完整返回

### 3.3 通用断言点

所有 WebSocket 消息至少校验：

- `type`
- `dbName`
- `sessionId`
- `currentTime`
- `fullTime`
- `speed`
- `running`

快照类消息还要校验：

- `fullData`
- `incrementalData`
- `data`

数据字段建议至少校验：

- `roomObjectId`
- `objCode`
- `side`
- `objName`
- `currentPos`
- `nextPos`
- `direction`
- `currentSpeed`
- `visible`
- `simTime`
- `targetId`

## 4. 测试用例矩阵

### TC-P0-001 初始化任务并开始播放

- 目标：收到 `INIT` 后开始收到 `PLAY`
- 预期：
  - HTTP 成功
  - 第一条是 `INIT`
  - 后续出现 `PLAY`

### TC-P0-002 1 倍速开始后暂停

- 目标：暂停后不再继续推进
- 预期：
  - 收到 `PAUSE`
  - `running=false`
  - 后续不再有 `PLAY`

### TC-P0-003 暂停后恢复播放

- 目标：暂停后可以恢复
- 预期：
  - 收到 `START`
  - 后续重新出现 `PLAY`

### TC-P0-004 播放中切换 3 倍速

- 目标：后续按新倍速推进
- 预期：
  - 收到 `SPEED`
  - `speed=3`
  - 后续 `currentTime` 按 `+3` 推进

### TC-P0-005 暂停后调成 3 倍速，不自动恢复播放

- 目标：暂停后调速只改速度
- 预期：
  - `SPEED.running=false`
  - 不自动出现 `PLAY`

### TC-P0-006 暂停后调速，再开始播放

- 目标：恢复后按新倍速推进
- 预期：
  - 调速后不自动播放
  - 开始后按新倍速推进

### TC-P0-007 speed=0 作为暂停

- 目标：`speed=0` 能进入暂停态
- 预期：
  - 收到 `SPEED`
  - `speed=0`
  - `running=false`

### TC-P0-008 跳到 11 秒

- 前置：`fullSaveIntervalSeconds=10`
- 预期：
  - `currentTime=11`
  - `fullTime=10`
  - `fullData=10秒全量`
  - `incrementalData=11秒增量`

### TC-P0-009 跳到 13 秒

- 前置：`fullSaveIntervalSeconds=10`
- 预期：
  - `currentTime=13`
  - `fullTime=10`
  - `incrementalData=11~13秒`

### TC-P0-010 修改全量间隔为 20 秒后跳到 33 秒

- 预期：
  - 收到 `INTERVAL`
  - 跳点后 `fullTime=20`
  - `incrementalData=21~33秒`

### TC-P0-011 3 倍速循环播放到结束

- 目标：长链路暂停 / 恢复直到结束稳定
- 预期：
  - 过程无 `ERROR`
  - 最终收到 `FINISH`

### TC-P0-012 播放结束时的最终状态

- 目标：最后一帧和结束状态都正确
- 预期：
  - 最后一条 `PLAY.currentTime=maxSimTime`
  - 随后 `PAUSE`
  - 随后 `FINISH`

### TC-P1-013 destroy 后资源释放并允许重建

- 预期：
  - 收到 `DESTROY`
  - 销毁后不再收到 `PLAY`
  - 再初始化后能重新收到 `INIT`

### TC-P1-014 多 sessionId 并发隔离

- 预期：
  - `s1` 只收到 `s1` 的消息
  - `s2` 只收到 `s2` 的消息

### TC-P1-015 多 dbName 并发隔离

- 预期：
  - 不同 `dbName` 互不干扰
  - WebSocket 消息可按 `dbName` 区分

### TC-P1-016 高频组合操作稳定性

- 操作示例：
  - `skip`
  - `speed`
  - `pause`
  - `resume`
- 预期：
  - 全程无 `ERROR`
  - 每步状态都符合顺序

### TC-P1-017 1 倍速播放 5 秒，暂停，切 5 倍速，再播放到 20 秒

- 预期：
  - 暂停后不自动播放
  - 恢复后按 `+5` 推进
  - `10`、`20` 等全量点计算正确

### TC-P1-018 1 倍速开始，暂停，切 3 倍速，再经历“开始-暂停-开始-暂停”

- 预期：
  - `speed` 保持为 `3`
  - 多次 `START/PAUSE` 状态正确

### TC-P2-019 非法全量间隔

- 预期：
  - HTTP `400`
  - 返回明确错误消息

### TC-P2-020 跳到负数时间

- 预期：
  - `currentTime=0`
  - `fullTime=0`

### TC-P2-021 未建立 WebSocket 时发送控制命令

- 预期：
  - HTTP 请求仍能成功返回
  - 后端不抛出未处理异常

## 5. 关键断言补充

### 5.1 快照拼装规则

如果：

- `currentTime=13`
- `fullSaveIntervalSeconds=10`

则应得到：

- `fullTime=10`
- `fullData=10秒全量`
- `incrementalData=11~13秒增量`
- `data=fullData+incrementalData`

补充断言：

- `INIT` / `SKIP` / `INTERVAL` 应按“最近全量 + 之后累计增量”校验
- 普通 `PLAY` 帧应按本次步长校验增量范围，即 `(previousTime, currentTime]`
- 如果 `PLAY.currentTime` 正好落在全量秒点，应只校验该秒点 `fullData`，不应再要求累计 `incrementalData`

### 5.2 状态切换规则

- `speed=0`：进入暂停态
- 暂停后改成非 `0`：只改速度，不自动开始
- `startOrStop(flag=1)`：恢复播放
- `destroy`：彻底销毁任务

### 5.3 结束规则

- 最终到达 `maxSimTime`
- 到达末尾后收到 `FINISH`
- 结束后不再继续产生新的 `PLAY`

## 6. 当前自动化覆盖矩阵

| 测试用例 | 对应自动化测试方法 | 覆盖状态 |
| --- | --- | --- |
| TC-P0-001 | `shouldStartPauseAndResumeAtOneX` | 已覆盖 |
| TC-P0-002 | `shouldStartPauseAndResumeAtOneX` | 已覆盖 |
| TC-P0-003 | `shouldStartPauseAndResumeAtOneX` | 已覆盖 |
| TC-P0-004 | `shouldHonorCustomSpeedPauseAndSkip` | 已覆盖 |
| TC-P0-005 | `shouldHandlePauseThenSwitchToThreeXThenPauseStartPauseSequence`、`shouldHandleOneXRunForTwoSecondsThenPauseChangeToThreeXStartAndPause` | 已覆盖 |
| TC-P0-006 | `shouldHandleOneXRunForTwoSecondsThenPauseChangeToThreeXStartAndPause` | 已覆盖 |
| TC-P0-007 | `shouldPauseWhenSpeedIsZero` | 已覆盖 |
| TC-P0-008 | `shouldReturnFullAndIncrementalDataWhenSkippingToEleven` | 已覆盖 |
| TC-P0-009 | `shouldReturnFullAndCumulativeIncrementalDataWhenSkippingToThirteen` | 已覆盖 |
| TC-P0-010 | `shouldUseTwentyMinuteFullSnapshotWhenIntervalChanges` | 已覆盖 |
| TC-P0-011 | `shouldHandleRepeatedThreeXPauseResumeCyclesUntilTwentyMinutesFinish` | 已覆盖 |
| TC-P0-012 | `shouldSendFinishWhenPlaybackReachesEnd` | 已覆盖 |
| TC-P1-013 | `shouldReleaseResourcesAfterDestroy` | 已覆盖 |
| TC-P1-014 | `shouldKeepMultipleSessionsIsolated` | 已覆盖 |
| TC-P1-015 | `shouldKeepMultipleDbNamesIsolated` | 已覆盖 |
| TC-P1-016 | `shouldHandleRapidSkipSpeedPauseCombinations` | 已覆盖 |
| TC-P1-017 | `shouldHandleOneXForFiveSecondsThenPauseSwitchToFiveXAndRunToTwentySeconds` | 已覆盖 |
| TC-P1-018 | `shouldHandlePauseThenSwitchToThreeXThenPauseStartPauseSequence` | 已覆盖 |
| TC-P2-019 | `shouldRejectInvalidFullSaveInterval` | 已覆盖 |
| TC-P2-020 | `shouldClampNegativeSkipToZero` | 已覆盖 |
| TC-P2-021 | `shouldAcceptControlCommandsWithoutWebSocketConnection` | 已覆盖 |

## 7. 当前结论

- 文档中的 21 个测试点都有对应自动化测试
- 自动化测试采用集成测试方式，统一覆盖 HTTP 控制链路和 WebSocket 推送链路
- 如后续新增业务规则，建议先补文档里的测试点，再补自动化测试方法
