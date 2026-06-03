package com.example.plandeduce.model;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("CONTRO_POINT")
public class ControlPoint {
    private Integer id; // 默认 ID
    private Integer roomId; // 推演室 ID
    private Date createTime; // 创建时间
    @TableField(exist = false)
    private Integer simTime; // 按 createTime 和房间开始时间换算出的毫秒时间
    @TableField(exist = false)
    private Integer realTime; // 当前消息对应的真实播放时间
}
