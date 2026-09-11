package com.itajay.superassistant.compact;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FileReadStateTest {

    @Test
    void extractsFilePathFromReadToolCallArguments() {
        String arguments = "{\"filePath\":\"src/main/java/Example.java\"}";
        assertThat(FileReadState.extractFilePath(arguments))
                .isEqualTo("src/main/java/Example.java");
    }

    @Test
    void acceptsFilePathVariants() {
        assertThat(FileReadState.extractFilePath("{\"file_path\":\"a/b.txt\"}")).isEqualTo("a/b.txt");
        assertThat(FileReadState.extractFilePath("{\"path\":\"c/d.txt\"}")).isEqualTo("c/d.txt");
        assertThat(FileReadState.extractFilePath("{\"other\":\"x\"}")).isNull();
        assertThat(FileReadState.extractFilePath("not json")).isNull();
        assertThat(FileReadState.extractFilePath(null)).isNull();
    }

    @Test
    void scanMessagesTracksReadAndWriteFileCalls() {
        FileReadState state = new FileReadState();

        List<Message> messages = List.of(
                new UserMessage("read file"),
                toolCall("call-1", "readFile", "{\"filePath\":\"src/a.java\"}"),
                toolCall("call-2", "writeFile", "{\"filePath\":\"src/b.java\"}"),
                toolCall("call-3", "webSearch", "{\"query\":\"hello\"}")
        );

        state.scanMessages(messages);

        assertThat(state.size()).isEqualTo(2);
        List<String> recent = state.getRecentFiles(5);
        assertThat(recent).contains("src/a.java", "src/b.java");
    }

    @Test
    void getRecentFilesReturnsNewestFirst() {
        FileReadState state = new FileReadState();
        state.recordAccess("first.java");
        state.recordAccess("second.java");
        state.recordAccess("first.java"); // touch again -> newest

        assertThat(state.getRecentFiles(5)).containsExactly("first.java", "second.java");
    }

    private static AssistantMessage toolCall(String id, String name, String arguments) {
        return AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(id, "function", name, arguments)))
                .build();
    }
}