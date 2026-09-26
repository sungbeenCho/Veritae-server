package com.veritae.veritae_server.analysis;

import com.veritae.veritae_server.domain.analysisrecord.AnalysisRecord;
import com.veritae.veritae_server.domain.analysisrecord.AnalysisRecordRepository;
import com.veritae.veritae_server.media.MediaObject;
import com.veritae.veritae_server.media.MediaObjectNotFoundException;
import com.veritae.veritae_server.media.MediaRangeNotSatisfiableException;
import com.veritae.veritae_server.media.MediaStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * 분석 원본 파일 관리 - 저장, 회원당 최신 10건 보관, 다운로드, 탈퇴 시 삭제.
 *
 * <p>저장소 key 는 "media/{회원id}/{기록id}" 다. 회원별 폴더로 두는 이유는 탈퇴 때 그 폴더를 통째로
 * 지우면, DB 에 위치가 기록되지 못한 원본(저장 직후 DB 저장이 실패한 경우 등)까지 빠짐없이 지워지기 때문이다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnalysisMediaService {

    // 앱 기록 목록이 최신 10건만 보여주므로 원본도 그만큼만 보관한다. 분석 결과(기록)는 리포트 누적
    // 통계 때문에 지우지 않는다.
    static final int RETAINED_ORIGINALS_PER_MEMBER = 10;

    private final MediaStorage mediaStorage;
    private final AnalysisRecordRepository analysisRecordRepository;

    /**
     * 원본을 저장하고 저장 위치(key)를 돌려준다. 저장소가 꺼져 있거나 저장에 실패하면 null - 원본 보관은
     * 부가 기능이라, 저장 실패 때문에 이미 끝난 분석 결과까지 실패시키지 않는다(원본만 없는 기록이 된다).
     */
    public String uploadOriginal(UUID memberId, UUID recordId, byte[] bytes, String contentType) {
        if (!mediaStorage.enabled()) {
            return null;
        }
        String key = keyOf(memberId, recordId);
        try {
            mediaStorage.put(key, bytes, contentType);
            return key;
        } catch (RuntimeException e) {
            log.warn("분석 원본 저장 실패 - 원본 없이 기록만 남깁니다. recordId={}", recordId, e);
            return null;
        }
    }

    /**
     * 동기 분석(이미지/음성)의 기록을 원본과 함께 저장한다: 원본 저장 → 기록 저장 → 최신 10건 정리.
     * 원본은 올라갔는데 기록 저장이 실패하면 그 원본은 어떤 기록도 가리키지 않아 받을 수도 정리할 수도
     * 없으므로 바로 지우고, 저장 실패는 그대로 던진다(영상 처리 VideoAnalysisAsyncWorker 와 같은 원칙).
     */
    public void saveRecordWithOriginal(AnalysisRecord record, byte[] bytes, String contentType) {
        String mediaKey = uploadOriginal(record.getMemberId(), record.getId(), bytes, contentType);
        if (mediaKey != null) {
            record.attachMedia(mediaKey);
        }
        try {
            analysisRecordRepository.save(record);
        } catch (RuntimeException e) {
            if (mediaKey != null) {
                deleteOriginal(mediaKey);
            }
            throw e;
        }
        if (mediaKey != null) {
            enforceRetention(record.getMemberId());
        }
    }

    /** 회원의 원본이 10건을 넘으면 오래된 것부터 원본만 지운다(기록은 유지). */
    public void enforceRetention(UUID memberId) {
        List<AnalysisRecord> withMedia =
                analysisRecordRepository.findByMemberIdAndMediaKeyIsNotNullOrderByCreatedAtDesc(memberId);
        for (AnalysisRecord record : withMedia.stream().skip(RETAINED_ORIGINALS_PER_MEMBER).toList()) {
            try {
                // 파일을 먼저 지우고 DB 위치를 지운다. 반대 순서면 중간에 멈췄을 때 DB엔 위치가 없는데
                // 파일은 남아, 다시는 찾아서 지울 수 없는 파일이 된다.
                mediaStorage.delete(record.getMediaKey());
                analysisRecordRepository.clearMediaKey(record.getId());
            } catch (RuntimeException e) {
                // 다음 저장 때 다시 10건 초과분을 계산하므로 여기서 멈추지 않고 넘어간다.
                log.warn("오래된 원본 정리 실패 recordId={}", record.getId(), e);
            }
        }
    }

    /**
     * 본인 기록의 원본을 연다.
     *
     * @throws AnalysisRecordNotFoundException 기록이 없거나 본인 것이 아님
     * @throws MediaNotAvailableException 기록은 있지만 원본이 없음
     * @throws RangeNotSatisfiableException Range 가 파일 크기를 벗어남
     */
    public MediaObject openOriginal(UUID recordId, UUID requesterId, String range) {
        AnalysisRecord record = analysisRecordRepository.findById(recordId)
                .filter(r -> r.getMemberId().equals(requesterId))
                .orElseThrow(AnalysisRecordNotFoundException::new);
        if (!record.hasMedia()) {
            throw new MediaNotAvailableException();
        }
        try {
            return mediaStorage.get(record.getMediaKey(), range);
        } catch (MediaObjectNotFoundException e) {
            throw new MediaNotAvailableException();
        } catch (MediaRangeNotSatisfiableException e) {
            throw new RangeNotSatisfiableException();
        }
    }

    /** 방금 올렸지만 기록에 연결하지 못한 원본을 지운다(예: 분석 중 회원이 탈퇴해 기록이 사라진 경우). */
    public void deleteOriginal(String key) {
        mediaStorage.delete(key);
    }

    /** 회원 탈퇴 - 그 회원 폴더의 원본을 전부 지운다. */
    public void deleteAllOriginals(UUID memberId) {
        mediaStorage.deleteByPrefix("media/" + memberId + "/");
    }

    private static String keyOf(UUID memberId, UUID recordId) {
        return "media/" + memberId + "/" + recordId;
    }
}
