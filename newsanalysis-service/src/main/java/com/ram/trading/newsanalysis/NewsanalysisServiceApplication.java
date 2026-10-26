package com.ram.trading.newsanalysis;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class NewsanalysisServiceApplication {

	public static void main(String[] args) {
		// Market hours, daily resets and stored timestamps are IST; the EC2 host clock is UTC.
		java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Kolkata"));
		SpringApplication.run(NewsanalysisServiceApplication.class, args);
	}

}
