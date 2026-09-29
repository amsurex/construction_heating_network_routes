package ru.lct.heat;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@OpenAPIDefinition(info = @Info(
        title = "КРОТ API",
        description = "Комплекс расчёта оптимальных трасс подключения ОКС",
        version = "1.0"))
public class HeatRoutingApplication {

    public static void main(String[] args) {
        SpringApplication.run(HeatRoutingApplication.class, args);
    }
}
