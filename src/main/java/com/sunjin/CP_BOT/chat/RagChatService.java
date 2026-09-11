package com.sunjin.CP_BOT.chat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunjin.CP_BOT.search.HybridSearchService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Set;

/**
 * 검색(Phase 3) 결과를 컨텍스트로 Gemini에게 답변을 생성시키는 RAG 오케스트레이션.
 * <p>
 * Spring AI의 {@code QuestionAnswerAdvisor}/{@code RetrievalAugmentationAdvisor}는 내부적으로
 * {@code VectorStore}를 호출하는 구조인데, 이 프로젝트는 Phase 2/3에서 이유가 있어 VectorStore
 * 자동설정을 껐다(Document.metadata가 배열을 지원하지 않아 RBAC용 allowed_roles를 못 담고,
 * 네이티브 RRF는 유료 라이선스라 애플리케이션 레벨 융합을 쓰기 때문 - CLAUDE.md 참고).
 * 그래서 Advisor를 쓰지 않고 {@link HybridSearchService} 결과로 프롬프트를 직접 조립한다.
 */
@Slf4j
@Service
public class RagChatService {

    private static final int TOP_K = 5;

    private static final String NO_CONTEXT_ANSWER = "제공된 사내 규정 문서에서 관련 내용을 찾을 수 없습니다.";

    private static final String STREAM_ERROR_MESSAGE = "답변을 생성하지 못했습니다. 잠시 후 다시 시도해 주세요.";

    // HybridSearchService와 동일한 이유(Boot 4 webmvc 스타터는 ObjectMapper 빈을 자동 등록하지 않음)로
    // citations를 SSE data로 직렬화하는 용도로만 DI 없이 직접 생성한다.
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    // 출처는 답변 텍스트에 넣지 않는다 - 화면에서 답변 박스 아래에 구조화된 citations로 따로 표시한다
    // (아래 citations 조립부 참고). 답변 안에 "출처: ..." 문구가 같이 뜨면 화면에 출처가 이중으로 보인다.
    private static final String SYSTEM_PROMPT = """
            당신은 사내 규정 안내 도우미입니다. 아래 [참고 자료]에 있는 내용만 근거로 답변하십시오.
            자료에 없는 내용은 절대 추측하거나 지어내지 말고, 그런 경우에는 "제공된 사내 규정 문서에서 관련 내용을 찾을 수 없습니다."라고만 답하십시오.
            답변 텍스트에는 출처나 문서명/파일명/페이지 번호를 언급하지 마십시오. 출처는 별도 화면 영역에 따로 표시됩니다.
            """;

    private final HybridSearchService hybridSearchService;
    private final ChatClient chatClient;

    public RagChatService(HybridSearchService hybridSearchService, ChatClient.Builder chatClientBuilder) {
        this.hybridSearchService = hybridSearchService;
        this.chatClient = chatClientBuilder.build();
    }

    public ChatAnswer ask(String question, Set<String> callerRoles) {
        List<HybridSearchService.SearchResultItem> results = hybridSearchService.search(question, callerRoles, TOP_K);

        if (results.isEmpty()) {
            return new ChatAnswer(NO_CONTEXT_ANSWER, List.of());
        }

        String userMessage = """
                [참고 자료]
                %s

                [질문]
                %s
                """.formatted(buildContext(results), question);

        String answer = chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .user(userMessage)
                .call()
                .content();

        List<Citation> citations = results.stream()
                .map(r -> new Citation(r.documentTitle(), r.fileName(), r.pageNumber()))
                .distinct()
                .toList();

        return new ChatAnswer(answer, citations);
    }

    /**
     * {@link #ask}와 검색/프롬프트 조립 로직은 동일하되, Gemini 응답을 기다렸다가 한 번에 반환하는 대신
     * {@code ChatClient.stream()}으로 토큰이 생성되는 대로 SSE(Server-Sent Events)로 흘려보낸다.
     * <p>
     * 검색(ES 호출)은 여전히 블로킹이라 {@code Schedulers.boundedElastic()}에서 실행해 서블릿
     * 요청 스레드를 막지 않는다. citations는 검색 결과가 나오는 즉시 첫 이벤트("citations")로 먼저
     * 보내고(화면이 출처를 답변 완성 전에 미리 표시할 수 있음), 이어서 답변 토큰 조각을 "delta"
     * 이벤트로 순서대로 내보낸다. 검색/생성 중 오류가 나면(응답 상태 코드는 이미 200으로 커밋된 뒤라
     * {@code GlobalExceptionHandler}가 개입할 수 없으므로) "error" 이벤트로 스트림 안에서 알린다.
     */
    public Flux<ServerSentEvent<String>> askStream(String question, Set<String> callerRoles) {
        return Mono.fromCallable(() -> hybridSearchService.search(question, callerRoles, TOP_K))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(results -> streamAnswer(question, results))
                .onErrorResume(this::toErrorEvent);
    }

    private Flux<ServerSentEvent<String>> streamAnswer(String question, List<HybridSearchService.SearchResultItem> results) {
        List<Citation> citations = results.stream()
                .map(r -> new Citation(r.documentTitle(), r.fileName(), r.pageNumber()))
                .distinct()
                .toList();
        Flux<ServerSentEvent<String>> citationsEvent = Flux.just(event("citations", toJson(citations)));

        if (results.isEmpty()) {
            return citationsEvent.concatWith(Flux.just(event("delta", NO_CONTEXT_ANSWER)));
        }

        String userMessage = """
                [참고 자료]
                %s

                [질문]
                %s
                """.formatted(buildContext(results), question);

        Flux<ServerSentEvent<String>> deltas = chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .user(userMessage)
                .stream()
                .content()
                .map(chunk -> event("delta", chunk));

        return citationsEvent.concatWith(deltas);
    }

    private Flux<ServerSentEvent<String>> toErrorEvent(Throwable ex) {
        log.error("스트리밍 답변 생성 중 오류", ex);
        return Flux.just(event("error", STREAM_ERROR_MESSAGE));
    }

    private ServerSentEvent<String> event(String name, String data) {
        return ServerSentEvent.builder(data).event(name).build();
    }

    private String toJson(Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("citations 직렬화에 실패했습니다.", e);
        }
    }

    private String buildContext(List<HybridSearchService.SearchResultItem> results) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < results.size(); i++) {
            HybridSearchService.SearchResultItem r = results.get(i);
            String page = r.pageNumber() == null ? "없음(페이지 구분 없는 문서)" : String.valueOf(r.pageNumber());
            sb.append("[자료 %d] 문서: %s / 파일: %s / 페이지: %s%n%s%n%n"
                    .formatted(i + 1, r.documentTitle(), r.fileName(), page, r.content()));
        }
        return sb.toString();
    }

    public record ChatAnswer(String answer, List<Citation> citations) {
    }

    public record Citation(String documentTitle, String fileName, Integer pageNumber) {
    }
}
