package com.sunjin.CP_BOT.search;

import com.sunjin.CP_BOT.common.security.AuthenticatedRoles;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

/**
 * 하이브리드 검색 테스트/조회용 API. Phase 4의 채팅 API가 이 결과를 Gemini 프롬프트에 그대로 넘겨 답변을 생성한다.
 * 인증된 사용자의 역할(ROLE_ADMIN/ROLE_USER)을 그대로 RBAC 사전 필터에 사용하므로,
 * 클라이언트가 스스로 "어떤 역할로 검색할지"를 지정할 수 없다(요청 바디가 아니라 SecurityContext에서 역할을 가져옴).
 */
@RestController
@RequiredArgsConstructor
public class SearchController {

    private final HybridSearchService hybridSearchService;

    @PostMapping("/api/search")
    public List<HybridSearchService.SearchResultItem> search(
            @Valid @RequestBody SearchRequestDto requestDto,
            Authentication authentication) {

        Set<String> callerRoles = AuthenticatedRoles.extract(authentication);
        int topK = requestDto.topK() == null ? 5 : requestDto.topK();
        return hybridSearchService.search(requestDto.query(), callerRoles, topK);
    }

    public record SearchRequestDto(
            @NotBlank String query,
            @Min(1) @Max(20) Integer topK) {
    }
}
