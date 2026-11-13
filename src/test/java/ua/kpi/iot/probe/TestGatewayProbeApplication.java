package ua.kpi.iot.probe;

import org.springframework.boot.SpringApplication;

public class TestGatewayProbeApplication {

	public static void main(String[] args) {
		SpringApplication.from(GatewayProbeApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
