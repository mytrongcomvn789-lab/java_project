# APDDMP-55 — Bàn giao kiểm thử 7.5

Phụ trách: **Lê Công Nhã**. Thực hiện trực tiếp trong `D:/3.HOC/1. LT_JAVA/BTL/java_project`.
Nhánh: `feature/7.5-operations-ai-analytics`; nền main `dbd4fc0`.
Ngày: 25/11/2026.

## Kết quả thực chạy

Lệnh: `mvn -f backend/pom.xml clean test`.
JDK 25, Java release 21 theo pom; H2 MSSQL mode; HTTP API thật trên random port; provider fixture.
**BUILD SUCCESS — 773 tổng, 764 thực chạy đạt, 0 failures, 0 errors, 9 skipped.**
7 MinioFileFlowTest thiếu MINIO_TEST_ENDPOINT và 2 concurrency OrderIntegrationTest thiếu ORDER_TEST_DATABASE_URL.
Không tính skipped là pass. Không gọi Gemini thật, không sửa cấu hình skip để làm đẹp kết quả.

Log local: `C:/Users/Cong Nha/Documents/Codex/ops75-full.log`.
Surefire: `backend/target/surefire-reports/TEST-*.xml` (generated, không commit).
Lượt đầu phát hiện nhãn evidence viết hoa không hợp lệ với AiDataBlock và import Order trùng tên trong test;
đã sửa và chạy lại bằng clean test để loại class cũ.

## 28 test mới

| Nhóm | Số test | Kiểm chứng |
|---|---:|---|
| OperationsRulesTest | 8 | FAILED khác 1-success khi có hoàn; ngưỡng 20%/mẫu20; null mẫu0; trend hai cửa sổ/giữa lẻ/baseline0/ngày đơn/giảm; tải80%/capacity0; thiếu mốc thời gian |
| OperationsNarrativeTest | 5 | ID hợp lệ -> text server; bỏ extra fields; từ chối prose/số bịa/nguyên nhân; ID không đúng báo cáo; rỗng/sai kiểu; deduplicate và fallback |
| OperationsAnalyticsServiceTest | 6 | Sai role trước query; không đơn không gọi AI; fallback4 loại lỗi; không nuốt rate/budget/quyền; giữ metadata; model không có căn cứ -> fallback |
| OperationsAnalyticsIntegrationTest | 9 | API thật/H2, parity cả3 API gốc; bộ lọc/thời gian/role/phạm vi trạm; thu hồi quyền; provider timeout/hallucination và dữ liệu cá nhân |

## Fixture đối chiếu API (tạo bởi test, không sửa DB vận hành)

- Hai trạm A/B được phân công cho quản lý, C ngoài quyền.
- 20 đơn A->B trong 01–02/11/2026: 16 DELIVERED, 4 FAILED.
- 10 đơn ở biên đầu ngày01 và 10 đơn lúc23:59:59 ngày02; một đơn ngày03 bị loại.
- Một đơn C->C bị loại theo quyền.
- 2/16 đơn DELIVERED đủ DISPATCH/CONFIRM cách nhau300giây; 14 đơn thiếu mốc.
- Kỳ CREATED: total20, successRate0.8, failureRate0.2, duration count2/avg300/excluded14, daily10+10.
- Kỳ COMPLETED: chỉ2 đơn có lịch sử hoàn tất hợp lệ trong khoảng.
- StationLoad so trực tiếp API gốc với cùng collectedAt và stationId.
- Kiểm tra prompt không có tên/địa chỉ/email/SĐT/ID trạm ngoài quyền.
- Thu hồi phân công trong database H2 riêng: request tiếp theo rỗng, không gọi provider.

## Bảng thay đổi

| File/nhóm | Thay đổi và mục tiêu |
|---|---|
| OperationsDataset / OperationsDataReader | Gom nguyên bản DTO5.1 theo quyền, cùng bộ lọc, transaction đọc kết thúc trước gọi model |
| OperationsRules | Công thức FAILED, trend, warnings, evidence, ngưỡng minh bạch có version |
| OperationsNarrative | Chỉ nhận ID thuộc evidence hiện tại; render text server; fallback xác định |
| OperationsAnalyticsService | Điều phối dữ liệu -> rules -> AiService -> kiểm chứng; giữ số liệu khi model lỗi |
| AiController | Thay endpoint analytics khung7.1 bằng service7.5; không đổi role |
| PromptBuilder / MockAiProvider | OPS_ANALYSIS chọn ID; mock trả ID hợp lệ và vẫn source=MOCK |
| AiDataAccessGuard / AiResult | Cập nhật mô tả kiến trúc/fallback cho đúng thực tế |
| 4 lớp test mới | 28 test; không xóa hoặc nới test cũ |
| docs API/kiến trúc/test | Hợp đồng và checklist bàn giao7.6 |

Không xóa file nghiệp vụ, không thêm migration, không sửa cấu hình key, không sửa đơn/trạm/lịch.
Không thêm UI (7.6), không tự merge main.

## Nhóm commit có ý nghĩa

1. Dataset và đọc thống kê theo phạm vi.
2. Công thức, evidence và unit test ngưỡng.
3. Kiểm chứng nội dung AI và unit test chống bịa.
4. Hợp đồng prompt/mock chọn ID.
5. API orchestration/fallback, metadata và unit test.
6. Test HTTP/database tích hợp.
7. Sơ đồ kiến trúc, trách nhiệm từng lớp.
8. Tài liệu API, kết quả kiểm thử và issue bàn giao.

## Kiểm tra tiếp trước nghiệm thu

- [ ] Nhóm review code, công thức FAILED và ngưỡng20%/80% (quy tắc v1, chưa phải SLA đã duyệt).
- [ ] Nghiệm thu SQL Server với dữ liệu đại diện; không coi H2 là đủ cho production.
- [ ] Kiểm tra Gemini thật chọn ID theo evidence; provider được cấu hình bằng cơ chế7.1 hiện có.
- [ ] Trọng tích hợp UI7.6: hiển thị source, sample, warnings, tải trạm hiện tại và toàn bộ evidence.
- [ ] Kiểm tra UI không hiển thị MOCK/FALLBACK thành AI thật.
- [ ] Chạy lại9 test bị skip khi có môi trường tương ứng.

Giới hạn: nhiều truy vấn read-committed không phải snapshot đóng băng; thống kê trạng thái hiện tại không phải lịch sử
trạng thái; trend không dự báo/quan hệ nhân quả; AI chỉ xếp ưu tiên nội dung đã xác minh.

## Kiểm tra hợp nhất main — 26/11/2026

Theo yêu cầu của Nhã, hợp nhất nhánh 7.5 với main mới nhất `90dcc27` đã có ETA 7.2.
Xung đột duy nhất ở constructor AiController đã được giải quyết bằng cách giữ cả EtaService
và OperationsAnalyticsService; endpoint ETA vẫn dùng công thức 7.2, analytics dùng 7.5.

Chạy lại `mvn -f backend/pom.xml clean test` trên bản hợp nhất:
**BUILD SUCCESS — 800 tổng, 791 thực chạy đạt, 0 failures, 0 errors, 9 skipped.**
9 skipped vẫn gồm 7 MinIO và 2 SQL concurrency thiếu môi trường; chưa nghiệm thu Gemini thật.
Log local: `C:/Users/Cong Nha/Documents/Codex/ops75-main-merge-tests.log`.
Các kết quả 773 test ở phần trên là lần kiểm tra nhánh 7.5 trước khi tích hợp ETA 7.2.