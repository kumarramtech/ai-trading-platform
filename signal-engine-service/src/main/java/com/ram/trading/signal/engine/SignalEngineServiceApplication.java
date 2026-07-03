package com.ram.trading.signal.engine;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class SignalEngineServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(SignalEngineServiceApplication.class, args);
	}

}
