package com.ram.trading.ai.engine.dto.news;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiPromptRequest {

    private String prompt;
}