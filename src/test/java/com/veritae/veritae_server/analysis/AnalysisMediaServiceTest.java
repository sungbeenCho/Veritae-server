package com.veritae.veritae_server.analysis;

import com.veritae.veritae_server.domain.analysisrecord.AnalysisRecord;
import com.veritae.veritae_server.domain.analysisrecord.AnalysisRecordRepository;
import com.veritae.veritae_server.domain.analysisrecord.Modality;
import com.veritae.veritae_server.media.InMemoryMediaStorage;
import com.veritae.veritae_server.media.MediaObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnalysisMediaServiceTest {

    @Mock
    private AnalysisRecordRepository analysisRecordRepository;

    private InMemoryMediaStorage storage;
    private AnalysisMediaService service;

    @BeforeEach
    void setUp() {
        storage = new InMemoryMediaStorage();
        service = new AnalysisMediaService(storage, analysisRecordRepository);
    }

    @Test
    void uploadOriginal_shouldStoreUnderMemberFolderAndReturnKey() {
        UUID memberId = UUID.randomUUID();
        UUID recordId = UUID.randomUUID();

        String key = service.uploadOriginal(memberId, recordId, "bytes".getBytes(), "image/jpeg");

        assertThat(key).isEqualTo("media/" + memberId + "/" + recordId);
        assertThat(storage.objects()).containsKey(key);
    }

    @Test
    void uploadOriginal_whenStorageDisabled_shouldReturnNullWithoutUploading() {
        storage.disable();

        String key = service.uploadOriginal(UUID.randomUUID(), UUID.randomUUID(), "bytes".getBytes(), "image/jpeg");

        assertThat(key).isNull();
        assertThat(storage.objects()).isEmpty();
    }

    @Test
    void uploadOriginal_whenUploadFails_shouldReturnNullInsteadOfFailingTheAnalysis() {
        storage.failOnPut();

        String key = service.uploadOriginal(UUID.randomUUID(), UUID.randomUUID(), "bytes".getBytes(), "image/jpeg");

        assertThat(key).isNull();
    }

    @Test
    void saveRecordWithOriginal_shouldAttachKeySaveRecordAndEnforceRetention() {
        UUID memberId = UUID.randomUUID();
        AnalysisRecord record = AnalysisRecord.completedSync(memberId, Modality.IMAGE, "{}", 0.1, null);

        service.saveRecordWithOriginal(record, "bytes".getBytes(), "image/jpeg");

        assertThat(record.getMediaKey()).isEqualTo("media/" + memberId + "/" + record.getId());
        assertThat(storage.objects()).containsKey(record.getMediaKey());
        verify(analysisRecordRepository).save(record);
        verify(analysisRecordRepository).findByMemberIdAndMediaKeyIsNotNullOrderByCreatedAtDesc(memberId);
    }

    @Test
    void saveRecordWithOriginal_whenRecordSaveFails_shouldDeleteUploadedOriginalAndRethrow() {
        // 원본은 올라갔는데 기록 저장이 실패하면, 그 원본은 어떤 기록도 가리키지 않아 받을 수도
        // 정리할 수도 없는 파일이 된다 - 바로 지워야 한다(영상 처리와 같은 원칙).
        UUID memberId = UUID.randomUUID();
        AnalysisRecord record = AnalysisRecord.completedSync(memberId, Modality.IMAGE, "{}", 0.1, null);
        when(analysisRecordRepository.save(record)).thenThrow(new IllegalStateException("DB 저장 실패(테스트)"));

        assertThatThrownBy(() -> service.saveRecordWithOriginal(record, "bytes".getBytes(), "image/jpeg"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(storage.objects()).isEmpty();
        verify(analysisRecordRepository, never()).findByMemberIdAndMediaKeyIsNotNullOrderByCreatedAtDesc(any());
    }

    @Test
    void saveRecordWithOriginal_whenStorageDisabled_shouldSaveRecordWithoutMediaAndSkipRetention() {
        storage.disable();
        UUID memberId = UUID.randomUUID();
        AnalysisRecord record = AnalysisRecord.completedSync(memberId, Modality.IMAGE, "{}", 0.1, null);

        service.saveRecordWithOriginal(record, "bytes".getBytes(), "image/jpeg");

        assertThat(record.getMediaKey()).isNull();
        verify(analysisRecordRepository).save(record);
        verify(analysisRecordRepository, never()).findByMemberIdAndMediaKeyIsNotNullOrderByCreatedAtDesc(any());
    }

    @Test
    void enforceRetention_withMoreThanTenOriginals_shouldDeleteOnlyTheOldestOnesAndKeepTheirRecords() {
        UUID memberId = UUID.randomUUID();
        List<AnalysisRecord> newestFirst = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            AnalysisRecord record = AnalysisRecord.completedSync(memberId, Modality.IMAGE, "{}", 0.1, null);
            String key = service.uploadOriginal(memberId, record.getId(), new byte[]{1}, "image/jpeg");
            record.attachMedia(key);
            newestFirst.add(record);
        }
        when(analysisRecordRepository.findByMemberIdAndMediaKeyIsNotNullOrderByCreatedAtDesc(memberId))
                .thenReturn(newestFirst);

        service.enforceRetention(memberId);

        AnalysisRecord eleventh = newestFirst.get(10);
        AnalysisRecord twelfth = newestFirst.get(11);
        assertThat(storage.objects()).hasSize(10)
                .doesNotContainKeys(eleventh.getMediaKey(), twelfth.getMediaKey());
        verify(analysisRecordRepository).clearMediaKey(eleventh.getId());
        verify(analysisRecordRepository).clearMediaKey(twelfth.getId());
        verify(analysisRecordRepository, never()).delete(any());
    }

    @Test
    void enforceRetention_withTenOrFewerOriginals_shouldDeleteNothing() {
        UUID memberId = UUID.randomUUID();
        List<AnalysisRecord> records = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            AnalysisRecord record = AnalysisRecord.completedSync(memberId, Modality.IMAGE, "{}", 0.1, null);
            record.attachMedia(service.uploadOriginal(memberId, record.getId(), new byte[]{1}, "image/jpeg"));
            records.add(record);
        }
        when(analysisRecordRepository.findByMemberIdAndMediaKeyIsNotNullOrderByCreatedAtDesc(memberId))
                .thenReturn(records);

        service.enforceRetention(memberId);

        assertThat(storage.objects()).hasSize(10);
        verify(analysisRecordRepository, never()).clearMediaKey(any());
    }

    @Test
    void openOriginal_withOwnRecordWithMedia_shouldReturnStoredObject() throws Exception {
        UUID memberId = UUID.randomUUID();
        AnalysisRecord record = AnalysisRecord.completedSync(memberId, Modality.AUDIO, "{}", 0.1, null);
        record.attachMedia(service.uploadOriginal(memberId, record.getId(), "voice".getBytes(), "audio/mp4"));
        when(analysisRecordRepository.findById(record.getId())).thenReturn(Optional.of(record));

        MediaObject object = service.openOriginal(record.getId(), memberId, null);

        assertThat(object.contentType()).isEqualTo("audio/mp4");
        assertThat(object.content().readAllBytes()).isEqualTo("voice".getBytes());
    }

    @Test
    void openOriginal_withUnknownRecord_shouldThrowAnalysisRecordNotFoundException() {
        UUID recordId = UUID.randomUUID();
        when(analysisRecordRepository.findById(recordId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.openOriginal(recordId, UUID.randomUUID(), null))
                .isInstanceOf(AnalysisRecordNotFoundException.class);
    }

    @Test
    void openOriginal_withAnotherMembersRecord_shouldThrowAnalysisRecordNotFoundException() {
        UUID ownerId = UUID.randomUUID();
        AnalysisRecord record = AnalysisRecord.completedSync(ownerId, Modality.IMAGE, "{}", 0.1, null);
        record.attachMedia(service.uploadOriginal(ownerId, record.getId(), new byte[]{1}, "image/jpeg"));
        when(analysisRecordRepository.findById(record.getId())).thenReturn(Optional.of(record));

        assertThatThrownBy(() -> service.openOriginal(record.getId(), UUID.randomUUID(), null))
                .isInstanceOf(AnalysisRecordNotFoundException.class);
    }

    @Test
    void openOriginal_withRecordWithoutMedia_shouldThrowMediaNotAvailableException() {
        UUID memberId = UUID.randomUUID();
        AnalysisRecord record = AnalysisRecord.completedSync(memberId, Modality.IMAGE, "{}", 0.1, null);
        when(analysisRecordRepository.findById(record.getId())).thenReturn(Optional.of(record));

        assertThatThrownBy(() -> service.openOriginal(record.getId(), memberId, null))
                .isInstanceOf(MediaNotAvailableException.class);
    }

    @Test
    void openOriginal_whenObjectMissingInStorage_shouldThrowMediaNotAvailableException() {
        UUID memberId = UUID.randomUUID();
        AnalysisRecord record = AnalysisRecord.completedSync(memberId, Modality.IMAGE, "{}", 0.1, null);
        record.attachMedia("media/" + memberId + "/" + record.getId()); // DB엔 있지만 저장소엔 없음
        when(analysisRecordRepository.findById(record.getId())).thenReturn(Optional.of(record));

        assertThatThrownBy(() -> service.openOriginal(record.getId(), memberId, null))
                .isInstanceOf(MediaNotAvailableException.class);
    }

    @Test
    void deleteAllOriginals_shouldDeleteOnlyThatMembersFolder() {
        UUID memberId = UUID.randomUUID();
        UUID otherMemberId = UUID.randomUUID();
        service.uploadOriginal(memberId, UUID.randomUUID(), new byte[]{1}, "image/jpeg");
        service.uploadOriginal(memberId, UUID.randomUUID(), new byte[]{1}, "image/jpeg");
        String othersKey = service.uploadOriginal(otherMemberId, UUID.randomUUID(), new byte[]{1}, "image/jpeg");

        service.deleteAllOriginals(memberId);

        assertThat(storage.objects()).containsOnlyKeys(othersKey);
    }
}
