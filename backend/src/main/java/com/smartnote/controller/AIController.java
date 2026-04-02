package com.smartnote.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartnote.dto.AIChatRequest;
import com.smartnote.service.AIService;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api/ai")
public class AIController {

    private final AIService aiService;
    private final ObjectMapper objectMapper;

    public AIController(AIService aiService, ObjectMapper objectMapper) {
        this.aiService = aiService;
        this.objectMapper = objectMapper;
    }

    /**
     * 流式 AI 对话接口
     */
    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chat(@RequestBody AIChatRequest request, Authentication authentication) {
        String username = authentication.getName();
        AIService.ChatSession chatSession = aiService.chat(
                request.getMessage(),
                request.getCurrentNoteId(),
                request.getHistory(),
                username
        );

        Flux<ServerSentEvent<String>> sourcesEvent = Flux.just(
                ServerSentEvent.<String>builder(serialize(chatSession.sources()))
                        .event("sources")
                        .build()
        );

        Flux<ServerSentEvent<String>> chunkEvents = chatSession.chunks()
                .map((chunk) -> ServerSentEvent.<String>builder(chunk)
                        .event("chunk")
                        .build());

        Flux<ServerSentEvent<String>> doneEvent = Flux.just(
                ServerSentEvent.<String>builder("[DONE]")
                        .event("done")
                        .build()
        );

        return Flux.concat(sourcesEvent, chunkEvents, doneEvent);
    }

    private String serialize(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize AI chat metadata", exception);
        }
    }
}
