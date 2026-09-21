package com.itajay.superassistant;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 上下文冒烟测试。
 *
 * <p>标注在类上（而非方法上）：JUnit 6 下方法级 {@code @Disabled} 只阻止方法执行，
 * 不阻止测试类实例化，而 Spring 的 {@code postProcessTestInstance} 会在实例化阶段就
 * 加载 ApplicationContext——本测试因此仍会在干净机器上因缺少 MySQL/Milvus/MCP 而报错。
 * 类级 {@code @Disabled} 会整体跳过该容器，不触发上下文加载。
 */
@SpringBootTest
@Disabled("Requires live MySQL/Milvus/MCP infrastructure; enable integration profile to run")
class SuperAssistantApplicationTests {

    @Test
    void contextLoads() {
    }

}
