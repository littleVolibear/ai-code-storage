package com.example.plandeduce.model;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("CONTRO_POINT")
public class ControlPoint {
    @TableId(value = "CONTROL_POINT_ID", type = IdType.INPUT)
    private Integer controlPointId; // 夺控点 ID
    private Integer roomId; // 推演室 ID
    private Date createTime; // 创建时间，不再用于夺控点时间匹配
    @TableField(exist = false)
    private Integer simTime; // 来自匹配到的 FIRE_JUDGE_RESULT.simTime
    @TableField(exist = false)
    private Integer realTime; // 当前消息对应的真实播放时间
}
