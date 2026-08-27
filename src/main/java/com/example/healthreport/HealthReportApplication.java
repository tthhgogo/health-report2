package com.example.healthreport;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 体检报告分析服务启动入口。
 */
@SpringBootApplication
public class HealthReportApplication {

    /**
     * 启动 Spring Boot 应用。
     *
     * @param args 启动参数
     */
    public static void main(String[] args) {
        SpringApplication.run(HealthReportApplication.class, args);
    }
}
