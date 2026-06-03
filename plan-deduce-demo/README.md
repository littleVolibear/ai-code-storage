# plan-deduce-demo

这是一个可直接运行的 Spring Boot 示例项目，用来演示：

1. 前端 WebSocket 连接后，后端启动固定线程池不断发送进度条数据。
2. 支持跳转到某个时间点。
3. 支持自定义倍速。
4. 支持开始和暂停。
5. 支持可配置的全量保存间隔，默认 600 秒。
6. 去掉 Redis，播放状态全部保存在 Java 内存中。
7. 初始化时前端会传 `dbName` 和 `checkpoint`；后端内部会把动态数据源标识拼成 `wargame + dbName + "_" + checkpoint`，同时继续使用原始 `dbName` 作为房间标识回传前端和查询 `ROOM_INFO.id`。

## 运行方式

Mac 上解压后，用 IDEA 打开项目，等待 Maven 依赖下载完成，直接运行：

`com.example.plandeduce.PlanDeduceApplication`

或者命令行运行：

```bash
mvn spring-boot:run
```

浏览器打开：

```text
http://localhost:8080/index.html
```

前端接入说明文档：

```text
docs/frontend-integration-guide.md
```

传输方案比较文档：

```text
docs/transport-comparison.md
```

后端测试用例文档：

```text
docs/backend-progress-test-cases.md
```

后端自测与调试文档：

```text
docs/backend-self-check-guide.md
```

fullSaveInterval 设计对比文档：

```text
docs/full-save-interval-design-comparison.md
```

## 测试接口

WebSocket：

```text
ws://localhost:8080/ws/planDeduce?sessionId=s1
```

初始化播放：

```text
http://localhost:8080/plan/sendPlanDeduce?dbName=1&checkpoint=1&skip=0&sessionId=s1
```

跳转到第 6 秒：

- `data` 按 `RoomObjectHis` 最近全量快照数据加跳点区间增量数据拼装当前状态。
- `eventData`、`indrectFirePlanData`、`commandInfoData`、`controlPointData` 返回第 0-6 秒的全部数据。
- `skipRenderData.data` 与外层 `data` 一致；`skipRenderData` 里的另外四类数据只返回第 6 秒窗口内的数据。

```text
http://localhost:8080/plan/skip?dbName=1&skip=6&sessionId=s1
```

跳转到第 11 秒：

- `data` 按 `RoomObjectHis` 最近全量快照数据加跳点区间增量数据拼装当前状态。
- `eventData`、`indrectFirePlanData`、`commandInfoData`、`controlPointData` 返回第 0-11 秒的全部数据。
- `skipRenderData.data` 与外层 `data` 一致；`skipRenderData` 里的另外四类数据只返回第 11 秒窗口内的数据。

```text
http://localhost:8080/plan/skip?dbName=1&skip=11&sessionId=s1
```

跳转到第 20 秒：

- `data` 按 `RoomObjectHis` 最近全量快照数据加跳点区间增量数据拼装当前状态。
- `eventData`、`indrectFirePlanData`、`commandInfoData`、`controlPointData` 返回第 0-20 秒的全部数据。
- `skipRenderData.data` 与外层 `data` 一致；`skipRenderData` 里的另外四类数据只返回第 20 秒窗口内的数据。

```text
http://localhost:8080/plan/skip?dbName=1&skip=20&sessionId=s1
```

设置倍速：

```text
http://localhost:8080/plan/speed?dbName=1&speed=2&sessionId=s1
```

暂停：

```text
http://localhost:8080/plan/startOrStop?dbName=1&flag=0&sessionId=s1
```

开始：

```text
http://localhost:8080/plan/startOrStop?dbName=1&flag=1&sessionId=s1
```

动态修改全量保存间隔为 20 秒：

```text
http://localhost:8080/plan/fullSaveInterval?dbName=1&fullSaveIntervalSeconds=20&sessionId=s1
```

## H2 控制台

```text
http://localhost:8080/h2-console
```

JDBC URL：

```text
jdbc:h2:mem:plandeduce
```

用户名：

```text
sa
```

密码为空。
