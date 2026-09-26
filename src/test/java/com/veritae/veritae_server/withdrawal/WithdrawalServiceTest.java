package com.veritae.veritae_server.withdrawal;

import com.veritae.veritae_server.analysis.AnalysisMediaService;
import com.veritae.veritae_server.domain.analysisrecord.AnalysisRecordRepository;
import com.veritae.veritae_server.domain.member.Member;
import com.veritae.veritae_server.domain.member.MemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WithdrawalServiceTest {

    @Mock
    private MemberRepository memberRepository;

    @Mock
    private AnalysisRecordRepository analysisRecordRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private AnalysisMediaService analysisMediaService;

    private WithdrawalService withdrawalService;

    @BeforeEach
    void setUp() {
        withdrawalService = new WithdrawalService(memberRepository, analysisRecordRepository, passwordEncoder,
                analysisMediaService, new TransactionTemplate(mock(PlatformTransactionManager.class)));
    }

    @Test
    void withdraw_withWrongPassword_shouldThrowInvalidPasswordAndDeleteNothing() {
        Member member = Member.register("user@veritae.app", "hash", "진실이");
        when(memberRepository.findById(member.getId())).thenReturn(Optional.of(member));
        when(passwordEncoder.matches("wrong", "hash")).thenReturn(false);

        assertThatThrownBy(() -> withdrawalService.withdraw(member.getId(), "wrong"))
                .isInstanceOf(InvalidPasswordException.class);
        verifyNoInteractions(analysisRecordRepository, analysisMediaService);
        org.mockito.Mockito.verify(memberRepository, org.mockito.Mockito.never()).deleteById(member.getId());
    }

    @Test
    void withdraw_withCorrectPassword_shouldDeleteOriginalsThenRecordsAndMemberThenOriginalsAgain() {
        Member member = Member.register("user@veritae.app", "hash", "진실이");
        when(memberRepository.findById(member.getId())).thenReturn(Optional.of(member));
        when(passwordEncoder.matches("veritae123", "hash")).thenReturn(true);

        withdrawalService.withdraw(member.getId(), "veritae123");

        // 원본을 먼저 지운다: 원본 삭제가 실패하면 DB는 그대로라 다시 시도할 수 있다(원본이 주인 없이
        // 남는 일이 없다). DB 삭제 뒤에 한 번 더 지우는 건, 그 사이 끝난 영상 분석이 올린 원본을 치우기 위해서다.
        InOrder order = inOrder(analysisMediaService, analysisRecordRepository, memberRepository);
        order.verify(analysisMediaService).deleteAllOriginals(member.getId());
        order.verify(analysisRecordRepository).deleteAllByMemberId(member.getId());
        order.verify(memberRepository).deleteById(member.getId());
        order.verify(analysisMediaService).deleteAllOriginals(member.getId());
    }
}
