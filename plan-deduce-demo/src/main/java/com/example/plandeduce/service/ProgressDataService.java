package com.example.plandeduce.service;

import com.example.plandeduce.model.RoomObjectHis;

import java.util.List;

public interface ProgressDataService {
    /**
     * 预热当前库在指定全量间隔下的基础快照缓存。
     * 当前实现只保证 0 点全量快照可用，其他全量点按需生成并缓存。
     */
    void preloadFullSnapshots(String dbName, int intervalSeconds);

    /**
     * 查询某个全量秒点的完整快照。
     * 返回结果优先走缓存，缓存未命中时会现场构造并回填缓存。
     */
    List<RoomObjectHis> queryCachedFullData(String dbName, int intervalSeconds, int simTime);

    /**
     * 查询指定秒点的完整全量快照。
     */
    List<RoomObjectHis> queryFullData(String dbName, Integer simTime);

    /**
     * 查询播放推进时使用的原始增量数据。
     * 保留区间内所有变化记录，供 PLAY 消息按步长逐帧下发。
     */
    List<RoomObjectHis> queryIncrementalData(String dbName, Integer fromExclusive, Integer toInclusive);

    /**
     * 查询快照类消息使用的增量数据。
     * 对同一个 roomObjectId 只保留区间内最后一次生效状态。
     */
    List<RoomObjectHis> querySnapshotIncrementalData(String dbName, Integer fromExclusive, Integer toInclusive);

    /**
     * 查询当前库可播放到的最大业务秒点。
     */
    Integer queryMaxSimTime(String dbName);

    /**
     * 查询当前推演任务的开始时间。
     * 优先按 roomInfo.roomId=dbName 命中；如果未配置，则回退到首条 roomInfo 记录。
     */
    String queryRoomStartTime(String dbName);
}
