package com.ram.trading.watchlist;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class WatchlistServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(WatchlistServiceApplication.class, args);
	}

}
