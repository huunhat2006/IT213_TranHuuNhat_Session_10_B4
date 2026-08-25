package com.rikkeipay.service;

import io.langfuse.client.LangfuseClient;
import io.langfuse.client.model.Generation;
import io.langfuse.client.model.Trace;
import io.langfuse.client.model.Usage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Service minh họa việc ghi nhận Token Usage và Chi phí thủ công sang Langfuse
 * dành cho các mô hình Custom/On-premise (DeepSeek-V3 Self-hosted, vLLM, Ollama)
 * hoặc các API không tự động trả về trường Token Usage trong response metadata.
 */
@Service
public class CustomModelTokenTrackingService {

    private static final Logger log = LoggerFactory.getLogger(CustomModelTokenTrackingService.class);
    private final LangfuseClient langfuseClient;

    public CustomModelTokenTrackingService(LangfuseClient langfuseClient) {
        this.langfuseClient = langfuseClient;
    }

    /**
     * Thực thi gọi mô hình tùy biến và gửi chi tiết số lượng Token thủ công sang Langfuse.
     *
     * @param userId Định danh khách hàng
     * @param systemPrompt System instruction
     * @param userPrompt Câu lệnh người dùng
     * @param modelName Tên định danh mô hình (vd: "deepseek-v3-custom" hoặc "gemini-2.5-flash")
     */
    public String executeCustomModelWithManualUsage(String userId,
                                                   String systemPrompt,
                                                   String userPrompt,
                                                   String modelName) {
        String sessionId = "session-" + UUID.randomUUID();
        Instant startTime = Instant.now();

        // 1. Khởi tạo Trace trên Langfuse
        Trace trace = langfuseClient.trace(new Trace()
                .name("custom-llm-execution")
                .userId(userId)
                .sessionId(sessionId)
                .input(Map.of("system", systemPrompt, "user", userPrompt)));

        try {
            // 2. Tạo đối tượng Generation trực thuộc Trace
            Generation generation = trace.generation(new Generation()
                    .name("llm-completion-step")
                    .model(modelName)
                    .modelParameters(Map.of("temperature", 0.2, "max_tokens", 500))
                    .input(userPrompt)
                    .startTime(startTime));

            // 3. Thực hiện gọi LLM thực tế (Giả lập gọi Custom Model / REST API)
            log.info("Calling Custom LLM Model: [{}] for user: [{}]", modelName, userId);
            String generatedResponse = callCustomLlmEndpoint(systemPrompt, userPrompt, modelName);
            Instant endTime = Instant.now();

            // 4. Tính toán số lượng Token thủ công (Sử dụng Tokenizer hoặc thuật toán ước tính)
            int promptTokens = estimateTokenCount(systemPrompt + " " + userPrompt);
            int completionTokens = estimateTokenCount(generatedResponse);
            int totalTokens = promptTokens + completionTokens;

            log.info("Calculated Token Usage - Prompt: {}, Completion: {}, Total: {}", 
                     promptTokens, completionTokens, totalTokens);

            // 5. Gửi thủ công đối tượng Usage sang Langfuse Generation
            Usage manualUsage = new Usage()
                    .promptTokens(promptTokens)
                    .completionTokens(completionTokens)
                    .totalTokens(totalTokens);

            generation.endTime(endTime)
                    .output(generatedResponse)
                    .usage(manualUsage)
                    .metadata(Map.of(
                            "token_calculation_method", "custom_bpe_tokenizer",
                            "execution_engine", "vLLM-Inference-Server",
                            "cost_center", "RikkeiPay-AI-Banking"
                    ));

            // 6. Cập nhật output cho toàn bộ Trace
            trace.output(Map.of("status", "SUCCESS", "response", generatedResponse));
            return generatedResponse;

        } catch (Exception ex) {
            log.error("Error executing custom model: {}", ex.getMessage(), ex);
            trace.output(Map.of("status", "ERROR", "message", ex.getMessage()));
            trace.level("ERROR");
            throw new RuntimeException("Custom model execution failed", ex);
        }
    }

    /**
     * Giả lập hàm gọi API LLM thực tế.
     */
    private String callCustomLlmEndpoint(String systemPrompt, String userPrompt, String modelName) {
        // Mô phỏng độ trễ sinh từ của mô hình
        try {
            Thread.sleep(300);
        } catch (InterruptedException ignored) {}

        return "Chào bạn, giao dịch của bạn đã được tiếp nhận và xử lý an toàn bởi " + modelName;
    }

    /**
     * Hàm ước lượng số lượng token cho tiếng Việt và tiếng Anh (Fallback Tokenizer).
     * Quy tắc thông thường: 1 token ≈ 0.75 từ tiếng Anh, hoặc ~1.2 - 1.5 token / từ tiếng Việt có dấu.
     */
    private int estimateTokenCount(String text) {
        if (text == null || text.trim().isEmpty()) {
            return 0;
        }
        String[] words = text.trim().split("\\s+");
        // Hệ số nhân x1.3 đối với tiếng Việt có dấu và ký tự đặc biệt
        return (int) Math.ceil(words.length * 1.35);
    }
}
