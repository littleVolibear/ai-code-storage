# PushMessage 协议示例

## 目的

这份文档用于说明 `PushMessage` 在播放、倍速和跳点场景下的时间语义，重点区分下面 3 个字段：

- `realTime`：真实流逝时间
- `deduceTime`：当前推演推进到的时间
- `data[].simTime` / `eventData[].simTime`：每条记录在数据库表中的原始仿真时间

## 场景示例

场景：

1. 初始以 `1x` 正常播放
2. 已经播放到 `2` 秒
3. 用户把倍速切换成 `3x`
4. 下一帧通过 WebSocket 向前端推送 `PLAY`

这时应按以下语义理解：

- `realTime=3`
  含义：真实只过去了 1 秒，所以真实时间从 `2` 走到 `3`
- `deduceTime=5`
  含义：推演按 `3x` 推进了 3 个秒点，所以当前推演时间从 `2` 走到 `5`
- `data[].simTime`
  含义：每条棋子记录所属的推演秒点，保持表中的原始值，不会全部改写成 `5`
- `eventData[].simTime`
  含义：每条事件记录所属的推演秒点，保持表中的原始值，不会全部改写成 `5`
- `data[].realTime` / `eventData[].realTime`
  含义：和外层 `realTime` 保持一致，表示“这条消息是在真实第几秒发出的”

## 示例报文

```json
{
  "type": "PLAY",
  "dbName": "1",
  "sessionId": "demo-session",
  "realTime": 3,
  "deduceTime": 5,
  "fullTime": 0,
  "speed": 3,
  "running": true,
  "fullData": [],
  "incrementalData": [],
  "data": [
    { "roomObjectId": 101, "simTime": 3, "realTime": 3 },
    { "roomObjectId": 102, "simTime": 3, "realTime": 3 },
    { "roomObjectId": 103, "simTime": 3, "realTime": 3 },
    { "roomObjectId": 101, "simTime": 4, "realTime": 3 },
    { "roomObjectId": 102, "simTime": 4, "realTime": 3 },
    { "roomObjectId": 103, "simTime": 4, "realTime": 3 },
    { "roomObjectId": 101, "simTime": 5, "realTime": 3 },
    { "roomObjectId": 102, "simTime": 5, "realTime": 3 },
    { "roomObjectId": 103, "simTime": 5, "realTime": 3 }
  ],
  "eventData": [
    { "id": 9001, "roomId": "1", "simTime": 3, "realTime": 3 },
    { "id": 9002, "roomId": "1", "simTime": 4, "realTime": 3 },
    { "id": 9003, "roomId": "1", "simTime": 5, "realTime": 3 }
  ],
  "message": "当前真实时间 3 秒，推演时间 5 秒，返回第 3-5 秒增量数据",
  "maxSimTime": 1200
}
```

## 前端消费建议

- 进度条真实播放节奏看外层 `realTime`
- 当前推演推进到哪里看外层 `deduceTime`
- 具体渲染哪几秒的数据，看 `data[].simTime`
- 具体渲染哪几秒的事件，看 `eventData[].simTime`
