package com.example.plandeduce.model;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("ROOM_INFO")
public class RoomInfo {
    @TableId("id")
    private Long id;
    @TableField("title")
    private String title;
    @TableField("totalTime")
    private Integer totalTime;
    @TableField("startTime")
    private Date startTime;
}
