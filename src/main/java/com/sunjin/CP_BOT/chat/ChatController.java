package com.sunjin.CP_BOT.chat;

import com.sunjin.CP_BOT.common.security.AuthenticatedRoles;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.Set;

/**
 * 사내 규정 Q&A 채팅 API. 검색과 마찬가지로 RBAC 필터는 인증된 사용자의 역할에서 서버가 직접 뽑아 쓴다.
 * <p>
 * {@code /api/chat}(기존, 한 번에 완성된 응답)와 {@code /api/chat/stream}(SSE 스트리밍)을 함께 둔다 -
 * CLAUDE.md에 이미 문서화된 curl 예제(`/api/chat`)가 계속 동작해야 하고, 화면(chat.js)만 스트리밍으로
 * 넘어가면 되므로 기존 엔드포인트를 건드리지 않았다.
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

    @PostMapping(value = "/api/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chatStream(
            @Valid @RequestBody ChatRequestDto requestDto,
            Authentication authentication) {

        Set<String> callerRoles = AuthenticatedRoles.extract(authentication);
        return ragChatService.askStream(requestDto.question(), callerRoles);
    }

    public record ChatRequestDto(@NotBlank String question) {
    }
}
