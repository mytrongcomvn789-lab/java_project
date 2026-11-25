# Kiến trúc chat khách hàng — APDDMP-54

Phụ trách: Lê Công Nhã. Dựa trên AiService 7.1, không tách thêm dịch vụ hoặc đổi provider.

```mermaid
sequenceDiagram
    participant C as Khách hàng / client 7.6
    participant API as AiController + RBAC
    participant S as CustomerChatService
    participant DB as ChatHistoryStore / SQL
    participant G as AiDataAccessGuard
    participant AI as AiService 7.1 / Gemini
    C->>API: POST chat + JWT + message
    API->>S: CUSTOMER đã xác thực
    S->>G: Kiểm quyền orderId nếu có
    S->>DB: begin: owner, TTL, lease
    DB-->>S: Hội thoại + tối đa 3 lượt gần nhất
    S->>G: Đọc lại trạng thái đơn theo quyền hiện tại
    S->>AI: Quy tắc + dữ liệu đã che + câu hỏi
    Note over S,AI: Không giữ transaction/khóa DB trong lời gọi provider
    AI-->>S: AiResult (PROVIDER/MOCK/FALLBACK)
    S->>DB: complete chỉ khi lease còn khớp
    S-->>C: Reply dạng text + metadata + conversationId
```

- Entity lưu lịch sử riêng theo owner; ID ngẫu nhiên không thay thế kiểm quyền.
- Giao dịch ngắn với pessimistic lock bảo vệ một hội thoại; lease chống ghi từ response cũ.
- AiService tiếp tục kiểm rate limit/ngân sách/circuit breaker/audit của 7.1.
- Chỉ CustomerChatService điều phối chat; không có repository ghi nghiệp vụ đơn hay công cụ model.
- Nguồn chính sách thương mại chưa có; danh mục ChatKnowledge là quy tắc chức năng có thể đối chiếu code.
- Giới hạn và rủi ro còn lại được ghi trong [hợp đồng API](../05-api/customer-ai-chat.md).
