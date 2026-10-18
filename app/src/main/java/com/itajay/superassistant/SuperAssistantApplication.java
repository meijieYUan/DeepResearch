package com.itajay.superassistant;

import com.itajay.superassistant.workspace.WorkspacePaths;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.itajay.superassistant.mapper")
@ConfigurationPropertiesScan
public class SuperAssistantApplication {

    public static void main(String[] args) {
        // Logback needs an absolute root before Spring initializes logging:
        // spring-boot:run starts in the app/ module, which would otherwise write
        // logs to app/logs while traces go to the repository-root .trace/.
        System.setProperty("workspace.root", WorkspacePaths.root().toString());
        SpringApplication.run(SuperAssistantApplication.class, args);
    }

}
