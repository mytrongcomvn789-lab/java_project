## 7.4 — Trợ lý AI khách hàng / APDDMP-54

Phụ trách: **Lê Công Nhã**.
Jira: https://nnha90190-1791007728869.atlassian.net/browse/APDDMP-54
Nhánh: `feature/7.4-customer-ai-chat`, nền main `e0a44bc` (AI Service 7.1 đã merge).

### Đã triển khai
- Chat CUSTOMER-only trên AiService/Gemini 7.1; không thêm provider OpenAI.
- Hội thoại lưu riêng theo tài khoản/đơn, TTL 24h, giữ 3 lượt gần nhất, tối đa 30 lượt.
- Kiểm quyền đơn mỗi lượt; không nhận ID hội thoại ngoài quyền; lease ngăn ghi đồng thời và response muộn.
- Nguồn quy tắc có version; thiếu chính sách thương mại thì không tự bịa.
- Redaction, prompt tách chỉ dẫn/dữ liệu, output whitelist và fallback có giải thích. Không có lệnh thay đổi đơn.

### Đã test (25/11/2026)
Full backend: **745 tổng, 736 thực chạy đạt, 0 failures, 0 errors, 9 skipped**.
Nhóm AI/RBAC: **348/348 đạt**. 11 test tích hợp mới: khác tài khoản, UUID giả, sai role,
trạng thái mới, đổi đơn, redaction/injection, busy409, timeout/retry, TTL/cleanup,
lease cũ, giới hạn30 lượt, quyền đơn thay đổi và model không thực thi lệnh.
7 MinIO + 2 concurrency DB bị skip vì thiếu biến môi trường test tương ứng; không tính là pass.
Gemini adapter được test qua HTTP fixture, chưa nghiệm thu model thật.

### Tài liệu
- [API 7.4](https://github.com/mytrongcomvn789-lab/java_project/blob/feature/7.4-customer-ai-chat/docs/05-api/customer-ai-chat.md)
- [Báo cáo test](https://github.com/mytrongcomvn789-lab/java_project/blob/feature/7.4-customer-ai-chat/docs/07-testing/APDDMP-54-verification.md)

### Checklist review
- [ ] Review code và 5 commit theo chức năng.
- [ ] Kiểm tra SQL Server thật và các test đang skip.
- [ ] Nhóm duyệt chính sách thương mại; thử Gemini thật bằng bộ câu hỏi chuẩn.
- [ ] Trọng tích hợp UI ở 7.6, kiểm tra đổi tài khoản và hiển thị MOCK/FALLBACK.
