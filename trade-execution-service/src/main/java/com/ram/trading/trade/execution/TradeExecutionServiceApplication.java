package com.ram.trading.trade.execution;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class TradeExecutionServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(TradeExecutionServiceApplication.class, args);
	}

}
