package com.veritae.veritae_server.analysis;

import com.veritae.veritae_server.domain.analysisrecord.AnalysisRecord;
import com.veritae.veritae_server.domain.analysisrecord.AnalysisRecordRepository;
import com.veritae.veritae_server.domain.analysisrecord.AnalysisJobStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 서버가 비정상 종료(재배포/크래시 등)되면, 그 시점에 PENDING/PROCESSING 이던
 * AnalysisJob은 실제 처리(메모리의 바이트, 이를 처리하던 스레드)가 함께 사라졌는데도
 * DB 레코드만 그 상태로 영원히 남는다. 그러면 iOS 앱이 완료될 리 없는 job을 무한
 * 폴링하게 되므로, 앱 기동 시 이런 job들을 찾아 FAILED로 정리한다. 재개(resume)는
 * 하지 않는다 - 원본 영상 바이트가 더 이상 없어 재시도할 방법이 없기 때문이다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StaleAnalysisJobCleaner {

    private static final String STALE_JOB_ERROR_MESSAGE = "서버 재시작으로 처리가 중단됐습니다.";

    private final AnalysisRecordRepository analysisRecordRepository;

    @EventListener(ApplicationReadyEvent.class)
    public void cleanUpStaleJobs() {
        List<AnalysisRecord> staleJobs = analysisRecordRepository.findByStatusIn(
                List.of(AnalysisJobStatus.PENDING, AnalysisJobStatus.PROCESSING));

        if (staleJobs.isEmpty()) {
            return;
        }

        log.warn("서버 재시작 직전 PENDING/PROCESSING 상태로 남아있던 영상 분석 job {}건을 FAILED로 정리합니다.",
                staleJobs.size());
        for (AnalysisRecord record : staleJobs) {
            record.markFailed("ANALYSIS_FAILED", STALE_JOB_ERROR_MESSAGE);
        }
        analysisRecordRepository.saveAll(staleJobs);
    }
}
