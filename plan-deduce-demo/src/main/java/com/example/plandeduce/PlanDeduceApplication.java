package com.example.plandeduce;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.example.plandeduce.mapper")
public class PlanDeduceApplication {
    public static void main(String[] args) {
        SpringApplication.run(PlanDeduceApplication.class, args);
    }
}
