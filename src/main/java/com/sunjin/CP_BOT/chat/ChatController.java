package com.sunjin.CP_BOT.chat;

import com.sunjin.CP_BOT.common.security.AuthenticatedRoles;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

/**
 * 사내 규정 Q&A 채팅 API. 검색과 마찬가지로 RBAC 필터는 인증된 사용자의 역할에서 서버가 직접 뽑아 쓴다.
 */
@RestController
@RequiredArgsConstructor
public class ChatController {

    private final RagChatService ragChatService;

    @PostMapping("/api/chat")
    public RagChatService.ChatAnswer chat(
            @Valid @RequestBody ChatRequestDto requestDto,
            Authentication authentication) {

        Set<String> callerRoles = AuthenticatedRoles.extract(authentication);
        return ragChatService.ask(requestDto.question(), callerRoles);
    }

    public record ChatRequestDto(@NotBlank String question) {
    }
}
