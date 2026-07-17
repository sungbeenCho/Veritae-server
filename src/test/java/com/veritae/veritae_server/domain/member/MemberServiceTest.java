package com.veritae.veritae_server.domain.member;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MemberServiceTest {

    @Mock
    private MemberRepository memberRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    private MemberService memberService;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        memberService = new MemberService(memberRepository, passwordEncoder);
    }

    @Test
    void signup_withNewEmail_shouldEncodePasswordAndSaveMember() {
        // Given
        when(memberRepository.existsByEmail("user@veritae.app")).thenReturn(false);
        when(passwordEncoder.encode("veritae123")).thenReturn("encoded-hash");
        when(memberRepository.saveAndFlush(any(Member.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // When
        Member result = memberService.signup("user@veritae.app", "veritae123", "진실이");

        // Then
        assertThat(result.getEmail()).isEqualTo("user@veritae.app");
        assertThat(result.getPasswordHash()).isEqualTo("encoded-hash");
        assertThat(result.getNickname()).isEqualTo("진실이");
        assertThat(result.getId()).isNotNull();
        verify(memberRepository).saveAndFlush(any(Member.class));
    }

    @Test
    void signup_withAlreadyRegisteredEmail_shouldThrowEmailAlreadyExistsException() {
        // Given
        when(memberRepository.existsByEmail("user@veritae.app")).thenReturn(true);

        // When / Then
        assertThatThrownBy(() -> memberService.signup("user@veritae.app", "veritae123", "진실이"))
                .isInstanceOf(EmailAlreadyExistsException.class);
        verify(memberRepository, org.mockito.Mockito.never()).saveAndFlush(any(Member.class));
    }

    @Test
    void signup_whenConcurrentSaveViolatesUniqueEmailConstraint_shouldThrowEmailAlreadyExistsException() {
        // Given: existsByEmail 통과 이후 동시 요청이 먼저 save 되어, 이 요청의 save 가
        // DB 유니크 제약(idx_members_email) 위반으로 실패하는 TOCTOU 상황을 재현한다.
        when(memberRepository.existsByEmail("user@veritae.app")).thenReturn(false);
        when(passwordEncoder.encode("veritae123")).thenReturn("encoded-hash");
        when(memberRepository.saveAndFlush(any(Member.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));

        // When / Then
        assertThatThrownBy(() -> memberService.signup("user@veritae.app", "veritae123", "진실이"))
                .isInstanceOf(EmailAlreadyExistsException.class);
    }

    @Test
    void getById_whenMemberExists_shouldReturnMember() {
        // Given
        Member member = Member.register("user@veritae.app", "hash", "진실이");
        when(memberRepository.findById(member.getId())).thenReturn(Optional.of(member));

        // When
        Member result = memberService.getById(member.getId());

        // Then
        assertThat(result).isEqualTo(member);
    }

    @Test
    void getById_whenMemberNotFound_shouldThrowIllegalStateException() {
        // Given
        UUID id = UUID.randomUUID();
        when(memberRepository.findById(id)).thenReturn(Optional.empty());

        // When / Then
        assertThatThrownBy(() -> memberService.getById(id))
                .isInstanceOf(IllegalStateException.class);
    }
}
