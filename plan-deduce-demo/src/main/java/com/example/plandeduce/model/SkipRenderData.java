package com.example.plandeduce.model;

import lombok.Data;

import java.util.List;

@Data
public class SkipRenderData {
    private List<RoomObjectHis> data; // 与 SKIP 外层 data 一致，表示跳点后的对象当前状态
    private List<FireJudgeResult> eventData; // 跳点目标秒窗口内的事件数据
    private List<IndrectFirePlan> indrectFirePlanData; // 跳点目标秒窗口内的间瞄计划数据
    private List<CommandInfo> commandInfoData; // 跳点目标秒窗口内的指令信息数据
}
