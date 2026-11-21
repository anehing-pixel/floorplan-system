package com.company.floorplan.web;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 库存融资（Floorplan）核心系统启动入口。
 * <p>
 * 架构：模块化单体（逻辑拆、物理不拆）——domain / application / infrastructure / web。
 */
@SpringBootApplication(scanBasePackages = "com.company.floorplan")
public class FloorplanApplication {

    public static void main(String[] args) {
        SpringApplication.run(FloorplanApplication.class, args);
    }
}
