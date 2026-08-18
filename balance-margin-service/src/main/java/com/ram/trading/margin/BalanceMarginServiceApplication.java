package com.ram.trading.margin;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class BalanceMarginServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(BalanceMarginServiceApplication.class, args);
	}

}
