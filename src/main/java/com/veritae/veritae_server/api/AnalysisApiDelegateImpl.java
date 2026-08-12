package com.veritae.veritae_server.api;

import com.veritae.veritae_server.analysis.ImageAnalysisService;
import com.veritae.veritae_server.openapi.api.AnalysisApiDelegate;
import com.veritae.veritae_server.openapi.model.ImageAnalysisResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
public class AnalysisApiDelegateImpl implements AnalysisApiDelegate {

    private final ImageAnalysisService imageAnalysisService;

    @Override
    public ResponseEntity<ImageAnalysisResponse> analyzeImage(MultipartFile file) {
        var result = imageAnalysisService.analyzeImage(file);
        return ResponseEntity.ok(AnalysisApiMapper.toResponse(result));
    }
}
