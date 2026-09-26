package com.veritae.veritae_server.analysis;

import com.veritae.veritae_server.common.exception.DomainException;
import org.springframework.http.HttpStatus;

/**
 * 존재하지 않거나 다른 회원 소유인 분석 기록. 소유자가 아니어도 404로 통일한다 - 다른 사용자
 * 기록의 존재 여부 자체를 숨기기 위함(AnalysisJobNotFoundException 과 같은 원칙).
 */
public class AnalysisRecordNotFoundException extends DomainException {

    public AnalysisRecordNotFoundException() {
        super("ANALYSIS_RECORD_NOT_FOUND", HttpStatus.NOT_FOUND, "Analysis Record Not Found",
                "분석 기록을 찾을 수 없습니다.");
    }
}
