package com.ram.trading.ai.engine.dto.technical;

import lombok.*;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VolumeAnalysis {

    private Long current;

    private Long average;

    private String interpretation;

}