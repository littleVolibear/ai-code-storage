package com.example.plandeduce.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.sql.Timestamp;

@Data
@TableName("OBJ_ROOM_HIS")
public class RoomObjectHis {
    private Integer roomObjectId; // 棋子ID（联合主键）
    private Integer scenarioId; // 想定ID
    private Integer roomId; // 推演室ID
    private String orgSeq; // 编制序列
    private String objCode; // 棋子编号
    private Integer objVehicleNum; // 初始班车数
    private Integer objLevel; // 级别
    private Integer objProtoId; // 棋子ID
    private String side; // 推演方
    private String objName; // 棋子名称
    @TableField("ICON_3D")
    private String icon3d; // 棋子三维度图标
    @TableField("ICON_2D")
    private String icon2d; // 棋子二维军标
    private Integer iconSize; // 图标大小
    private Integer objType; // 棋子类型
    private String combatGroup; // 战斗编组名称
    private Integer stealthValue; // 隐蔽值
    private Integer targetValue; // 目标价值
    private Integer workObstacle; // 是否工事/障碍
    private Integer entryTime; // 入场时间
    private Integer personCount; // 成员数
    private Integer armorLevel; // 装甲级别
    private Integer combatMode; // 作战方式
    private Integer movingFireCapability; // 行进间射击能力
    private Integer weaponDiversity; // 武器多样化
    private Integer reconCommsType; // 侦察通信手段
    private Integer carryingCapacity; // 装载能力上限
    private Integer moveStopConvertTime; // 机动停止时长
    private Integer attackInterval; // 打击间隔时间
    private Integer offRoadMaxSpeed; // 越野每格耗时（毫秒）
    private Integer normalRoadMaxSpeed; // 普通公路每格耗时（毫秒）
    private Integer fastRoadMaxSpeed; // 快速公路每格耗时（毫秒）
    private Integer highwayMaxSpeed; // 高速公路每格耗时（毫秒）
    private Integer reconPersonnel; // 对人观测距离
    private Integer reconVehicle; // 对车辆观测距离
    private Integer reconAir; // 对空中目标观测距离
    private Integer reconObstacle; // 观测障碍物
    private Integer reconMinefield; // 对雷区观测
    private Integer deploymentDuration; // 设备展开时间
    private Integer withdrawalDuration; // 设备撤收时间
    private Integer camouflageDuration; // 伪装掩蔽时长
    private Integer normalAmmunition; // 普通弹药数
    private Integer heavyAmmunition; // 重型弹药数
    private Integer mediumAmmunition; // 中型弹药数
    private Integer smallAmmunition; // 小型弹药数
    private Integer ammunitionConsumption; // 弹药消耗标准
    private Integer weaponList; // 武器列表
    private String carryingObjList; // 可装载棋子列表
    private String operatorId; // 用户ID
    private Integer objCurrentVehicleNum; // 当前载具数
    private Integer objParentId; // 父棋子ID
    private Integer aiInterface; // AI智能体名称
    private String objSonNum; // 当前棋子数量
    private Integer objSonOriginalId; // 父棋子ID
    private Integer currentPos; // 当前位置ID
    private Integer nextPos; // 下一位置ID
    private Integer direction; // 棋子朝向
    private Integer currentSpeed; // 当前机动速度（毫秒）
    private Integer moving; // 是否正在机动
    private Integer stopping; // 是否正在停止
    private Integer suppressed; // 是否被压制
    private Integer cooling; // 设备冷却时间
    private Integer ifPlanId; // 是否有计划ID
    private Integer hideStatus; // 是否在停止中
    private Integer firingOrMarching; // 是否冷却中
    private Integer combatHeight; // 是否炮火准备中（回瞄）
    private Integer weaponId; // 棋子状态
    private Integer weaponRange; // 作战距离
    private Integer weaponNum; // 当前使用武器ID
    private Integer fortObjId; // 当前武器射程
    private Integer supporting; // 支援状态
    private Integer loadState; // 当前弹药数
    private Integer visible; // 是否可见
    private Integer simTime; // 工事内棋子ID
    private Long createTime; // 保障单位是否正在作业
    private Long ifCountdown; // 装载状态：1已装载，2空置中
    private Timestamp weaponCountdown; // 棋子是否对敌方可见：0不可见，1可见
    @TableId(value = "TARGET_ID", type = IdType.INPUT)
    private Long targetId; // 伤害记录时间（联合主键）
    private Integer firstCategoryCode; // 一级分类ID
    private Integer secondCategoryCode; // 二级分类ID
    private String airDefenseRange; // 防空范围
    @TableField(exist = false)
    private String sourceType; // 快照来源：FULL 或 INCREMENT
    @TableField(exist = false)
    private Integer realTime; // 记录对应的真实时间，当前取自 simTime
}
