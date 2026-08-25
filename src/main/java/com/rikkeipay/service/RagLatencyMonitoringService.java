package com.rikkeipay.service;

import io.langfuse.client.LangfuseClient;
import io.langfuse.client.model.Generation;
import io.langfuse.client.model.Span;
import io.langfuse.client.model.Trace;
import io.langfuse.client.model.Usage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Service minh họa việc cấu trúc Trace phân cấp (Nested Spans & Generations)
 * để theo dõi và phân tích biểu đồ Latency Waterfall trên Langfuse Dashboard,
 * giúp xác định chính xác Bottleneck giữa Vector DB Retrieval và LLM Generation.
 */
@Service
public class RagLatencyMonitoringService {

    private static final Logger log = LoggerFactory.getLogger(RagLatencyMonitoringService.class);
    private final LangfuseClient langfuseClient;

    public RagLatencyMonitoringService(LangfuseClient langfuseClient) {
        this.langfuseClient = langfuseClient;
    }

    /**
     * Xử lý truy vấn tra cứu chính sách ngân hàng sử dụng RAG với đầy đủ telemetry breakdown.
     */
    public String answerBankingQueryWithRag(String userId, String userQuery) {
        String sessionId = "sess-" + UUID.randomUUID();

        // 1. Khởi tạo Root Trace
        Trace trace = langfuseClient.trace(new Trace()
                .name("rag-policy-lookup")
                .userId(userId)
                .sessionId(sessionId)
                .tags(List.of("rag", "vector-search", "rikkeipay-faq"))
                .input(userQuery));

        try {
            // 2. SPAN 1: Bước truy vấn Vector DB (Retrieval Phase)
            Span retrievalSpan = trace.span(new Span()
                    .name("rag-retrieval-step")
                    .input(Map.of("query", userQuery, "top_k", 3))
                    .startTime(Instant.now()));

            log.info("Executing Vector Retrieval for query: [{}]", userQuery);
            List<String> retrievedDocuments = queryVectorDatabase(userQuery, retrievalSpan);
            retrievalSpan.endTime(Instant.now())
                    .output(Map.of("docs_retrieved_count", retrievedDocuments.size(), "documents", retrievedDocuments));

            // 3. GENERATION: Bước sinh câu trả lời bằng LLM (Generation Phase)
            String promptContext = String.join("\n---\n", retrievedDocuments);
            String fullPrompt = "Ngữ cảnh ngân hàng:\n" + promptContext + "\n\nCâu hỏi: " + userQuery;

            Generation generation = trace.generation(new Generation()
                    .name("llm-answer-generation")
                    .model("gemini-2.5-flash")
                    .input(fullPrompt)
                    .startTime(Instant.now()));

            log.info("Generating LLM Response with Gemini-2.5-Flash...");
            String answer = callLlmService(fullPrompt);

            // Ghi nhận hoàn thành generation
            generation.endTime(Instant.now())
                    .output(answer)
                    .usage(new Usage().promptTokens(650).completionTokens(120).totalTokens(770));

            // 4. Kết thúc Root Trace
            trace.output(Map.of("final_answer", answer, "status", "SUCCESS"));
            return answer;

        } catch (Exception ex) {
            log.error("RAG Pipeline failed: {}", ex.getMessage(), ex);
            trace.output(Map.of("error", ex.getMessage()));
            trace.level("ERROR");
            return "Đã xảy ra lỗi trong quá trình tra cứu thông tin chính sách.";
        }
    }

    private List<String> queryVectorDatabase(String query, Span parentSpan) {
        // Mô phỏng Span con: Embedding latency
        Span embeddingSpan = parentSpan.span(new Span()
                .name("text-embedding-creation")
                .input(query)
                .startTime(Instant.now()));
        try {
            Thread.sleep(80); // Giả lập độ trễ embedding 80ms
        } catch (InterruptedException ignored) {}
        embeddingSpan.endTime(Instant.now()).output("Embedding Vector [dim: 1536]");

        // Mô phỏng Span con: Vector DB Search latency
        Span milvusSpan = parentSpan.span(new Span()
                .name("milvus-vector-similarity-search")
                .input(Map.of("collection", "rikkeipay_policies", "metric", "COSINE"))
                .startTime(Instant.now()));
        try {
            Thread.sleep(150); // Giả lập độ trễ Vector Search 150ms
        } catch (InterruptedException ignored) {}
        milvusSpan.endTime(Instant.now()).output("Matched 3 chunks with score > 0.82");

        return List.of(
                "Chính sách hạn mức chuyển tiền trực tuyến RikkeiPay: Tối đa 500,000,000 VND / ngày.",
                "Phí chuyển tiền liên ngân hàng 24/7 qua Napas: Miễn phí trọn đời.",
                "Yêu cầu xác thực sinh trắc học khuôn mặt đối với giao dịch trên 10,000,000 VND."
        );
    }

    private String callLlmService(String prompt) {
        try {
            Thread.sleep(450); // Giả lập LLM Generation latency 450ms
        } catch (InterruptedException ignored) {}
        return "Theo chính sách của RikkeiPay, quý khách được miễn phí trọn đời chuyển tiền liên ngân hàng 24/7 và hạn mức giao dịch tối đa là 500 triệu VND/ngày.";
    }
}
