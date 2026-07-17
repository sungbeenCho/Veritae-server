package com.veritae.veritae_server.domain.member;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class MemberService {

    private final MemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public Member signup(String email, String rawPassword, String nickname) {
        if (memberRepository.existsByEmail(email)) {
            throw new EmailAlreadyExistsException();
        }
        Member member = Member.register(email, passwordEncoder.encode(rawPassword), nickname);
        try {
            // saveAndFlush 로 즉시 INSERT 를 실행시켜야 한다. UUID 는 Java 에서 미리 생성되므로
            // Hibernate 가 ID 를 얻으려 flush 를 강제할 이유가 없어, 평범한 save() 는 실제 INSERT 를
            // 트랜잭션 커밋 시점까지 지연시킨다 — 그러면 유니크 제약 위반이 이 메서드가 반환된 "이후"
            // (트랜잭션 커밋 중, try/catch 밖) 던져져서 여기서 잡히지 않는다. 실제로 동시 요청 2개를
            // 붙여서 재현해보고서야 드러난 문제였다.
            return memberRepository.saveAndFlush(member);
        } catch (DataIntegrityViolationException e) {
            // existsByEmail 통과 후 save 사이의 동시 요청으로 유니크 제약(idx_members_email)이
            // 위반된 경우. TOCTOU 이므로 여기서만 잡아 409 로 변환한다.
            throw new EmailAlreadyExistsException();
        }
    }

    @Transactional(readOnly = true)
    public Member getById(UUID id) {
        return memberRepository.findById(id)
                // 유효한 JWT 가 가리키는 회원이 존재하지 않는 경우 — 이번 스펙 범위(탈퇴 없음)에서는
                // 발생하지 않아야 하는 데이터 정합성 이상 상황이다. AC-4 는 이 케이스를 정의하지 않으므로
                // GlobalExceptionHandler 의 default(500) 처리로 흘려보낸다.
                .orElseThrow(() -> new IllegalStateException("인증된 토큰의 회원을 찾을 수 없습니다: " + id));
    }
}
