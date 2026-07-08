package com.ram.trading.newsanalysis.service;

import com.ram.trading.newsanalysis.client.GoogleNewsClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.List;

@Service
@RequiredArgsConstructor
public class NewsCollectionServiceImpl implements NewsCollectionService {

    private final GoogleNewsClient googleNewsClient;

    @Override
    public Mono<List<String>> collectNews(String symbol) {

        return googleNewsClient.getLatestHeadlines(symbol);

    }

}