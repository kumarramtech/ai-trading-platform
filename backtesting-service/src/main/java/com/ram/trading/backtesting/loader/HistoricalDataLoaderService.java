package com.ram.trading.backtesting.loader;

import com.ram.trading.backtesting.entity.HistoricalPrice;
import com.ram.trading.backtesting.repo.HistoricalPriceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class HistoricalDataLoaderService {

    private final HistoricalPriceRepository repository;

    public void loadData() throws Exception {

        if(repository.count() > 0){
            return;
        }

        ClassPathResource resource =
                new ClassPathResource("data/Quote-Equity-TCS-EQ-05-06-2025-05-06-2026.csv");

        try (BufferedReader reader =
                     new BufferedReader(
                             new InputStreamReader(
                                     resource.getInputStream()))) {

            String line;

            // Skip Header
            reader.readLine();

            DateTimeFormatter formatter =
                    DateTimeFormatter.ofPattern("dd-MMM-yyyy",
                            Locale.ENGLISH);

            while ((line = reader.readLine()) != null) {

                String[] columns = line.split(",");

                String dateString = columns[0];

                String closePriceString =
                        columns[7].replace(",", "");

                HistoricalPrice historicalPrice =
                        new HistoricalPrice();

                historicalPrice.setSymbol("TCS");

                historicalPrice.setTradeDate(
                        LocalDate.parse(
                                dateString,
                                formatter));

                closePriceString = closePriceString
                        .replace("\"", "")
                        .trim();

                historicalPrice.setPrice(
                        Double.parseDouble(closePriceString));

                repository.save(historicalPrice);
            }
        }
    }
}