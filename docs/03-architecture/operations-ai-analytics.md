# Kiến trúc 7.5 — Nhã

```mermaid
sequenceDiagram
    actor Manager as Quản lý hậu cần
    participant API as AiController
    participant Ops as OperationsAnalyticsService
    participant Data as OperationsDataReader
    participant Stats as StatisticsService 5.1
    participant Rules as OperationsRules
    participant AI as AiService 7.1 / Gemini
    participant Guard as OperationsNarrative
    Manager->>API: POST /v1/ai/analytics + JWT + bộ lọc
    API->>Ops: Kiểm role LOGISTICS_MANAGER
    Ops->>Data: read(actor, filters)
    Data->>Stats: summary / daily / stationLoad
    Note over Data,Stats: Phạm vi trạm theo phân công; transaction đọc kết thúc trước gọi AI
    Stats-->>Data: DTO số liệu từ Java/SQL
    Data-->>Ops: OperationsDataset
    Ops->>Rules: Công thức, cảnh báo, evidence
    Rules-->>Ops: Findings có số mẫu và nguồn
    alt Không có đơn
        Ops-->>API: Báo cáo xác định, FALLBACK / NO_ORDERS_IN_SCOPE
    else Có đơn
        Ops->>AI: Phạm vi ngày + evidence ngắn, không PII
        AI-->>Ops: ID nhận định được ưu tiên hoặc lỗi
        Ops->>Guard: Xác minh ID thuộc evidence hiện tại
        Guard-->>Ops: Text server hoặc từ chối
        Ops-->>API: Báo cáo + nội dung kiểm chứng hoặc fallback
    end
    API-->>Manager: no-store; nguồn, thời gian, cảnh báo
```

Không mở transaction DB qua mạng gọi model; không tạo bảng/migration mới.
Không thay đổi công thức thống kê gốc hoặc nới role của 7.1.
Analytics không có command, tool call, thay đổi đơn hoặc tự điều chỉnh lịch.
AI chỉ xếp ưu tiên evidence; Java render lời giải thích và khuyến nghị.
Không đóng băng snapshot giữa các truy vấn; warning ghi rõ giới hạn này.
