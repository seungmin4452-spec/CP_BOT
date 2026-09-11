(function () {
    const csrfToken = document.querySelector('meta[name="_csrf"]').content;
    const csrfHeader = document.querySelector('meta[name="_csrf_header"]').content;

    // Gemini 답변은 단일 개행으로 줄을 나누는 경우가 많은데, 마크다운 기본 규칙(빈 줄 2개 이상이어야 문단 구분)대로면
    // 그게 다 한 문단으로 뭉쳐 보인다. breaks:true로 단일 개행도 <br>로 렌더링해서 보이는 그대로 줄바꿈되게 한다.
    marked.setOptions({ breaks: true, gfm: true });

    const log = document.getElementById('chat-log');
    const form = document.getElementById('composer');
    const input = document.getElementById('question');
    const sendBtn = document.getElementById('send-btn');

    input.addEventListener('input', () => {
        input.style.height = 'auto';
        input.style.height = Math.min(input.scrollHeight, 160) + 'px';
    });

    input.addEventListener('keydown', (e) => {
        if (e.key === 'Enter' && !e.shiftKey) {
            e.preventDefault();
            form.requestSubmit();
        }
    });

    function appendUserMessage(text) {
        const msg = document.createElement('div');
        msg.className = 'msg msg-user';
        const bubble = document.createElement('div');
        bubble.className = 'bubble';
        bubble.textContent = text;
        msg.appendChild(bubble);
        log.appendChild(msg);
        scrollToBottom();
    }

    function appendTypingIndicator() {
        const msg = document.createElement('div');
        msg.className = 'msg msg-assistant';
        msg.dataset.pending = 'true';
        const bubble = document.createElement('div');
        bubble.className = 'bubble typing-dots';
        bubble.innerHTML = '<span></span><span></span><span></span>';
        msg.appendChild(bubble);
        log.appendChild(msg);
        scrollToBottom();
        return msg;
    }

    // .msg는 가로(flex row) 컨테이너라 bubble/citations를 그 직속 자식으로 나란히 붙이면 옆으로 배치된다.
    // 세로로 쌓으려면 별도의 column 래퍼로 감싸야 한다. 스트리밍 중에는 bubble 내용을 델타가 올 때마다
    // 계속 다시 채워야 해서, wrapper/bubble을 미리 만들어두고 참조를 돌려준다.
    function startAssistantMessage(pendingEl) {
        pendingEl.removeAttribute('data-pending');

        const wrapper = document.createElement('div');
        wrapper.className = 'assistant-content';

        const bubble = document.createElement('div');
        bubble.className = 'bubble markdown-body';
        wrapper.appendChild(bubble);

        pendingEl.replaceChildren(wrapper);
        return { wrapper, bubble };
    }

    function renderAnswerText(bubble, answerText) {
        // DOMPurify로 살균한 뒤에만 innerHTML에 넣는다 - 검색된 문서 내용이나 사용자 질문에 프롬프트
        // 인젝션으로 마크다운/HTML이 섞여 들어와도 답변에 스크립트가 실행되지 않도록 막는 안전장치.
        bubble.innerHTML = DOMPurify.sanitize(marked.parse(answerText));
        scrollToBottom();
    }

    function renderCitations(wrapper, citations) {
        if (!citations || citations.length === 0) {
            return;
        }
        const chips = document.createElement('div');
        chips.className = 'citations';

        const label = document.createElement('span');
        label.className = 'citations-label';
        label.textContent = '출처';
        chips.appendChild(label);

        citations.forEach((c) => {
            const chip = document.createElement('span');
            chip.className = 'citation-chip';
            chip.textContent = c.pageNumber != null ? `${c.documentTitle} p.${c.pageNumber}` : c.documentTitle;
            chip.title = c.pageNumber != null ? `${c.fileName}, p.${c.pageNumber}` : c.fileName;
            chips.appendChild(chip);
        });
        wrapper.appendChild(chips);
        scrollToBottom();
    }

    function renderErrorMessage(pendingEl, text) {
        pendingEl.removeAttribute('data-pending');
        pendingEl.className = 'msg msg-error';
        const bubble = document.createElement('div');
        bubble.className = 'bubble';
        bubble.textContent = text;
        pendingEl.replaceChildren(bubble);
        scrollToBottom();
    }

    function scrollToBottom() {
        log.scrollTop = log.scrollHeight;
    }

    // fetch()의 스트리밍 응답 본문을 SSE 프레임 단위("\n\n"로 구분된 event:/data: 블록)로 잘라 반환하는
    // 제너레이터. EventSource는 커스텀 헤더(CSRF)를 실어 POST할 수 없어 fetch + ReadableStream을 직접 쓴다.
    async function* readSseEvents(response) {
        const reader = response.body.getReader();
        const decoder = new TextDecoder();
        let buffer = '';

        while (true) {
            const { value, done } = await reader.read();
            if (done) {
                break;
            }
            buffer += decoder.decode(value, { stream: true });

            let boundary;
            while ((boundary = buffer.indexOf('\n\n')) !== -1) {
                const rawEvent = buffer.slice(0, boundary);
                buffer = buffer.slice(boundary + 2);
                yield parseSseEvent(rawEvent);
            }
        }
    }

    function parseSseEvent(rawEvent) {
        let eventName = 'message';
        const dataLines = [];
        for (const line of rawEvent.split('\n')) {
            if (line.startsWith('event:')) {
                eventName = line.slice(6).trim();
            } else if (line.startsWith('data:')) {
                dataLines.push(line.slice(5).replace(/^ /, ''));
            }
        }
        return { event: eventName, data: dataLines.join('\n') };
    }

    form.addEventListener('submit', async (e) => {
        e.preventDefault();
        const question = input.value.trim();
        if (!question) {
            return;
        }

        appendUserMessage(question);
        input.value = '';
        input.style.height = 'auto';
        sendBtn.disabled = true;
        const pendingEl = appendTypingIndicator();

        try {
            const res = await fetch('/api/chat/stream', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                    [csrfHeader]: csrfToken
                },
                body: JSON.stringify({ question })
            });

            if (res.status === 401) {
                window.location.href = '/login';
                return;
            }

            if (!res.ok) {
                renderErrorMessage(pendingEl, `요청을 처리하지 못했습니다. (HTTP ${res.status})`);
                return;
            }

            let started = false;
            let answerText = '';
            let assistant = null;

            for await (const { event, data } of readSseEvents(res)) {
                if (!started) {
                    assistant = startAssistantMessage(pendingEl);
                    started = true;
                }

                if (event === 'citations') {
                    renderCitations(assistant.wrapper, JSON.parse(data));
                } else if (event === 'delta') {
                    answerText += data;
                    renderAnswerText(assistant.bubble, answerText);
                } else if (event === 'error') {
                    renderErrorMessage(pendingEl, data);
                    return;
                }
            }
        } catch (err) {
            renderErrorMessage(pendingEl, '서버에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.');
        } finally {
            sendBtn.disabled = false;
            input.focus();
        }
    });
})();
