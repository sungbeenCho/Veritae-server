package com.veritae.veritae_server.analysis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.veritae.veritae_server.detection.VideoAnalysisResult;
import com.veritae.veritae_server.detection.VideoDetectionClient;
import com.veritae.veritae_server.domain.analysisrecord.AnalysisRecord;
import com.veritae.veritae_server.domain.analysisrecord.AnalysisRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.UUID;
import java.util.function.Function;

/**
 * VideoAnalysisService.submitVideo() 가 job을 만든 뒤 이 빈의 process()를 호출해
 * 실제 탐지 서버 호출 + 상태 갱신을 비동기로 수행한다. 별도 빈으로 분리한 이유는
 * VideoAnalysisService의 클래스 주석 참고(스프링 @Async self-invocation 함정).
 *
 * <p>상태 갱신은 항상 "트랜잭션 안에서 다시 조회한 엔티티를 바꾸는" 방식으로 한다. 몇 분 걸리는 분석
 * 도중 회원이 탈퇴해 기록이 지워질 수 있는데, 처음 불러온 엔티티를 save(merge)하면 지워진 기록이
 * 다시 만들어지기 때문이다. 다시 조회해서 없으면 아무것도 저장하지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VideoAnalysisAsyncWorker {

    // resultJson은 우리가 직접 쓰고 직접 읽는 순수 내부 저장 포맷이지 iOS로 나가는 응답이 아니다
    // (그건 openapi-generator가 만든 Jackson 3 기반 DTO를 통해 나간다). 이 프로젝트는 Spring Boot 4 +
    // Jackson 3(tools.jackson) 기본 스택이라 com.fasterxml.jackson.databind.ObjectMapper 빈이
    // 자동 등록되지 않으므로, DI 대신 기본 설정 인스턴스를 직접 만들어 쓴다.
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final VideoDetectionClient videoDetectionClient;
    private final AnalysisRecordRepository analysisRecordRepository;
    private final AnalysisMediaService analysisMediaService;
    private final TransactionTemplate transactionTemplate;

    @Async("videoAnalysisExecutor")
    public void process(UUID jobId, byte[] videoBytes, String filename, String contentType) {
        UUID memberId = update(jobId, record -> {
            record.markProcessing();
            return record.getMemberId();
        });
        if (memberId == null) {
            log.info("영상 분석: 처리 시작 전에 기록이 삭제됨(회원 탈퇴 등) jobId={}", jobId);
            return;
        }

        VideoAnalysisResult result;
        try {
            result = videoDetectionClient.detectVideo(videoBytes, filename, contentType);
        } catch (Exception e) {
            // 탐지 서버(Python)가 던지는 원문 에러 메시지에는 내부 경로/traceback 일부가 섞여 나올 수 있어
            // 클라이언트(errorMessage)에 그대로 노출하지 않는다. 상세 원인은 로그에만 남기고,
            // 사용자에게는 일반화된 메시지만 전달한다. 실패한 분석은 기록 목록에 나오지 않으므로 원본도 보관하지 않는다.
            log.error("영상 분석 실패 jobId={}", jobId, e);
            update(jobId, record -> {
                record.markFailed("ANALYSIS_FAILED", "영상 분석 중 오류가 발생했습니다.");
                return true;
            });
            return;
        }

        String mediaKey = analysisMediaService.uploadOriginal(memberId, jobId, videoBytes, contentType);
        Boolean saved = update(jobId, record -> {
            complete(record, result);
            if (mediaKey != null) {
                record.attachMedia(mediaKey);
            }
            return true;
        });
        if (saved == null) {
            log.info("영상 분석: 분석 도중 기록이 삭제돼 결과를 저장하지 않음(회원 탈퇴 등) jobId={}", jobId);
            if (mediaKey != null) {
                analysisMediaService.deleteOriginal(mediaKey);
            }
            return;
        }
        if (mediaKey != null) {
            analysisMediaService.enforceRetention(memberId);
        }
    }

    private void complete(AnalysisRecord record, VideoAnalysisResult result) {
        Double aiScore = result.aiDetection() != null ? result.aiDetection().score() : null;
        Double scamScore = result.scamDetection() != null ? result.scamDetection().score() : null;
        if (result.errorCode() != null) {
            // 얼굴없음처럼 일부 판독만 정상적으로 비어있는 경우 - 사기감지 등 나머지 결과가
            // 살아있을 수 있으니 전체 실패가 아니라 부분 성공(COMPLETED)으로 기록한다(2026-09-21,
            // 예전엔 이것도 통째로 FAILED 처리돼서 살아있는 사기감지 결과까지 같이 버려졌었음).
            log.info("영상 분석: 일부 판독 불가 jobId={} errorCode={}", record.getId(), result.errorCode());
            record.markCompletedWithPartialError(
                    writeResultJson(result), aiScore, scamScore, result.errorCode(), errorMessageFor(result.errorCode()));
        } else {
            record.markCompleted(writeResultJson(result), aiScore, scamScore);
        }
    }

    /**
     * 트랜잭션 안에서 기록을 다시 조회해 바꾸고 change 의 결과를 돌려준다. 기록이 없으면(이미 삭제됨) null.
     * 조회와 커밋 사이에 삭제되면 UPDATE 가 0건이 되어 Hibernate 가 낙관적 락 예외를 던지는데, 이것도
     * "삭제됨"으로 본다(VideoAnalysisAsyncWorkerJpaTest 가 실제 DB로 이 동작을 확인한다).
     */
    private <T> T update(UUID jobId, Function<AnalysisRecord, T> change) {
        try {
            return transactionTemplate.execute(status -> analysisRecordRepository.findById(jobId)
                    .map(change)
                    .orElse(null));
        } catch (OptimisticLockingFailureException e) {
            return null;
        }
    }

    private String errorMessageFor(String errorCode) {
        if ("NO_FACE_DETECTED".equals(errorCode)) {
            return "영상에서 얼굴을 찾을 수 없어 AI판독은 제공되지 않습니다. 얼굴이 잘 보이는 영상이면 판독도 함께 받을 수 있습니다.";
        }
        return null;
    }

    private String writeResultJson(VideoAnalysisResult result) {
        try {
            return OBJECT_MAPPER.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(new IOException(e));
        }
    }
}
