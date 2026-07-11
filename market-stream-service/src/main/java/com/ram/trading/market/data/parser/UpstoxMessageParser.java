package com.ram.trading.market.data.parser;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;

@Slf4j
@Component
public class UpstoxMessageParser {

    public void parse(ByteBuffer buffer) {

        log.info("Parsing Binary Packet...");

        log.info("Packet Size : {} bytes",
                buffer.remaining());

    }

}