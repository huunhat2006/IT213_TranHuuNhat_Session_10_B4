# BÁO CÁO GIÁM SÁT CHI PHÍ & PHÂN TÍCH LATENCY TRONG LLMOps
**Dự án:** Rikkei Intelligent Banking & Assistant Suite (RikkeiPay)  
**Phân hệ:** Trợ lý ảo giao dịch ngân hàng thông minh (RikkeiPay Assistant)  
**Tác vụ:** Nghiên cứu Token & Cost Tracking, Thiết lập Custom Model Prices và Phân tích Bottleneck Latency trên Langfuse

---

## 1. Cơ Chế Tính Toán Chi Phí (Token & Cost Tracking) Trên Langfuse

### 1.1. Cách Langfuse tự động đếm Token từ Spring AI
Khi ứng dụng Spring AI thực hiện gọi mô hình LLM thông qua `ChatClient` hoặc SDK:
1. **Metadata Ingestion:** Response trả về từ nhà cung cấp LLM (OpenAI, Google Gemini, Anthropic, DeepSeek API,...) thường đính kèm đối tượng `Usage` / `TokenUsage` trong phần Response Metadata (gồm: `promptTokens` / `inputTokens`, `completionTokens` / `outputTokens`, `totalTokens`).
2. **Telemetry Extraction:** Langfuse Client / Spring AI Observability Interceptor tự động bắt (intercept) các trường metadata này và gắn vào đối tượng **Generation** của Trace tương ứng.
3. **Fallback Tokenizer:** Đối với các mô hình local (Ollama, vLLM, HuggingFace) hoặc API không trả về usage metadata, Langfuse sử dụng bộ mã hóa token tích hợp (Tiktoken, Claude tokenizer, hoặc tokenizers do người dùng cấu hình) để tự động ước tính số lượng token dựa trên độ dài chuỗi văn bản đầu vào/đầu ra.

---

### 1.2. Cách thiết lập bảng giá Model tùy chỉnh (Custom Model Prices) trên Langfuse Dashboard

Langfuse cung cấp sẵn danh mục giá mặc định cho các mô hình phổ biến trên thị trường, đồng thời cho phép thiết lập bảng giá riêng (**Custom Models & Pricing**) cho các mô hình nội bộ hoặc mô hình mới như **Gemini-2.5-Flash** và **DeepSeek-V3**.

#### Các bước thiết lập trên giao diện Langfuse Dashboard:
1. Truy cập **Settings** -> Chọn tab **Models & Pricing** -> Nhấn **+ Add Model Definition**.
2. Nhập các thông tin cấu hình giá:
   * **Model Name:** Tên định danh mô hình khớp với tham số `model` được gửi trong Spring AI (ví dụ: `gemini-2.5-flash` hoặc `deepseek-chat`).
   * **Match Pattern (Regex):** Biểu thức chính quy để nhận diện tên model (ví dụ: `(?i)^(models/)?gemini-2\.5-flash.*`).
   * **Unit:** Đơn vị tính (thường là `TOKENS` hoặc `CHARACTERS`).
   * **Input Price (per 1k tokens hoặc per 1M tokens):** Đơn giá nạp vào ($).
   * **Output Price (per 1k tokens hoặc per 1M tokens):** Đơn giá sinh ra ($).
   * **Start Date:** Thời điểm áp dụng biểu giá.

#### Bảng so sánh biểu giá mẫu (Dữ liệu tham chiếu cho RikkeiPay):
| Tiêu chí | Google Gemini-2.5-Flash | DeepSeek-V3 (Official API) | Nhận xét & Đánh giá cho RikkeiPay |
| :--- | :--- | :--- | :--- |
| **Input Price (USD / 1M Tokens)** | ~$0.075 (≤ 128k prompt) | ~$0.14 (Cache Miss) / $0.014 (Cache Hit) | DeepSeek-V3 tối ưu vượt trội khi có Prompt Caching. |
| **Output Price (USD / 1M Tokens)** | ~$0.30 | ~$0.28 | Chi phí output của 2 bên tương đương nhau. |
| **Chi phí trung bình / 1,000 lượt gọi** (Input: 800 tokens, Output: 200 tokens) | **~$0.12 USD** (~3,000 VND) | **~$0.168 USD** (~4,200 VND, chưa cache) | Cả 2 mô hình đều có mức chi phí cực kỳ tiết kiệm cho khối lượng giao dịch ngân hàng lớn. |

#### Công thức tính toán chi phí trên Langfuse:
$$\text{Total Cost} = (\text{Input Tokens} \times \text{Input Price Unit}) + (\text{Output Tokens} \times \text{Output Price Unit})$$

---

## 2. Hướng Dẫn Phân Tích Biểu Đồ Latency & Xác Định Bottleneck RAG

Khi một truy vấn RAG (Retrieval-Augmented Generation) của khách hàng RikkeiPay phản hồi chậm, Langfuse cung cấp biểu đồ dạng cây phân cấp (**Trace Waterfall Tree View**) để mổ xẻ chính xác độ trễ từng phân đoạn.

```
[Trace] total_duration = 2450ms (Toàn bộ cuộc gọi)
├── [Span] user_authentication & sanitize (duration: 35ms)
├── [Span] rag_vector_retrieval (duration: 1850ms)  <--- 🚨 BOTTLENECK PHÁT HIỆN TẠI ĐÂY!
│   ├── [Span] text_embedding_generation (duration: 250ms)
│   └── [Span] milvus_vector_search (duration: 1600ms)
└── [Generation] llm_answer_generation (duration: 565ms)
    ├── time_to_first_token (TTFT): 180ms
    └── token_generation_duration: 385ms
```

### 2.1. Nhận diện Bottleneck ở bước truy vấn Vector DB (Retrieval Phase)
* **Dấu hiệu trên biểu đồ Langfuse:**
  * Thời gian của Span `rag_retrieval` / `vector_search` chiếm tỷ trọng lớn (> 60% tổng thời gian Trace).
  * Biểu đồ phân vị **P95 / P99 Latency** của Span Retrieval tăng đột biến theo thời gian.
* **Nguyên nhân chính & Giải pháp khắc phục:**
  1. *Vector Index chưa tối ưu:* Chưa tạo chỉ mục HNSW/IVF_FLAT hoặc `efSearch`/`nlist` quá cao.  
     -> **Khắc phục:** Tinh chỉnh tham số Index trên Vector DB (Milvus/PgVector/Qdrant).
  2. *Độ trễ Embedding API:* Gọi Embedding model qua Remote API bị nghẽn mạng.  
     -> **Khắc phục:** Chuyển sang Local Embedding (ONNX Runtime, TEI) hoặc Cache Embedding của câu hỏi phổ biến.
  3. *Payload quá lớn:* Trích xuất quá nhiều đoạn văn bản (Top-K = 20) nạp vào prompt.  
     -> **Khắc phục:** Giảm Top-K xuống 3-5 kết hợp Re-ranking.

### 2.2. Nhận diện Bottleneck ở bước sinh văn bản của LLM (Generation Phase)
* **Dấu hiệu trên biểu đồ Langfuse:**
  * Span `llm_generation` chiếm phần lớn thời gian Trace (> 70% tổng thời gian).
  * Chỉ số **Time To First Token (TTFT)** cao (> 1.5s - 2s): Mô hình mất quá nhiều thời gian đọc hiểu context trước khi nhả token đầu tiên.
  * Tốc độ **Tokens Per Second (TPS)** thấp (< 15 tokens/sec).
* **Nguyên nhân chính & Giải pháp khắc phục:**
  1. *Prompt/Context quá dài:* Context từ Vector DB quá cồng kềnh làm phình to Prompt Tokens.  
     -> **Khắc phục:** Nén ngữ cảnh (Context Compression) và tinh gọn system prompt.
  2. *Max Output Tokens đặt quá lớn:* LLM sinh câu trả lời dài dòng không cần thiết.  
     -> **Khắc phục:** Ép ràng buộc ngắn gọn trong System Prompt và giới hạn `max_tokens` (ví dụ: 300 tokens cho câu trả lời chuyển khoản).
  3. *Tình trạng quá tải API nhà cung cấp:* Provider đang bị rate-limit hoặc nghẽn hàng đợi.  
     -> **Khắc phục:** Cấu hình Fallback Provider hoặc Load Balancing giữa Gemini và DeepSeek.

---

## 3. Cấu Trúc Mã Nguồn Minh Họa

Trong thư mục `Session10/B4/src/main/java/com/rikkeipay/`, các mã nguồn sau đã được xây dựng hoàn chỉnh:

1. **`CustomModelTokenTrackingService.java`**: Minh họa cách đo lường thời gian và gửi thông tin `Usage` (inputTokens, outputTokens, totalTokens) thủ công sang Langfuse cho các model tùy biến hoặc khi token usage không tự động trả về từ metadata.
2. **`RagLatencyMonitoringService.java`**: Xây dựng luồng RAG đa bước với đầy đủ các Span lồng nhau (`rag-retrieval`, `vector-search`, `llm-generation`) giúp hiển thị biểu đồ phân tích Waterfall Latency chuyên nghiệp trên Langfuse Dashboard.
