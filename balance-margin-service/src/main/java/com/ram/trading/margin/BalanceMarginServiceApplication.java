package com.ram.trading.margin;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class BalanceMarginServiceApplication {

	public static void main(String[] args) {
		// Market hours, daily resets and stored timestamps are IST; the EC2 host clock is UTC.
		java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Kolkata"));
		SpringApplication.run(BalanceMarginServiceApplication.class, args);
	}

}
