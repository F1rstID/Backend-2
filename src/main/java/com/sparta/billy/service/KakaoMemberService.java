package com.sparta.billy.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparta.billy.dto.MemberDto.KakaoMemberInfoDto;
import com.sparta.billy.dto.MemberDto.MemberResponseDto;
import com.sparta.billy.dto.MemberDto.TokenDto;
import com.sparta.billy.dto.ResponseDto;
import com.sparta.billy.exception.ex.MemberException.MemberNotFoundException;
import com.sparta.billy.model.Member;
import com.sparta.billy.model.UserDetailsImpl;
import com.sparta.billy.repository.MemberRepository;
import com.sparta.billy.repository.RefreshTokenRepository;
import com.sparta.billy.security.jwt.TokenProvider;
import com.sparta.billy.util.Check;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import javax.servlet.http.HttpServletResponse;
import java.util.UUID;

@RequiredArgsConstructor
@Service
public class KakaoMemberService {

    private final PasswordEncoder passwordEncoder;
    private final MemberRepository memberRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final MemberService memberService;
    private final TokenProvider tokenProvider;

    private final Check check;

    @Value("${myKaKaoRestAplKey}")
    private String myKaKaoRestAplKey;

    @Transactional
    public ResponseDto<MemberResponseDto> kakaoLogin(String code, HttpServletResponse response) throws JsonProcessingException {
        // 1. "인가 코드"로 "액세스 토큰" 요청
        String accessToken = getAccessToken(code);

        // 2. "액세스 토큰"으로 "카카오 사용자 정보" 가져오기
        KakaoMemberInfoDto kakaoMemberInfo = getKakaoMemberInfo(accessToken);

        // DB 에 중복된 Kakao Id 가 있는지 확인
        String email = kakaoMemberInfo.getEmail();
        Member kakaoMember = memberRepository.findByEmail(email)
                .orElse(null);

        if (refreshTokenRepository.findByMember(kakaoMember).isPresent()) {
            refreshTokenRepository.deleteByMember(kakaoMember);
        }
        if (kakaoMember == null) {
            // 회원가입
            String password = UUID.randomUUID().toString();
            String userId = UUID.randomUUID().toString().substring(0, 8);
            String encodedPassword = passwordEncoder.encode(password);
            String profileUrl = kakaoMemberInfo.getProfileUrl();
            String nickname = kakaoMemberInfo.getNickname();
            Long kakaoId= kakaoMemberInfo.getId();
            kakaoMember = new Member(email, userId, encodedPassword, profileUrl, nickname, kakaoId, false);

            memberRepository.save(kakaoMember);
        }

        // 4. 강제 로그인 처리
        UserDetails userDetails = new UserDetailsImpl(kakaoMember);
        Authentication authentication = new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(authentication);

        Member member = check.getMemberByUserId(kakaoMember.getUserId());
        TokenDto tokenDto = tokenProvider.generateTokenDto(member);
        tokenDto.tokenToHeaders(response);

        if (member.isDelete()) {
            throw new MemberNotFoundException();
        }

        return ResponseDto.success(
                MemberResponseDto.builder()
                        .id(member.getId())
                        .nickname(member.getNickname())
                        .userId(member.getUserId())
                        .email(member.getEmail())
                        .profileUrl(member.getProfileUrl())
                        .createdAt(member.getCreatedAt())
                        .updatedAt(member.getUpdatedAt())
                        .build()
        );
    }


    private String getAccessToken(String code) throws JsonProcessingException {
        // HTTP Header 생성
        HttpHeaders headers = new HttpHeaders();
        headers.add("Content-type", "application/x-www-form-urlencoded;charset=utf-8");

        // HTTP Body 생성
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("grant_type", "authorization_code");
        body.add("client_id", myKaKaoRestAplKey);
        body.add("redirect_uri", "https://billyproject.shop/kakao");
        body.add("code", code);

        // HTTP 요청 보내기
        HttpEntity<MultiValueMap<String, String>> kakaoTokenRequest =
                new HttpEntity<>(body, headers);
        RestTemplate rt = new RestTemplate();
        ResponseEntity<String> response = rt.exchange(
                "https://kauth.kakao.com/oauth/token",
                HttpMethod.POST,
                kakaoTokenRequest,
                String.class
        );

        // HTTP 응답 (JSON) -> 액세스 토큰 파싱
        String responseBody = response.getBody();
        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode jsonNode = objectMapper.readTree(responseBody);
        return jsonNode.get("access_token").asText();
    }

    private KakaoMemberInfoDto getKakaoMemberInfo(String accessToken) throws JsonProcessingException {
        // HTTP Header 생성
        HttpHeaders headers = new HttpHeaders();
        headers.add("Authorization", "Bearer " + accessToken);
        headers.add("Content-type", "application/x-www-form-urlencoded;charset=utf-8");

        // HTTP 요청 보내기
        HttpEntity<MultiValueMap<String, String>> kakaoMemberInfoRequest = new HttpEntity<>(headers);
        RestTemplate rt = new RestTemplate();
        ResponseEntity<String> response = rt.exchange(
                "https://kapi.kakao.com/v2/user/me",
                HttpMethod.POST,
                kakaoMemberInfoRequest,
                String.class
        );

        String responseBody = response.getBody();
        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode jsonNode = objectMapper.readTree(responseBody);
        System.out.println(responseBody);
        Long id = jsonNode.get("id").asLong();
        String nickname = jsonNode.get("kakao_account").get("profile")
                .get("nickname").asText();
        String email = jsonNode.get("kakao_account")
                .get("email").asText();
        String profilePhoto = jsonNode.get("kakao_account").get("profile").get("profile_image_url").asText();


        System.out.println("카카오 사용자 정보: " + id + ", " + nickname);
        return new KakaoMemberInfoDto(nickname, email, profilePhoto, id);
    }
}
