package com.itajay.superassistant;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.itajay.superassistant.mapper")
@ConfigurationPropertiesScan
public class SuperAssistantApplication {

    public static void main(String[] args) {
        SpringApplication.run(SuperAssistantApplication.class, args);
    }

}
