package com.veritae.veritae_server.analysis;

import com.veritae.veritae_server.domain.analysisrecord.AnalysisJobStatus;
import com.veritae.veritae_server.domain.analysisrecord.AnalysisRecord;
import com.veritae.veritae_server.domain.analysisrecord.AnalysisRecordRepository;
import com.veritae.veritae_server.domain.analysisrecord.Modality;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnalysisHistoryServiceTest {

    @Mock
    private AnalysisRecordRepository analysisRecordRepository;

    private AnalysisHistoryService analysisHistoryService;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        analysisHistoryService = new AnalysisHistoryService(analysisRecordRepository);
    }

    @Test
    void getRecords_shouldQueryCompletedRecordsForMemberWithPagination() {
        UUID memberId = UUID.randomUUID();
        AnalysisRecord record = AnalysisRecord.completedSync(memberId, Modality.IMAGE, "{}", 0.1, null);
        Page<AnalysisRecord> expected = new PageImpl<>(List.of(record));
        when(analysisRecordRepository.findByMemberIdAndStatusOrderByCreatedAtDesc(
                eq(memberId), eq(AnalysisJobStatus.COMPLETED), any(PageRequest.class)))
                .thenReturn(expected);

        Page<AnalysisRecord> result = analysisHistoryService.getRecords(memberId, 0, 10);

        assertThat(result.getContent()).containsExactly(record);
    }

    @Test
    void getReport_shouldAggregateCountsForMember() {
        UUID memberId = UUID.randomUUID();
        when(analysisRecordRepository.countByMemberIdAndStatus(memberId, AnalysisJobStatus.COMPLETED)).thenReturn(23L);
        when(analysisRecordRepository.countByMemberIdAndStatusAndModality(memberId, AnalysisJobStatus.COMPLETED, Modality.IMAGE)).thenReturn(10L);
        when(analysisRecordRepository.countByMemberIdAndStatusAndModality(memberId, AnalysisJobStatus.COMPLETED, Modality.AUDIO)).thenReturn(8L);
        when(analysisRecordRepository.countByMemberIdAndStatusAndModality(memberId, AnalysisJobStatus.COMPLETED, Modality.VIDEO)).thenReturn(5L);
        when(analysisRecordRepository.countByMemberIdAndStatusAndAiScoreGreaterThanEqual(memberId, AnalysisJobStatus.COMPLETED, 0.5)).thenReturn(3L);
        when(analysisRecordRepository.countByMemberIdAndStatusAndScamScoreGreaterThanEqual(memberId, AnalysisJobStatus.COMPLETED, 0.5)).thenReturn(2L);

        AnalysisHistoryService.AnalysisReportView view = analysisHistoryService.getReport(memberId);

        assertThat(view.totalCount()).isEqualTo(23);
        assertThat(view.imageCount()).isEqualTo(10);
        assertThat(view.audioCount()).isEqualTo(8);
        assertThat(view.videoCount()).isEqualTo(5);
        assertThat(view.aiDetectedCount()).isEqualTo(3);
        assertThat(view.scamDetectedCount()).isEqualTo(2);
    }
}
