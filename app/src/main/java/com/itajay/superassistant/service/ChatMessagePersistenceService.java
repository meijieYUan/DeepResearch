package com.itajay.superassistant.service;

import com.itajay.superassistant.rag.CustomJdbcChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;

@Service
public class ChatMessagePersistenceService {

    private final CustomJdbcChatMemoryRepository repository;

    public ChatMessagePersistenceService(CustomJdbcChatMemoryRepository repository) {
        this.repository = repository;
    }

    public void saveUserMessage(String conversationId, String content) {
        if (content == null || content.isBlank()) {
            return;
        }
        repository.appendMessage(conversationId, new UserMessage(content));
    }

    public void saveAssistantMessage(String conversationId, AssistantMessage message) {
        if (message == null || message.getText() == null || message.getText().isBlank()) {
            return;
        }
        repository.appendMessage(conversationId, message);
    }
}
