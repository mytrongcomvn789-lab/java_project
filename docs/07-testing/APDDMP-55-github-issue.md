Phụ trách: **Lê Công Nhã**. Jira: https://nnha90190-1791007728869.atlassian.net/browse/APDDMP-55
Nhánh: `feature/7.5-operations-ai-analytics`, nền main `dbd4fc0`.

## Đã triển khai
POST /api/v1/ai/analytics cho LOGISTICS_MANAGER theo phạm vi trạm. Dùng số liệu StatisticsService 5.1; tính failureRate, trend, mẫu và warnings bằng Java. Tải trạm ghi riêng là slot hiện tại. AI chỉ chọn ID evidence đã kiểm chứng; không nhận số liệu/quan hệ nhân quả tự tạo. Provider lỗi vẫn trả báo cáo FALLBACK; rate limit/budget/quyền vẫn giữ nguyên. Không sửa đơn/lịch.

## Test đã chạy — 25/11/2026
`mvn -f backend/pom.xml clean test`: **773 tổng, 764 thực chạy đạt, 0 failures, 0 errors, 9 skipped**.
**28 test mới đạt**: rules8, narrative5, orchestration6, HTTP/H2 integration9.
Đối chiếu API7.5 với summary/daily/stationLoad gốc; fixture20đơn(16giao/4FAILED), success0.8/failure0.2, duration2đơn/300giây/14thiếu mốc. Test boundaryngày, COMPLETED, scope/thu hồi quyền, roles, bộlọc400, timeout, AI bịa và PII.
9skipped:7MinIO+2SQLconcurrency do thiếu môi trường. Chưa nghiệm thu Gemini thật/SQLServer thật.

## Docs
- [API và hướng dẫn Swagger](https://github.com/mytrongcomvn789-lab/java_project/blob/feature/7.5-operations-ai-analytics/docs/05-api/operations-ai-analytics.md)
- [Bảng thay đổi và báo cáo test](https://github.com/mytrongcomvn789-lab/java_project/blob/feature/7.5-operations-ai-analytics/docs/07-testing/APDDMP-55-verification.md)
- [Kiến trúc](https://github.com/mytrongcomvn789-lab/java_project/blob/feature/7.5-operations-ai-analytics/docs/03-architecture/operations-ai-analytics.md)

## Checklist bàn giao
- [ ] Review8nhómcommit và ngưỡng cảnh báo20%/80% (chưa là SLA đã duyệt).
- [ ] Nghiệm thu SQLServer thật và Gemini thật.
- [ ] Chạy9test đang skip khi đủ môi trường.
- [ ] Trọng tích hợp UI7.6, giữ nhãn MOCK/FALLBACK, số mẫu, warnings.
- [ ] Nhã test/review trước merge main.

AI xếp ưu tiên bằng chứng; text server xác định. Không cam kết snapshot đóng băng giữa nhiều query; không suy ra nhân quả từ trend.
