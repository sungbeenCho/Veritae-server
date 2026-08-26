package com.veritae.veritae_server.analysis;

import com.veritae.veritae_server.common.exception.DomainException;
import org.springframework.http.HttpStatus;

import java.util.UUID;

/**
 * 존재하지 않거나 다른 회원 소유인 job을 조회하려 한 경우. 소유자가 아니어도 404로
 * 통일한다(403 대신) - 다른 사용자 job의 존재 여부 자체를 숨기기 위함.
 */
public class AnalysisJobNotFoundException extends DomainException {

    public AnalysisJobNotFoundException(UUID jobId) {
        super("ANALYSIS_JOB_NOT_FOUND", HttpStatus.NOT_FOUND, "Analysis Job Not Found",
                "분석 작업을 찾을 수 없습니다: " + jobId);
    }
}
