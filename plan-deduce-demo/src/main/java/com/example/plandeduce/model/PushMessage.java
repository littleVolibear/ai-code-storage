package com.example.plandeduce.model;

import lombok.Data;

import java.util.List;

@Data
public class PushMessage {
    private String type; // 消息类型，如 INIT/PLAY/PAUSE/SKIP
    private String dbName; // 当前任务所属数据库名
    private String sessionId; // 当前前端会话标识
    private Integer currentTime; // 当前播放到的业务秒点
    private Integer fullTime; // 当前快照对应的最近全量秒点
    private Integer speed; // 当前播放倍速
    private Boolean running; // 当前任务是否处于播放态
    private List<RoomObjectHis> fullData; // 最近全量秒点的数据
    private List<RoomObjectHis> incrementalData; // 全量秒点之后到当前秒的增量数据
    private List<RoomObjectHis> data; // 给前端直接消费的合并结果
    private List<FireJudgeResult> eventData; // 当前帧对应的事件数据
    private String message; // 辅助说明文案
    private Integer maxSimTime; // 推演最大业务时间（进度条结束时间）
}
