//package com.codingshuttle.projects.lovable_clone.service.impl;
//
//import com.codingshuttle.projects.lovable_clone.dto.chat.StreamResponse;
//import com.codingshuttle.projects.lovable_clone.entity.*;
//import com.codingshuttle.projects.lovable_clone.enums.ChatEventType;
//import com.codingshuttle.projects.lovable_clone.enums.MessageRole;
//import com.codingshuttle.projects.lovable_clone.error.ResourceNotFoundException;
//import com.codingshuttle.projects.lovable_clone.llm.LlmResponseParser;
//import com.codingshuttle.projects.lovable_clone.llm.PromptUtils;
//import com.codingshuttle.projects.lovable_clone.llm.advisors.FileTreeContextAdvisor;
//import com.codingshuttle.projects.lovable_clone.repository.*;
//import com.codingshuttle.projects.lovable_clone.security.AuthUtil;
//import com.codingshuttle.projects.lovable_clone.service.AiGenerationService;
//import com.codingshuttle.projects.lovable_clone.service.ProjectFileService;
//import com.codingshuttle.projects.lovable_clone.service.UsageService;
//import lombok.RequiredArgsConstructor;
//import lombok.extern.slf4j.Slf4j;
//import org.springframework.ai.chat.client.ChatClient;
//import org.springframework.ai.chat.metadata.Usage;
//import org.springframework.security.access.prepost.PreAuthorize;
//import org.springframework.stereotype.Service;
//import reactor.core.publisher.Flux;
//import reactor.core.scheduler.Schedulers;
//
//import java.util.List;
//import java.util.Map;
//import java.util.concurrent.atomic.AtomicBoolean;
//import java.util.concurrent.atomic.AtomicReference;
//
//@Service
//@RequiredArgsConstructor
//@Slf4j
//public class AiGenerationServiceImpl implements AiGenerationService {
//
//    private final ChatClient chatClient;
//    private final AuthUtil authUtil;
//    private final ProjectFileService projectFileService;
//    private final FileTreeContextAdvisor fileTreeContextAdvisor;
//    private final ChatSessionRepository chatSessionRepository;
//    private final ProjectRepository projectRepository;
//    private final LlmResponseParser llmResponseParser;
//    private final UserRepository userRepository;
//    private final ChatMessageRepository chatMessageRepository;
//    private final ChatEventRepository chatEventRepository;
//    private final UsageService usageService;
//
//    @Override
//    @PreAuthorize("@security.canEditProject(#projectId)")
//    public Flux<StreamResponse> streamResponse(String userMessage, Long projectId) {
//
//        Long userId = authUtil.getCurrentUserId();
//        ChatSession chatSession = createChatSessionIfNotExists(projectId, userId);
//
//        Map<String, Object> advisorParams = Map.of(
//                "userId", userId,
//                "projectId", projectId
//        );
//
//        StringBuilder fullResponseBuffer = new StringBuilder();
//
//        AtomicReference<Long> startTime = new AtomicReference<>(System.currentTimeMillis());
//        AtomicReference<Long> endTime = new AtomicReference<>(0L);
//        AtomicReference<Usage> usageRef = new AtomicReference<>();
//        AtomicBoolean isThinking = new AtomicBoolean(true); // reasoning chal raha hai
//
//        return chatClient.prompt()
//                .system(PromptUtils.CODE_GENERATION_SYSTEM_PROMPT)
//                .user(userMessage)
//                .advisors(advisorSpec -> {
//                    advisorSpec.params(advisorParams);
//                    advisorSpec.advisors(fileTreeContextAdvisor);
//                })
//                .stream()
//                .chatResponse()
//                .doOnNext(response -> {
//                    String content = response.getResult().getOutput().getText();
//
//                    // Pehla non-empty chunk aaya → reasoning khatam, actual response shuru
//                    if (content != null && !content.isEmpty() && endTime.get() == 0) {
//                        endTime.set(System.currentTimeMillis());
//                        isThinking.set(false);
//                    }
//
//                    if (response.getMetadata().getUsage() != null) {
//                        usageRef.set(response.getMetadata().getUsage());
//                    }
//
//                    // Sirf actual content buffer mein rakho, reasoning nahi
//                    if (content != null && !isThinking.get()) {
//                        fullResponseBuffer.append(content);
//                    }
//                })
//                .doOnComplete(() -> {
//                    Schedulers.boundedElastic().schedule(() -> {
//                        long end = endTime.get();
//                        long duration = end > 0 ? (end - startTime.get()) / 1000 : 0;
//
//                        String fullText = fullResponseBuffer.toString();
//                        if (fullText.isBlank()) {
//                            log.warn("Empty LLM response for projectId={}, skipping finalize", projectId);
//                            return;
//                        }
//
//                        finalizeChats(userMessage, chatSession, fullText, duration, usageRef.get());
//                    });
//                })
//                .doOnError(error -> log.error("Error during streaming for projectId: {}", projectId, error))
//                .map(response -> {
//                    // Reasoning chal raha hai → frontend pe kuch mat bhejo
//                    if (isThinking.get()) {
//                        return new StreamResponse("");
//                    }
//                    String text = response.getResult().getOutput().getText();
//                    return new StreamResponse(text != null ? text : "");
//                });
//    }
//
//    private void finalizeChats(String userMessage, ChatSession chatSession,
//                               String fullText, Long duration, Usage usage) {
//        Long projectId = chatSession.getProject().getId();
//
//        if (usage != null) {
//            usageService.recordTokenUsage(chatSession.getUser().getId(), usage.getTotalTokens());
//        }
//
//        int promptTokens = usage != null ? usage.getPromptTokens() : 0;
//        int completionTokens = usage != null ? usage.getCompletionTokens() : 0;
//
//        // User message save karo
//        chatMessageRepository.save(
//                ChatMessage.builder()
//                        .chatSession(chatSession)
//                        .role(MessageRole.USER)
//                        .content(userMessage)
//                        .tokensUsed(promptTokens)
//                        .build()
//        );
//
//        // Assistant message save karo
//        ChatMessage assistantChatMessage = ChatMessage.builder()
//                .role(MessageRole.ASSISTANT)
//                .content("Assistant Message here...")
//                .chatSession(chatSession)
//                .tokensUsed(completionTokens)
//                .build();
//
//        assistantChatMessage = chatMessageRepository.save(assistantChatMessage);
//
//        // Parse events from LLM response
//        List<ChatEvent> chatEventList = llmResponseParser.parseChatEvents(fullText, assistantChatMessage);
//
//        // Thought event sabse pehle add karo
//        chatEventList.addFirst(
//                ChatEvent.builder()
//                        .type(ChatEventType.THOUGHT)
//                        .chatMessage(assistantChatMessage)
//                        .content("Thought for " + duration + "s")
//                        .sequenceOrder(0)
//                        .build()
//        );
//
//        // File edits save karo MinIO mein
//        chatEventList.stream()
//                .filter(e -> e.getType() == ChatEventType.FILE_EDIT)
//                .forEach(e -> projectFileService.saveFile(projectId, e.getFilePath(), e.getContent()));
//
//        // Sab events DB mein save karo
//        chatEventRepository.saveAll(chatEventList);
//
//        log.info("✅ Chat finalized for projectId={}, events={}", projectId, chatEventList.size());
//    }
//
//    private ChatSession createChatSessionIfNotExists(Long projectId, Long userId) {
//        ChatSessionId chatSessionId = new ChatSessionId(projectId, userId);
//        ChatSession chatSession = chatSessionRepository.findById(chatSessionId).orElse(null);
//
//        if (chatSession == null) {
//            Project project = projectRepository.findById(projectId)
//                    .orElseThrow(() -> new ResourceNotFoundException("Project", projectId.toString()));
//            User user = userRepository.findById(userId)
//                    .orElseThrow(() -> new ResourceNotFoundException("User", userId.toString()));
//
//            chatSession = ChatSession.builder()
//                    .id(chatSessionId)
//                    .project(project)
//                    .user(user)
//                    .build();
//
//            chatSession = chatSessionRepository.save(chatSession);
//        }
//        return chatSession;
//    }
//}
package com.codingshuttle.projects.lovable_clone.service.impl;

import com.codingshuttle.projects.lovable_clone.dto.chat.StreamResponse;
import com.codingshuttle.projects.lovable_clone.entity.*;
import com.codingshuttle.projects.lovable_clone.enums.ChatEventType;
import com.codingshuttle.projects.lovable_clone.enums.MessageRole;
import com.codingshuttle.projects.lovable_clone.error.ResourceNotFoundException;
import com.codingshuttle.projects.lovable_clone.llm.LlmResponseParser;
import com.codingshuttle.projects.lovable_clone.llm.PromptUtils;
import com.codingshuttle.projects.lovable_clone.llm.advisors.FileTreeContextAdvisor;
import com.codingshuttle.projects.lovable_clone.repository.*;
import com.codingshuttle.projects.lovable_clone.security.AuthUtil;
import com.codingshuttle.projects.lovable_clone.service.AiGenerationService;
import com.codingshuttle.projects.lovable_clone.service.ProjectFileService;
import com.codingshuttle.projects.lovable_clone.service.UsageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Service
@RequiredArgsConstructor
@Slf4j
public class AiGenerationServiceImpl implements AiGenerationService {

    private final ChatClient chatClient;
    private final AuthUtil authUtil;
    private final ProjectFileService projectFileService;
    private final FileTreeContextAdvisor fileTreeContextAdvisor;
    private final ChatSessionRepository chatSessionRepository;
    private final ProjectRepository projectRepository;
    private final LlmResponseParser llmResponseParser;
    private final UserRepository userRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final ChatEventRepository chatEventRepository;
    private final UsageService usageService;

    @Override
    @PreAuthorize("@security.canEditProject(#projectId)")
    public Flux<StreamResponse> streamResponse(String userMessage, Long projectId) {

        Long userId = authUtil.getCurrentUserId();
        ChatSession chatSession = createChatSessionIfNotExists(projectId, userId);

        Map<String, Object> advisorParams = Map.of(
                "userId", userId,
                "projectId", projectId
        );

        StringBuilder fullResponseBuffer = new StringBuilder();

        AtomicReference<Long> startTime = new AtomicReference<>(System.currentTimeMillis());
        AtomicReference<Long> endTime = new AtomicReference<>(0L);
        AtomicReference<Usage> usageRef = new AtomicReference<>();
        AtomicBoolean isThinking = new AtomicBoolean(true); // reasoning chal raha hai

        return chatClient.prompt()
                .system(PromptUtils.CODE_GENERATION_SYSTEM_PROMPT)
                .user(userMessage)
                .advisors(advisorSpec -> {
                    advisorSpec.params(advisorParams);
                    advisorSpec.advisors(fileTreeContextAdvisor);
                })
                .stream()
                .chatResponse()
                .doOnNext(response -> {
                    String content = response.getResult().getOutput().getText();

                    // Pehla non-empty chunk aaya → reasoning khatam, actual response shuru
                    if (content != null && !content.isEmpty() && endTime.get() == 0) {
                        endTime.set(System.currentTimeMillis());
                        isThinking.set(false);
                    }

                    if (response.getMetadata().getUsage() != null) {
                        usageRef.set(response.getMetadata().getUsage());
                    }

                    // Sirf actual content buffer mein rakho, reasoning nahi
                    if (content != null && !isThinking.get()) {
                        fullResponseBuffer.append(content);
                    }
                })
                .doOnComplete(() -> {
                    Schedulers.boundedElastic().schedule(() -> {
                        long end = endTime.get();
                        long duration = end > 0 ? (end - startTime.get()) / 1000 : 0;

                        String fullText = fullResponseBuffer.toString();
                        if (fullText.isBlank()) {
                            log.warn("Empty LLM response for projectId={}, skipping finalize", projectId);
                            return;
                        }

                        finalizeChats(userMessage, chatSession, fullText, duration, usageRef.get());
                    });
                })
                .doOnError(error -> log.error("Error during streaming for projectId: {}", projectId, error))
                .map(response -> {
                    // Reasoning chal raha hai → frontend pe kuch mat bhejo
                    if (isThinking.get()) {
                        return new StreamResponse("");
                    }
                    String text = response.getResult().getOutput().getText();
                    return new StreamResponse(text != null ? text : "");
                });
    }

    private void finalizeChats(String userMessage, ChatSession chatSession,
                               String fullText, Long duration, Usage usage) {
        Long projectId = chatSession.getProject().getId();

        if (usage != null) {
            usageService.recordTokenUsage(chatSession.getUser().getId(), usage.getTotalTokens());
        }

        int promptTokens = usage != null ? usage.getPromptTokens() : 0;
        int completionTokens = usage != null ? usage.getCompletionTokens() : 0;

        // User message save karo
        chatMessageRepository.save(
                ChatMessage.builder()
                        .chatSession(chatSession)
                        .role(MessageRole.USER)
                        .content(userMessage)
                        .tokensUsed(promptTokens)
                        .build()
        );

        // Assistant message save karo
        ChatMessage assistantChatMessage = ChatMessage.builder()
                .role(MessageRole.ASSISTANT)
                .content("Assistant Message here...")
                .chatSession(chatSession)
                .tokensUsed(completionTokens)
                .build();

        assistantChatMessage = chatMessageRepository.save(assistantChatMessage);

        // Parse events from LLM response
        List<ChatEvent> chatEventList = llmResponseParser.parseChatEvents(fullText, assistantChatMessage);

        // Thought event sabse pehle add karo
        chatEventList.addFirst(
                ChatEvent.builder()
                        .type(ChatEventType.THOUGHT)
                        .chatMessage(assistantChatMessage)
                        .content("Thought for " + duration + "s")
                        .sequenceOrder(0)
                        .build()
        );

        // File edits save karo MinIO mein
        chatEventList.stream()
                .filter(e -> e.getType() == ChatEventType.FILE_EDIT)
                .forEach(e -> projectFileService.saveFile(projectId, e.getFilePath(), e.getContent()));

        // Sab events DB mein save karo
        chatEventRepository.saveAll(chatEventList);

        log.info("✅ Chat finalized for projectId={}, events={}", projectId, chatEventList.size());
    }

    private ChatSession createChatSessionIfNotExists(Long projectId, Long userId) {
        ChatSessionId chatSessionId = new ChatSessionId(projectId, userId);
        ChatSession chatSession = chatSessionRepository.findById(chatSessionId).orElse(null);

        if (chatSession == null) {
            Project project = projectRepository.findById(projectId)
                    .orElseThrow(() -> new ResourceNotFoundException("Project", projectId.toString()));
            User user = userRepository.findById(userId)
                    .orElseThrow(() -> new ResourceNotFoundException("User", userId.toString()));

            chatSession = ChatSession.builder()
                    .id(chatSessionId)
                    .project(project)
                    .user(user)
                    .build();

            chatSession = chatSessionRepository.save(chatSession);
        }
        return chatSession;
    }
}