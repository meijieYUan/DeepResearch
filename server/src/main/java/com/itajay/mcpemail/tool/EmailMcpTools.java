package com.itajay.mcpemail.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;


/*
自动配置将自动检测并注册来自以下来源的所有工具回调：
    单个 ToolCallback bean    ToolCallback bean 列表  ToolCallbackProvider bean
 */
@Component
public class EmailMcpTools {

    private static final Logger log = LoggerFactory.getLogger(EmailMcpTools.class);

    /** 共享的 ObjectMapper：线程安全，构造一次即可，避免每次调用都 new。 */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** 简单邮箱格式校验：非空白@非空白.非空白。仅做基础防呆，不追求完整 RFC 校验。 */
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    /** 对外统一的失败话术：不回传底层异常细节，细节只写服务端日志。 */
    private static final String GENERIC_FAILURE = "Error: failed to send email. Please check the server logs for details.";

    private final JavaMailSender mailSender;
    private final String senderAddress;

    public EmailMcpTools(JavaMailSender mailSender,
                         @Value("${spring.mail.username}") String senderAddress) {
        this.mailSender = mailSender;
        this.senderAddress = senderAddress;
    }

    @Tool(description = "Send an email via SMTP. Supports plain text and HTML, multiple recipients (comma-separated), optional CC.")
    public String sendEmail(
            @ToolParam(description = "Recipient email(s), comma-separated") String to,
            @ToolParam(description = "Email subject") String subject,
            @ToolParam(description = "Email body (plain text or HTML)") String body,
            @ToolParam(description = "Whether body is HTML") boolean isHtml,
            @ToolParam(description = "CC recipient(s), optional") String cc) {
        List<String> recipients;
        List<String> ccList;
        try {
            recipients = parseAddresses(to);
            if (recipients.isEmpty()) {
                return "Error: no valid recipient address provided.";
            }
            ccList = parseAddresses(cc);
        } catch (IllegalArgumentException e) {
            log.warn("sendEmail rejected an invalid recipient address: {}", e.getMessage());
            return "Error: invalid email address format.";
        }

        try {
            MimeMessage msg = mailSender.createMimeMessage();
            // 仅 HTML 正文需要 multipart；纯文本走简单消息体即可
            MimeMessageHelper h = new MimeMessageHelper(msg, isHtml, "UTF-8");
            h.setFrom(senderAddress);
            h.setTo(recipients.toArray(new String[0]));
            h.setSubject(subject);
            h.setText(body, isHtml);
            if (!ccList.isEmpty()) h.setCc(ccList.toArray(new String[0]));
            mailSender.send(msg);
            // 不记录完整收件人列表，仅记录数量，避免日志泄露收件人信息
            log.info("Email sent to {} recipient(s){}", recipients.size(),
                    ccList.isEmpty() ? "" : ", cc " + ccList.size());
            return "Email sent to " + recipients.size() + " recipient(s)"
                    + (ccList.isEmpty() ? "" : " (CC: " + ccList.size() + ")");
        } catch (Exception e) {
            log.error("sendEmail failed for {} recipient(s)", recipients.size(), e);
            return GENERIC_FAILURE;
        }
    }

    @Tool(description = "Send the same email to multiple recipients individually (batch mode).")
    public String sendEmailBatch(
            @ToolParam(description = "JSON array string: [\"a@x.com\",\"b@x.com\"]") String recipients,
            @ToolParam(description = "Email subject") String subject,
            @ToolParam(description = "Email body") String body,
            @ToolParam(description = "Whether body is HTML") boolean isHtml) {
        List<String> addresses = new ArrayList<>();
        try {
            JsonNode node = OBJECT_MAPPER.readTree(recipients);
            if (node == null || !node.isArray()) {
                return "Error: recipients must be a JSON array of email addresses.";
            }
            for (JsonNode r : node) {
                String addr = r.asText().trim();
                if (addr.isEmpty()) continue;
                if (!EMAIL_PATTERN.matcher(addr).matches()) {
                    log.warn("sendEmailBatch rejected an invalid recipient address");
                    return "Error: invalid email address format.";
                }
                addresses.add(addr);
            }
        } catch (Exception e) {
            log.warn("sendEmailBatch failed to parse recipients JSON", e);
            return "Error: recipients must be a JSON array of email addresses.";
        }
        if (addresses.isEmpty()) {
            return "Error: no valid recipient address provided.";
        }

        int ok = 0;
        int failed = 0;
        for (String addr : addresses) {
            try {
                MimeMessage msg = mailSender.createMimeMessage();
                MimeMessageHelper h = new MimeMessageHelper(msg, isHtml, "UTF-8");
                h.setFrom(senderAddress);
                h.setTo(addr);
                h.setSubject(subject);
                h.setText(body, isHtml);
                mailSender.send(msg);
                ok++;
            } catch (Exception e) {
                failed++;
                // 逐条失败原因只写服务端日志，不拼进返回给 LLM 的结果里
                log.error("sendEmailBatch failed for one recipient", e);
            }
        }
        log.info("Batch email done. Success: {}/{}", ok, addresses.size());
        return "Batch done. Success: " + ok + "/" + addresses.size()
                + (failed > 0 ? ". Failed: " + failed + " (see server logs)" : "");
    }

    /**
     * 逗号分隔的收件人字符串 → 去空白、丢弃空项、校验格式后的地址列表。
     * 单发与批量共用同一套 trim + 校验逻辑。
     *
     * @throws IllegalArgumentException 任一地址格式非法
     */
    private static List<String> parseAddresses(String raw) {
        List<String> result = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return result;
        }
        for (String part : raw.split(",")) {
            String addr = part.trim();
            if (addr.isEmpty()) continue;
            if (!EMAIL_PATTERN.matcher(addr).matches()) {
                throw new IllegalArgumentException("invalid address format");
            }
            result.add(addr);
        }
        return result;
    }
}
