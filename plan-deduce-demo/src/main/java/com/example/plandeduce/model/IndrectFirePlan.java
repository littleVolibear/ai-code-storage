package com.example.plandeduce.model;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.sql.Timestamp;

@Data
@TableName("INDRECT_FIRE_PLAN")
public class IndrectFirePlan {
    private Integer id; // 记录 ID
    private Integer roomId; // 推演室 ID
    private Integer objId; // 炮兵棋子 ID
    private Timestamp createTime; // 物理时间
    private Integer ifId; // 间瞄 ID
    private Integer simTime; // 库里按毫秒存储的业务时间，对外查询直接返回原值
    @TableField(exist = false)
    private Integer realTime; // 当前消息对应的真实播放时间
}
