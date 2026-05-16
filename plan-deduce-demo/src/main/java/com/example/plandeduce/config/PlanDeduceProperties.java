package com.example.plandeduce.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "plan.deduce")
public class PlanDeduceProperties {
    private int defaultFullSaveIntervalSeconds = 600; // 默认全量快照间隔，单位秒
    private int defaultSpeed = 1; // 默认播放倍速
    private long tickIntervalMs = 1000L; // 定时推进间隔，单位毫秒
    private int maxWorkerThreads = 8; // 后端任务线程池大小
}
