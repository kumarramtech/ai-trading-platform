package com.ram.trading.newsanalysis.client;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Component
@RequiredArgsConstructor
@Slf4j
public class AIClientImpl implements AIClient {

    private final ChatClient.Builder chatClientBuilder;

    @Override
    public Mono<String> analyze(String prompt) {

        return Mono.fromCallable(() -> {

                    log.info("Calling OpenAI...");

                    return chatClientBuilder
                            .build()
                            .prompt(prompt)
                            .call()
                            .content();

                })
                .subscribeOn(Schedulers.boundedElastic())
                .doOnSuccess(r ->
                        log.info("AI Response received."))
                .doOnError(e ->
                        log.error("AI Call Failed", e));

    }

}