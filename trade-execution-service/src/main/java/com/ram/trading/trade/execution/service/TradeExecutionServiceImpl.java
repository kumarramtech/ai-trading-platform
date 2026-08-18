package com.ram.trading.trade.execution.service;

import com.ram.trading.trade.execution.client.BalanceMarginClient;
import com.ram.trading.trade.execution.client.BrokerExecutionClient;
import com.ram.trading.trade.execution.dto.BrokerOrderRequest;
import com.ram.trading.trade.execution.dto.BrokerOrderResponse;
import com.ram.trading.trade.execution.dto.ReserveMarginRequest;
import com.ram.trading.trade.execution.dto.TradeExecutionRequest;
import com.ram.trading.trade.execution.dto.TradeExecutionResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
@Slf4j
public class TradeExecutionServiceImpl implements TradeExecutionService {

    private final BalanceMarginClient balanceMarginClient;

    private final BrokerExecutionClient brokerExecutionClient;

    @Override
    public Mono<TradeExecutionResponse> executeTrade(
            TradeExecutionRequest request) {

        log.info("========================================");
        log.info("TRADE EXECUTION STARTED");
        log.info(
                "Symbol={} | Signal={} | Quantity={} | EntryPrice={} | RequiredMargin={}",
                request.getSymbol(),
                request.getSignal(),
                request.getQuantity(),
                request.getEntryPrice(),
                request.getRequiredMargin()
        );

        BigDecimal requiredMargin = request.getRequiredMargin();

        ReserveMarginRequest reserveMarginRequest =
                ReserveMarginRequest.builder()
                        .symbol(request.getSymbol())
                        .requiredMargin(requiredMargin)
                        .build();

        return balanceMarginClient
                .reserveMargin(reserveMarginRequest)

                .flatMap(marginResponse -> {

                    if (!Boolean.TRUE.equals(
                            marginResponse.getSufficientBalance())) {

                        log.warn(
                                "Insufficient balance | Symbol={} | RequiredMargin={}",
                                request.getSymbol(),
                                requiredMargin
                        );

                        return Mono.just(
                                TradeExecutionResponse.builder()
                                        .success(false)
                                        .signalId(request.getSignalId())
                                        .symbol(request.getSymbol())
                                        .signal(request.getSignal())
                                        .quantity(request.getQuantity())
                                        .status("REJECTED")
                                        .message(
                                                "Insufficient balance for trade execution"
                                        )
                                        .build()
                        );
                    }

                    log.info(
                            "Margin reserved successfully | Symbol={} | Margin={}",
                            request.getSymbol(),
                            requiredMargin
                    );

                    BrokerOrderRequest brokerOrderRequest =
                            BrokerOrderRequest.builder()
                                    .symbol(request.getSymbol())
                                    .instrumentKey(request.getInstrumentKey())
                                    .signal(request.getSignal())
                                    .quantity(request.getQuantity())
                                    .price(request.getEntryPrice())
                                    .build();

                    return brokerExecutionClient
                            .executeOrder(brokerOrderRequest)

                            .map(brokerResponse ->
                                    buildTradeExecutionResponse(
                                            request,
                                            brokerResponse,
                                            requiredMargin
                                    )
                            );
                })

                .onErrorResume(error -> {

                    log.error(
                            "Trade execution failed | Symbol={}",
                            request.getSymbol(),
                            error
                    );

                    return Mono.just(
                            TradeExecutionResponse.builder()
                                    .success(false)
                                    .signalId(request.getSignalId())
                                    .symbol(request.getSymbol())
                                    .signal(request.getSignal())
                                    .quantity(request.getQuantity())
                                    .status("FAILED")
                                    .message(
                                            "Trade execution failed: "
                                                    + error.getMessage()
                                    )
                                    .build()
                    );
                });
    }

    private TradeExecutionResponse buildTradeExecutionResponse(
            TradeExecutionRequest request,
            BrokerOrderResponse brokerResponse,
            BigDecimal requiredMargin) {

        if (!brokerResponse.isSuccess()) {

            log.warn(
                    "Broker rejected order | Symbol={} | Message={}",
                    request.getSymbol(),
                    brokerResponse.getMessage()
            );

            return TradeExecutionResponse.builder()
                    .success(false)
                    .signalId(request.getSignalId())
                    .symbol(request.getSymbol())
                    .signal(request.getSignal())
                    .quantity(request.getQuantity())
                    .orderId(brokerResponse.getOrderId())
                    .status(brokerResponse.getStatus())
                    .message(brokerResponse.getMessage())
                    .build();
        }

        log.info(
                "TRADE EXECUTED SUCCESSFULLY | Symbol={} | OrderId={}",
                request.getSymbol(),
                brokerResponse.getOrderId()
        );

        return TradeExecutionResponse.builder()
                .success(true)
                .signalId(request.getSignalId())
                .symbol(request.getSymbol())
                .signal(request.getSignal())
                .quantity(request.getQuantity())
                .executedPrice(brokerResponse.getExecutedPrice())
                .orderId(brokerResponse.getOrderId())
                .status(brokerResponse.getStatus())
                .message(brokerResponse.getMessage())
                .build();
    }
}