package com.smartdrone.application.statistics;

import java.io.ByteArrayOutputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.smartdrone.application.statistics.dto.DailyOrderStats;
import com.smartdrone.application.statistics.dto.OrderStatsSummary;
import com.smartdrone.application.statistics.dto.ReportFile;
import com.smartdrone.application.statistics.dto.StationLoadStat;

@Service
public class ReportExportService {
    private static final MediaType XLSX = MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    private static final MediaType PDF = MediaType.APPLICATION_PDF;

    private final StatisticsService statistics;
    private final Clock clock;

    public ReportExportService(StatisticsService statistics, Clock clock) {
        this.statistics = statistics;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ReportFile excel(UUID actorId, StatisticsPeriod period, Instant at) {
        var summary = statistics.summary(actorId, period);
        var daily = statistics.daily(actorId, period);
        var stations = statistics.stationLoad(actorId, period.stationId(), at);
        var generatedAt = Instant.now(clock);
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            createMetadataSheet(workbook, summary, period, generatedAt);
            createSummarySheet(workbook, summary);
            createDailySheet(workbook, daily);
            createStationSheet(workbook, stations);
            workbook.write(out);
            String name = "thong-ke-don-" + period.from() + "-" + period.to() + ".xlsx";
            return new ReportFile(name, XLSX, out.toByteArray());
        } catch (Exception ex) {
            throw new IllegalStateException("Không thể tạo báo cáo Excel", ex);
        }
    }

    @Transactional(readOnly = true)
    public ReportFile pdf(UUID actorId, StatisticsPeriod period, Instant at) {
        var summary = statistics.summary(actorId, period);
        var daily = statistics.daily(actorId, period);
        var stations = statistics.stationLoad(actorId, period.stationId(), at);
        var generatedAt = Instant.now(clock);
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Document document = new Document(PageSize.A4.rotate(), 36, 36, 36, 36);
            PdfWriter.getInstance(document, out);
            document.open();

            var regular = pdfFont("/fonts/NotoSans-Regular.ttf", 9);
            var bold = pdfFont("/fonts/NotoSans-Regular.ttf", 9, Font.BOLD);
            var title = pdfFont("/fonts/NotoSans-Regular.ttf", 16, Font.BOLD);
            document.add(new Paragraph("BÁO CÁO THỐNG KÊ ĐƠN GIAO HÀNG", title));
            document.add(new Paragraph(metadataText(summary, period, generatedAt), regular));
            document.add(new Paragraph("Ghi chú định nghĩa: tỷ lệ giao = delivered / (delivered + failed + returning + returned); "
                    + "tỷ lệ hoàn = (returning + returned) / mẫu trên; mẫu bằng 0 thì tỷ lệ là không có dữ liệu.", regular));

            document.add(new Paragraph("TỔNG HỢP", bold));
            PdfPTable summaryTable = new PdfPTable(new float[] {3.2f, 1.2f});
            summaryTable.setWidthPercentage(55);
            addPdfRow(summaryTable, "Chỉ số", "Giá trị", bold, true);
            addPdfRow(summaryTable, "Tổng đơn", String.valueOf(summary.orders().total()), regular, false);
            addPdfRow(summaryTable, "Đang xử lý", String.valueOf(summary.orders().inProgress()), regular, false);
            addPdfRow(summaryTable, "Đã giao", String.valueOf(summary.orders().delivered()), regular, false);
            addPdfRow(summaryTable, "Giao thất bại", String.valueOf(summary.orders().failed()), regular, false);
            addPdfRow(summaryTable, "Đang hoàn", String.valueOf(summary.orders().returning()), regular, false);
            addPdfRow(summaryTable, "Đã hoàn", String.valueOf(summary.orders().returned()), regular, false);
            addPdfRow(summaryTable, "Đã hủy", String.valueOf(summary.orders().cancelled()), regular, false);
            addPdfRow(summaryTable, "Bị từ chối", String.valueOf(summary.orders().rejected()), regular, false);
            addPdfRow(summaryTable, "Tỷ lệ giao", percent(summary.rates().successRate()), regular, false);
            addPdfRow(summaryTable, "Tỷ lệ hoàn", percent(summary.rates().returnRate()), regular, false);
            addPdfRow(summaryTable, "Tỷ lệ hủy", percent(summary.rates().cancelRate()), regular, false);
            addPdfRow(summaryTable, "Tổng attempt", String.valueOf(summary.attempts().totalAttempts()), regular, false);
            addPdfRow(summaryTable, "Retry attempt", String.valueOf(summary.attempts().retryAttempts()), regular, false);
            addPdfRow(summaryTable, "Thời gian giao TB (giây)", seconds(summary.deliveryTime().avgSeconds()), regular, false);
            document.add(summaryTable);

            document.add(new Paragraph("THEO NGÀY", bold));
            PdfPTable dailyTable = new PdfPTable(new float[] {1.4f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f});
            dailyTable.setWidthPercentage(100);
            for (String h : List.of("Ngày", "Tổng", "Chờ", "Đang xử lý", "Đã giao", "Thất bại", "Đang hoàn", "Đã hoàn", "Hủy/Từ chối")) {
                addPdfCell(dailyTable, h, bold, true);
            }
            for (DailyOrderStats day : daily) {
                var c = day.orders();
                for (String v : List.of(day.date().toString(), String.valueOf(c.total()), String.valueOf(c.pending()),
                        String.valueOf(c.inProgress()), String.valueOf(c.delivered()), String.valueOf(c.failed()),
                        String.valueOf(c.returning()), String.valueOf(c.returned()),
                        String.valueOf(c.cancelled() + c.rejected()))) addPdfCell(dailyTable, v, regular, false);
            }
            document.add(dailyTable);

            document.add(new Paragraph("TẢI TRẠM", bold));
            PdfPTable stationTable = new PdfPTable(new float[] {2.2f, 1.1f, 1f, 1f, 1f, 1f, 1f});
            stationTable.setWidthPercentage(100);
            for (String h : List.of("Trạm", "Sức chứa", "Đã đặt", "Còn trống", "Đang xử lý", "Sử dụng", "Khung giờ"))
                addPdfCell(stationTable, h, bold, true);
            for (StationLoadStat s : stations) {
                for (String v : List.of(safe(s.name()), String.valueOf(s.capacity()), String.valueOf(s.booked()),
                        String.valueOf(s.free()), String.valueOf(s.processing()), percent(s.utilization()),
                        s.slotStart().atZone(StatisticsPeriod.ZONE).toLocalTime().toString()))
                    addPdfCell(stationTable, v, regular, false);
            }
            document.add(stationTable);

            document.close();
            String name = "thong-ke-don-" + period.from() + "-" + period.to() + ".pdf";
            return new ReportFile(name, PDF, out.toByteArray());
        } catch (Exception ex) {
            throw new IllegalStateException("Không thể tạo báo cáo PDF", ex);
        }
    }

    private void createMetadataSheet(Workbook wb, OrderStatsSummary summary, StatisticsPeriod period, Instant generatedAt) {
        Sheet sheet = wb.createSheet("Thông tin");
        int r = 0;
        r = addSheetRow(sheet, r, "Tiêu đề", "Báo cáo thống kê đơn giao hàng");
        r = addSheetRow(sheet, r, "Từ ngày", period.from().toString());
        r = addSheetRow(sheet, r, "Đến ngày", period.to().toString());
        r = addSheetRow(sheet, r, "Múi giờ", summary.timezone());
        r = addSheetRow(sheet, r, "Mốc ngày", summary.dateBasis().name());
        r = addSheetRow(sheet, r, "Trạm", period.stationId() == null ? "Toàn phạm vi được phân công" : period.stationId().toString());
        r = addSheetRow(sheet, r, "Thời điểm xuất", formatInstant(generatedAt));
        r = addSheetRow(sheet, r, "Đơn vị", "Đơn / attempt / giây");
        addSheetRow(sheet, r, "Ghi chú", "Số liệu lấy trực tiếp từ API thống kê 5.1; không tự tính lại công thức trong exporter.");
        sheet.setColumnWidth(0, 6000); sheet.setColumnWidth(1, 14000);
    }

    private void createSummarySheet(Workbook wb, OrderStatsSummary s) {
        Sheet sheet = wb.createSheet("Tổng hợp");
        int r = 0;
        r = addSheetRow(sheet, r, "Chỉ số", "Giá trị");
        r = addSheetRow(sheet, r, "Tổng đơn", s.orders().total());
        r = addSheetRow(sheet, r, "Chờ xử lý", s.orders().pending());
        r = addSheetRow(sheet, r, "Đang xử lý", s.orders().inProgress());
        r = addSheetRow(sheet, r, "Đã giao", s.orders().delivered());
        r = addSheetRow(sheet, r, "Giao thất bại", s.orders().failed());
        r = addSheetRow(sheet, r, "Đang hoàn", s.orders().returning());
        r = addSheetRow(sheet, r, "Đã hoàn", s.orders().returned());
        r = addSheetRow(sheet, r, "Đã hủy", s.orders().cancelled());
        r = addSheetRow(sheet, r, "Bị từ chối", s.orders().rejected());
        r = addSheetRow(sheet, r, "Tỷ lệ giao", s.rates().successRate());
        r = addSheetRow(sheet, r, "Tỷ lệ hoàn", s.rates().returnRate());
        r = addSheetRow(sheet, r, "Tỷ lệ hủy", s.rates().cancelRate());
        r = addSheetRow(sheet, r, "Tổng attempt", s.attempts().totalAttempts());
        r = addSheetRow(sheet, r, "Retry attempt", s.attempts().retryAttempts());
        r = addSheetRow(sheet, r, "Đơn có retry", s.attempts().ordersWithRetry());
        r = addSheetRow(sheet, r, "Thời gian giao TB (giây)", s.deliveryTime().avgSeconds());
        r = addSheetRow(sheet, r, "Thời gian giao ngắn nhất (giây)", s.deliveryTime().minSeconds());
        r = addSheetRow(sheet, r, "Thời gian giao dài nhất (giây)", s.deliveryTime().maxSeconds());
        sheet.setColumnWidth(0, 9000); sheet.setColumnWidth(1, 5000);
    }

    private void createDailySheet(Workbook wb, List<DailyOrderStats> daily) {
        Sheet sheet = wb.createSheet("Theo ngày");
        Row header = sheet.createRow(0);
        String[] headers = {"Ngày", "Tổng", "Chờ", "Đang xử lý", "Đã giao", "Thất bại", "Đang hoàn", "Đã hoàn", "Hủy", "Từ chối"};
        for (int i = 0; i < headers.length; i++) safeText(header.createCell(i), headers[i]);
        styleHeader(wb, header);
        for (int i = 0; i < daily.size(); i++) {
            var d = daily.get(i); var c = d.orders(); Row row = sheet.createRow(i + 1); int col = 0;
            safeText(row.createCell(col++), d.date().toString());
            row.createCell(col++).setCellValue(c.total()); row.createCell(col++).setCellValue(c.pending());
            row.createCell(col++).setCellValue(c.inProgress()); row.createCell(col++).setCellValue(c.delivered());
            row.createCell(col++).setCellValue(c.failed()); row.createCell(col++).setCellValue(c.returning());
            row.createCell(col++).setCellValue(c.returned()); row.createCell(col++).setCellValue(c.cancelled());
            row.createCell(col).setCellValue(c.rejected());
        }
        sheet.createFreezePane(0, 1);
        for (int i = 0; i < headers.length; i++) sheet.autoSizeColumn(i);
    }

    private void createStationSheet(Workbook wb, List<StationLoadStat> stations) {
        Sheet sheet = wb.createSheet("Tải trạm");
        Row header = sheet.createRow(0);
        String[] headers = {"Trạm", "Sức chứa", "Đã đặt", "Còn trống", "Đang xử lý", "Sử dụng", "Bắt đầu khung giờ", "Kết thúc khung giờ"};
        for (int i = 0; i < headers.length; i++) safeText(header.createCell(i), headers[i]);
        styleHeader(wb, header);
        for (int i = 0; i < stations.size(); i++) {
            var s = stations.get(i); Row row = sheet.createRow(i + 1); int col = 0;
            safeText(row.createCell(col++), s.name()); row.createCell(col++).setCellValue(s.capacity());
            row.createCell(col++).setCellValue(s.booked()); row.createCell(col++).setCellValue(s.free());
            row.createCell(col++).setCellValue(s.processing());
            if (s.utilization() != null) row.createCell(col++).setCellValue(s.utilization());
            else row.createCell(col++).setBlank();
            safeText(row.createCell(col++), s.slotStart().atZone(StatisticsPeriod.ZONE).toString());
            safeText(row.createCell(col), s.slotEnd().atZone(StatisticsPeriod.ZONE).toString());
        }
        sheet.createFreezePane(0, 1);
        for (int i = 0; i < headers.length; i++) sheet.autoSizeColumn(i);
    }

    private int addSheetRow(Sheet sheet, int rowIndex, String label, Object value) {
        Row row = sheet.createRow(rowIndex);
        safeText(row.createCell(0), label);
        if (value == null) row.createCell(1).setBlank();
        else if (value instanceof Number n) row.createCell(1).setCellValue(n.doubleValue());
        else safeText(row.createCell(1), value.toString());
        return rowIndex + 1;
    }

    private void styleHeader(Workbook wb, Row row) {
        CellStyle style = wb.createCellStyle();
        style.setFillForegroundColor(IndexedColors.LIGHT_BLUE.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setBorderBottom(BorderStyle.THIN);
        for (Cell cell : row) cell.setCellStyle(style);
    }

    private static void safeText(Cell cell, String text) {
        String value = safe(text);
        if (value.startsWith("=") || value.startsWith("+") || value.startsWith("-") || value.startsWith("@")) {
            value = "'" + value;
        }
        cell.setCellValue(value);
    }

    private static String safe(String value) { return value == null ? "" : value; }

    private static String formatInstant(Instant instant) {
        return ZonedDateTime.ofInstant(instant, StatisticsPeriod.ZONE).format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss"));
    }

    private static String metadataText(OrderStatsSummary s, StatisticsPeriod p, Instant generatedAt) {
        return "Khoảng thời gian: " + p.from() + " đến " + p.to()
                + " | Múi giờ: " + s.timezone() + " | Mốc ngày: " + s.dateBasis()
                + " | Trạm: " + (p.stationId() == null ? "toàn phạm vi được phân công" : p.stationId())
                + " | Thời điểm xuất: " + formatInstant(generatedAt);
    }

    private static Font pdfFont(String path, float size) throws Exception { return pdfFont(path, size, Font.NORMAL); }

    private static Font pdfFont(String path, float size, int style) throws Exception {
        try (var input = ReportExportService.class.getResourceAsStream(path)) {
            if (input == null) throw new IllegalStateException("Không tìm thấy font PDF: " + path);
            byte[] bytes = input.readAllBytes();
            BaseFont base = BaseFont.createFont("NotoSans.ttf", BaseFont.IDENTITY_H, true,
                    true, bytes, null);
            return new Font(base, size, style);
        }
    }

    private static void addPdfRow(PdfPTable table, String label, String value, Font font, boolean header) {
        addPdfCell(table, label, font, header); addPdfCell(table, value, font, header);
    }

    private static void addPdfCell(PdfPTable table, String value, Font font, boolean header) {
        PdfPCell cell = new PdfPCell(new Phrase(safe(value), font));
        cell.setPadding(4);
        if (header) { cell.setGrayFill(0.85f); cell.setHorizontalAlignment(Element.ALIGN_CENTER); }
        table.addCell(cell);
    }

    private static String percent(Double value) { return value == null ? "Không có dữ liệu" : String.format(java.util.Locale.US, "%.2f%%", value * 100); }
    private static String seconds(Double value) { return value == null ? "Không có dữ liệu" : String.format(java.util.Locale.US, "%.2f", value); }
}
