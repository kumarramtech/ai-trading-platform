package com.ram.trading.ai.engine.dto.openrouter;

import lombok.Data;

import java.util.List;

@Data
public class OpenRouterResponse {

    private List<Choice> choices;
}