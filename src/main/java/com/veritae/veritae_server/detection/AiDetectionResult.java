package com.veritae.veritae_server.detection;

import java.util.List;

public record AiDetectionResult(String model, double score, List<Evidence> evidence) {
}
