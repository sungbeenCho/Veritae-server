package com.veritae.veritae_server.withdrawal;

import com.veritae.veritae_server.analysis.AnalysisMediaService;
import com.veritae.veritae_server.domain.analysisrecord.AnalysisRecordRepository;
import com.veritae.veritae_server.domain.member.Member;
import com.veritae.veritae_server.domain.member.MemberRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

/**
 * 회원 탈퇴 - 비밀번호를 다시 확인한 뒤 원본 파일, 분석 기록, 회원 정보를 전부 지운다. 회원(member)과
 * 분석(analysis) 두 도메인을 함께 지우는 작업이라 어느 한쪽 서비스에 두지 않고 따로 뺐다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WithdrawalService {

    private final MemberRepository memberRepository;
    private final AnalysisRecordRepository analysisRecordRepository;
    private final PasswordEncoder passwordEncoder;
    private final AnalysisMediaService analysisMediaService;
    private final TransactionTemplate transactionTemplate;

    public void withdraw(UUID memberId, String rawPassword) {
        Member member = memberRepository.findById(memberId)
                // 로그인 필터가 존재하는 회원만 인증하므로 정상 흐름에서는 일어나지 않는다.
                .orElseThrow(() -> new IllegalStateException("인증된 토큰의 회원을 찾을 수 없습니다: " + memberId));
        if (!passwordEncoder.matches(rawPassword, member.getPasswordHash())) {
            throw new InvalidPasswordException();
        }

        // 1) 원본부터 지운다. 여기서 실패하면 DB는 그대로라 사용자가 다시 시도할 수 있다 - DB를 먼저 지우고
        //    원본 삭제가 실패하면 주인 없는 원본(개인정보)이 남는다.
        analysisMediaService.deleteAllOriginals(memberId);
        // 2) 기록과 회원을 한 트랜잭션으로 지운다.
        transactionTemplate.executeWithoutResult(status -> {
            analysisRecordRepository.deleteAllByMemberId(memberId);
            memberRepository.deleteById(memberId);
        });
        // 3) 1)과 2) 사이에 끝난 영상 분석이 원본을 올렸을 수 있어 한 번 더 지운다. 2) 이후에 끝나는 분석은
        //    기록이 없어 결과 저장이 안 되고, 올린 원본도 스스로 지운다(VideoAnalysisAsyncWorker).
        try {
            analysisMediaService.deleteAllOriginals(memberId);
        } catch (RuntimeException e) {
            // 탈퇴 자체는 이미 끝났다 - 사용자에게 실패로 돌려주지 않고, 남았을 수 있는 원본은 로그로 추적한다.
            log.error("탈퇴 후 원본 재정리 실패 - media/{}/ 아래 파일 확인 필요", memberId, e);
        }
    }
}
