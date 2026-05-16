# plan-deduce-demo

这是一个可直接运行的 Spring Boot 示例项目，用来演示：

1. 前端 WebSocket 连接后，后端启动固定线程池不断发送进度条数据。
2. 支持跳转到某个时间点。
3. 支持自定义倍速。
4. 支持开始和暂停。
5. 支持可配置的全量保存间隔，默认 600 秒。
6. 去掉 Redis，播放状态全部保存在 Java 内存中。
7. 请求参数 `dbName` 表示数据库名，固定查询 `OBJ_ROOM_HIS` 表；表中同时包含棋子静态编成字段（如 `OBJ_CODE/OBJ_NAME/SIDE/OBJ_TYPE`）和每秒状态字段（如 `SIM_TIME/CURRENT_POS/NEXT_POS/CURRENT_SPEED/VISIBLE`）。

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
http://localhost:8080/plan/sendPlanDeduce?dbName=plandeduce&skip=0&sessionId=s1
```

跳转到第 6 秒，会取第 0 秒全量数据：

```text
http://localhost:8080/plan/skip?dbName=plandeduce&skip=6&sessionId=s1
```

跳转到第 11 秒，会取第 10 秒全量数据：

```text
http://localhost:8080/plan/skip?dbName=plandeduce&skip=11&sessionId=s1
```

跳转到第 20 秒，会取第 20 秒全量数据：

```text
http://localhost:8080/plan/skip?dbName=plandeduce&skip=20&sessionId=s1
```

设置倍速：

```text
http://localhost:8080/plan/speed?dbName=plandeduce&speed=2&sessionId=s1
```

暂停：

```text
http://localhost:8080/plan/startOrStop?dbName=plandeduce&flag=0&sessionId=s1
```

开始：

```text
http://localhost:8080/plan/startOrStop?dbName=plandeduce&flag=1&sessionId=s1
```

动态修改全量保存间隔为 20 秒：

```text
http://localhost:8080/plan/fullSaveInterval?dbName=plandeduce&fullSaveIntervalSeconds=20&sessionId=s1
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
