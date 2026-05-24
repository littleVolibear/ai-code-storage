package com.example.plandeduce.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
@Configuration
@ConfigurationProperties(prefix = "plan.deduce.dynamic-datasource")
public class DynamicDataSourceProperties {
    private String defaultKey = "default";
    private Map<String, DataSourceItem> datasources = new LinkedHashMap<String, DataSourceItem>();

    @Data
    public static class DataSourceItem {
        private String driverClassName;
        private String url;
        private String username;
        private String password;
    }
}
