package com.example.plandeduce.config;

import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.util.LinkedHashMap;
import java.util.Map;

@Configuration
public class DataSourceConfig {
    @Bean
    @Primary
    public DataSource dataSource(DataSourceProperties properties,
                                 DynamicDataSourceProperties dynamicDataSourceProperties) {
        DataSource defaultDataSource = buildDataSource(
                properties.getDriverClassName(),
                properties.getUrl(),
                properties.getUsername(),
                properties.getPassword()
        );

        Map<Object, Object> targetDataSources = new LinkedHashMap<Object, Object>();
        targetDataSources.put(dynamicDataSourceProperties.getDefaultKey(), defaultDataSource);

        for (Map.Entry<String, DynamicDataSourceProperties.DataSourceItem> entry : dynamicDataSourceProperties.getDatasources().entrySet()) {
            DynamicDataSourceProperties.DataSourceItem item = entry.getValue();
            targetDataSources.put(entry.getKey(), buildDataSource(
                    item.getDriverClassName(),
                    item.getUrl(),
                    item.getUsername(),
                    item.getPassword()
            ));
        }

        DynamicRoutingDataSource routingDataSource = new DynamicRoutingDataSource();
        routingDataSource.setDefaultTargetDataSource(defaultDataSource);
        routingDataSource.setTargetDataSources(targetDataSources);
        routingDataSource.afterPropertiesSet();
        return routingDataSource;
    }

    private DataSource buildDataSource(String driverClassName,
                                       String url,
                                       String username,
                                       String password) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName(driverClassName);
        dataSource.setUrl(url);
        dataSource.setUsername(username);
        dataSource.setPassword(password);
        return dataSource;
    }
}
