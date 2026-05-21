package com.example.plandeduce.model;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("FIRE_JUDGE_RESULT")
public class FireJudgeResult {
    @TableId("ID")
    private Long id; // 裁决 ID
    @TableField("ROOM_ID")
    private String roomId; // 推演室 ID
    @TableField("PHYSICAL_TIME")
    private Integer physicalTime; // 仿真时间
    @TableField("SIM_TIME")
    private Integer simTime; // 事件对应的业务秒点
    @TableField(exist = false)
    private Integer realTime; // 记录对应的真实时间，当前取自 simTime
}
