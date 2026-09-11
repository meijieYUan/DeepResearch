package com.itajay.superassistant.rag;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * JDBC repository for renderable chat history.
 *
 * The table stores message text rather than a full framework serialization.
 * It is a frontend rendering fallback and must not be used to reconstruct the
 * agent's model context; graph state remains owned by the checkpoint saver.
 */
public class CustomJdbcChatMemoryRepository implements ChatMemoryRepository {

    private static final Logger log = LoggerFactory.getLogger(CustomJdbcChatMemoryRepository.class);

    private static final String TABLE_NAME = "custom_chat_memory";

    private static final String CREATE_TABLE_SQL = """
            CREATE TABLE IF NOT EXISTS custom_chat_memory (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                conversation_id VARCHAR(100) NOT NULL,
                message_type VARCHAR(20) NOT NULL,
                message_content TEXT NOT NULL,
                metadata TEXT,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                INDEX idx_custom_chat_memory_conversation (conversation_id),
                INDEX idx_custom_chat_memory_created (conversation_id, created_at)
            )
            """;

    private static final String FIND_CONVERSATION_IDS_SQL =
            "SELECT DISTINCT conversation_id FROM " + TABLE_NAME;

    private static final String FIND_BY_CONVERSATION_ID_SQL = """
            SELECT message_content, message_type FROM custom_chat_memory
            WHERE conversation_id = ? ORDER BY id ASC
            """;

    private static final String FIND_LATEST_BY_CONVERSATION_ID_SQL = """
            SELECT message_content, message_type FROM custom_chat_memory
            WHERE conversation_id = ? ORDER BY id DESC LIMIT ?
            """;

    private static final String INSERT_MESSAGE_SQL = """
            INSERT INTO custom_chat_memory
                (conversation_id, message_type, message_content, created_at)
            VALUES (?, ?, ?, ?)
            """;

    private static final String DELETE_BY_CONVERSATION_ID_SQL =
            "DELETE FROM " + TABLE_NAME + " WHERE conversation_id = ?";

    private final DataSource dataSource;

    public CustomJdbcChatMemoryRepository(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
        initTable();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private DataSource dataSource;

        public Builder dataSource(DataSource dataSource) {
            this.dataSource = dataSource;
            return this;
        }

        public CustomJdbcChatMemoryRepository build() {
            return new CustomJdbcChatMemoryRepository(dataSource);
        }
    }

    private void initTable() {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(CREATE_TABLE_SQL);
            log.info("Custom chat memory table `{}` ready", TABLE_NAME);
        } catch (SQLException e) {
            log.error("Failed to initialize custom chat memory table", e);
            throw new IllegalStateException("Failed to initialize custom chat memory table", e);
        }
    }

    @Override
    public List<String> findConversationIds() {
        List<String> ids = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(FIND_CONVERSATION_IDS_SQL);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                ids.add(rs.getString("conversation_id"));
            }
            return ids;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to find conversation ids", e);
        }
    }

    @NotNull
    @Override
    public List<Message> findByConversationId(@NotNull String conversationId) {
        return findByConversationIdInternal(conversationId, FIND_BY_CONVERSATION_ID_SQL, null);
    }

    @Override
    public void saveAll(String conversationId, List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        Connection conn = null;
        try {
            conn = dataSource.getConnection();
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(INSERT_MESSAGE_SQL)) {
                for (Message message : messages) {
                    String content = message.getText();
                    if (content == null) {
                        continue;
                    }
                    ps.setString(1, conversationId);
                    ps.setString(2, message.getMessageType().name());
                    ps.setString(3, content);
                    ps.setTimestamp(4, Timestamp.from(Instant.now()));
                    ps.addBatch();
                }
                int[] counts = ps.executeBatch();
                conn.commit();
                log.debug("Saved {} messages to conversation `{}`", counts.length, conversationId);
            }
        } catch (SQLException e) {
            rollback(conn);
            throw new IllegalStateException(
                    "Failed to save messages for conversation `" + conversationId + "`", e);
        } finally {
            close(conn);
        }
    }

    @Override
    public void deleteByConversationId(String conversationId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(DELETE_BY_CONVERSATION_ID_SQL)) {
            ps.setString(1, conversationId);
            int deleted = ps.executeUpdate();
            log.debug("Deleted {} messages from conversation `{}`", deleted, conversationId);
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "Failed to delete messages for conversation `" + conversationId + "`", e);
        }
    }

    /**
     * Returns the newest N renderable messages in chronological order.
     */
    public List<Message> findLatestByConversationId(String conversationId, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        List<Message> messages = findByConversationIdInternal(
                conversationId, FIND_LATEST_BY_CONVERSATION_ID_SQL, limit);
        Collections.reverse(messages);
        return messages;
    }

    public void appendMessage(String conversationId, Message message) {
        if (message == null) {
            return;
        }
        saveAll(conversationId, List.of(message));
    }

    private List<Message> findByConversationIdInternal(
            String conversationId, String sql, Integer limit) {
        List<Message> messages = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, conversationId);
            if (limit != null) {
                ps.setInt(2, limit);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    messages.add(buildMessage(
                            rs.getString("message_content"),
                            rs.getString("message_type")));
                }
            }
            return messages;
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "Failed to find messages for conversation `" + conversationId + "`", e);
        }
    }

    private Message buildMessage(String content, String type) {
        if (content == null) {
            return null;
        }
        return switch (type) {
            case "ASSISTANT" -> new AssistantMessage(content);
            case "SYSTEM" -> new SystemMessage(content);
            // Tool messages are not reconstructed from text. This repository is
            // only a rendering history, not a replacement for graph checkpoints.
            default -> new UserMessage(content);
        };
    }

    private static void rollback(Connection conn) {
        if (conn == null) {
            return;
        }
        try {
            conn.rollback();
        } catch (SQLException e) {
            log.warn("Failed to roll back chat memory transaction", e);
        }
    }

    private static void close(Connection conn) {
        if (conn == null) {
            return;
        }
        try {
            conn.close();
        } catch (SQLException e) {
            log.debug("Failed to close chat memory connection", e);
        }
    }
}
