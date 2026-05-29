package com.example.plandeduce.model;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("FIRE_JUDGE_RESULT")
public class FireJudgeResult {
    private Long id; // 裁决 ID
    @TableField("ROOM_ID")
    private String roomId; // 推演室 ID
    @TableField("OBJ_ID")
    private Integer objId; // 射击棋子 ID
    @TableField("TAR_OBJ_ID")
    private Integer tarObjId; // 被射击棋子 ID
    @TableField("PHYSICAL_TIME")
    private Integer physicalTime; // 库里按毫秒存储的仿真时间，对外查询直接返回原值
    @TableField("SIM_TIME")
    private Integer simTime; // 库里按毫秒存储的业务时间，对外查询直接返回原值
    @TableField(exist = false)
    private Integer realTime; // 记录对应的真实时间，当前取自 simTime
}
