package com.ram.trading.backtesting;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class BacktestingServiceApplication {

	public static void main(String[] args) {
		// Market hours, daily resets and stored timestamps are IST; the EC2 host clock is UTC.
		java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Kolkata"));
		SpringApplication.run(BacktestingServiceApplication.class, args);
	}

}
