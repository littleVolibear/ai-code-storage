package com.example.plandeduce.model;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.sql.Timestamp;

@Data
@TableName("COMMAND_INFO")
public class CommandInfo {
    private Integer id; // 默认 ID
    private Integer roomId; // 推演室 ID
    private Integer objId; // 棋子 ID
    private Timestamp beginTime; // 开始时间
    private Timestamp receiveTime; // 指令接收时间
    @TableField(exist = false)
    private Integer simTime; // 按 beginTime 和房间开始时间换算出的毫秒时间
    @TableField(exist = false)
    private Integer realTime; // 当前消息对应的真实播放时间
}
