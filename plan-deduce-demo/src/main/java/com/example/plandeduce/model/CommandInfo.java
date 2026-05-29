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
    private Timestamp receiveTime; // 指令接收时间
    private Integer simTime; // 库里按毫秒存储的业务时间，对外查询直接返回原值
    @TableField(exist = false)
    private Integer realTime; // 当前消息对应的真实播放时间
}
