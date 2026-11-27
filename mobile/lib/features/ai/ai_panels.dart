import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../orders/order_screens.dart' show formatTime;
import 'ai_errors.dart';
import 'ai_models.dart';
import 'ai_task_controller.dart';

/// Nhãn nguồn kết quả: AI thật / giả lập (MOCK) / dự phòng (FALLBACK) / công thức (FORMULA).
class AiSourceBadge extends StatelessWidget {
  const AiSourceBadge(this.envelope, {super.key});
  final AiEnvelope<Object?> envelope;

  @override
  Widget build(BuildContext context) {
    final info = describeAiSource(envelope);
    final scheme = Theme.of(context).colorScheme;
    final color = switch (info.tone) {
      AiSourceTone.mock => scheme.tertiaryContainer,
      AiSourceTone.fallback => scheme.errorContainer,
      AiSourceTone.formula => scheme.secondaryContainer,
      AiSourceTone.ai => scheme.primaryContainer,
    };
    return Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        key: const Key('ai-source'),
        children: [
          Container(
              padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
              decoration: BoxDecoration(
                  color: color, borderRadius: BorderRadius.circular(999)),
              child: Text(info.label,
                  style: Theme.of(context)
                      .textTheme
                      .labelSmall
                      ?.copyWith(fontWeight: FontWeight.w700))),
          const SizedBox(height: 2),
          Text(info.detail, style: Theme.of(context).textTheme.bodySmall),
        ]);
  }
}

class _AiCard extends StatelessWidget {
  const _AiCard({required this.title, required this.children, super.key});
  final String title;
  final List<Widget> children;
  @override
  Widget build(BuildContext context) => Padding(
      padding: const EdgeInsets.only(bottom: 16),
      child: Card(
          margin: EdgeInsets.zero,
          child: Padding(
              padding: const EdgeInsets.all(12),
              child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(title, style: Theme.of(context).textTheme.titleMedium),
                    const SizedBox(height: 8),
                    ...children,
                  ]))));
}

class _AiError extends StatelessWidget {
  const _AiError(this.state, this.onRetry);
  final AiTaskState<Object?> state;
  final VoidCallback onRetry;
  @override
  Widget build(BuildContext context) {
    final e = state.error!;
    return Column(
        key: const Key('ai-error'),
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(e.message),
          if (state.canRetry)
            TextButton(
                key: const Key('ai-retry'),
                onPressed: onRetry,
                child: const Text('Thử lại'))
          else if (e.retryable)
            Text('Đã thử nhiều lần không được. Hãy thử lại sau ít phút.',
                style: Theme.of(context).textTheme.bodySmall),
        ]);
  }
}

const _etaReasons = {
  'STATUS_NOT_APPLICABLE':
      'Trạng thái hiện tại của đơn không áp dụng ước tính thời gian giao.',
  'COORDINATES_MISSING': 'Chưa có toạ độ trạm nên chưa ước tính được.',
  'SPEED_INVALID': 'Cấu hình ước tính chưa hợp lệ nên chưa ước tính được.',
  'WAIT_INVALID': 'Cấu hình ước tính chưa hợp lệ nên chưa ước tính được.',
};

const _etaLimits = {
  'STRAIGHT_LINE_DISTANCE':
      'Dùng khoảng cách đường chim bay, không tính đường bay thực tế.',
  'CONSTANT_SPEED_UNCALIBRATED': 'Vận tốc là giả định cố định, chưa hiệu chuẩn.',
  'CONSTANT_STATION_WAIT': 'Thời gian chờ tại trạm là giá trị cố định.',
  'START_CLAMPED_TO_NOW': 'Mốc xuất phát đã qua nên được tính từ hiện tại.',
  'OVERDUE_NO_LIVE_POSITION':
      'Đã quá thời gian ước tính; chưa có vị trí drone thực tế nên chưa biết còn bao lâu.',
  'DEPARTURE_FROM_STATUS_HISTORY': 'Mốc cất cánh lấy từ lịch sử trạng thái.',
};

Widget _etaBody(ThemeData theme, EtaResult? eta) {
  if (eta == null) return const Text('Chưa có kết quả ước tính cho đơn này.');
  if (!eta.applicable || !eta.computed) {
    return Text(_etaReasons[eta.reason] ?? 'Chưa ước tính được thời gian giao.');
  }
  final minutes = eta.etaMinutes;
  return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
    Text('${formatTime(eta.etaAt)}${minutes == null ? '' : ' (khoảng ${formatMinutes(minutes)} nữa)'}',
        key: const Key('ai-eta-value'), style: theme.textTheme.titleSmall),
    Text(
        'Bay khoảng ${eta.travelMinutes == null ? '—' : formatMinutes(eta.travelMinutes!)} · chờ trạm ${eta.waitMinutes == null ? '—' : formatMinutes(eta.waitMinutes!)} · ${eta.distanceKm ?? '—'} km',
        style: theme.textTheme.bodySmall),
    Text('Độ tin cậy: thấp (chỉ là ước tính, không đảm bảo giờ giao).',
        style: theme.textTheme.bodySmall),
    for (final l in eta.limitations)
      Text('• ${_etaLimits[l] ?? l}', style: theme.textTheme.bodySmall),
  ]);
}

/// ETA (7.2) trên chi tiết đơn.
class AiEtaPanel extends ConsumerWidget {
  const AiEtaPanel(this.orderKey, {super.key});
  final AiOrderKey orderKey;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final state = ref.watch(etaProvider(orderKey));
    final controller = ref.read(etaProvider(orderKey).notifier);
    final theme = Theme.of(context);
    final eta = state.envelope?.result;
    return _AiCard(key: const Key('ai-eta'), title: 'Thời gian giao dự kiến (ETA)', children: [
      switch (state.phase) {
        AiPhase.idle || AiPhase.loading => const Text(
            'Đang ước tính thời gian giao…',
            key: Key('ai-loading')),
        AiPhase.error => _AiError(state, controller.start),
        AiPhase.success => _etaBody(theme, eta),
      },
      if (state.envelope != null) ...[
        const SizedBox(height: 8),
        AiSourceBadge(state.envelope!),
      ],
    ]);
  }
}

/// Tóm tắt đơn (7.3). Chỉ chạy khi bấm nút; văn bản hiển thị dạng text thuần.
class AiSummaryPanel extends ConsumerWidget {
  const AiSummaryPanel(this.orderKey, {super.key});
  final AiOrderKey orderKey;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final state = ref.watch(summaryProvider(orderKey));
    final controller = ref.read(summaryProvider(orderKey).notifier);
    final theme = Theme.of(context);
    final result = state.envelope?.result;
    return _AiCard(key: const Key('ai-summary'), title: 'Tóm tắt quá trình giao hàng', children: [
      if (state.phase == AiPhase.idle) ...[
        Text(
            'Tóm tắt các mốc xử lý của đơn này. Chỉ đọc, không thay đổi đơn.',
            style: theme.textTheme.bodySmall),
        const SizedBox(height: 4),
        FilledButton.tonal(
            key: const Key('ai-summary-start'),
            onPressed: controller.start,
            child: const Text('Tạo tóm tắt')),
      ],
      if (state.phase == AiPhase.loading)
        const Text('Đang tạo tóm tắt…', key: Key('ai-loading')),
      if (state.phase == AiPhase.error) _AiError(state, controller.start),
      if (state.phase == AiPhase.success && result == null)
        const Text('Không có nội dung tóm tắt. Vui lòng thử lại sau.'),
      if (state.phase == AiPhase.success && result != null) ...[
        Text(result.summary, key: const Key('ai-summary-text')),
        for (final h in result.highlights) Text('• $h'),
        if (result.timeline.isEmpty)
          Text('Chưa có lịch sử xử lý để tóm tắt.',
              style: theme.textTheme.bodySmall)
        else
          for (final s in result.timeline)
            Text(
                '${s.label} · ${formatTime(s.at)}'
                '${s.minutesSincePrevious == null ? '' : ' (sau ${formatMinutes(s.minutesSincePrevious!)})'}',
                style: theme.textTheme.bodySmall),
        const SizedBox(height: 4),
        Text(
            'Trạng thái chính thức là trạng thái của đơn ở trên, không phải nội dung tóm tắt này.',
            style: theme.textTheme.bodySmall),
        TextButton(
            key: const Key('ai-summary-restart'),
            onPressed: controller.start,
            child: const Text('Tạo lại tóm tắt')),
      ],
      if (state.envelope != null) ...[
        const SizedBox(height: 8),
        AiSourceBadge(state.envelope!),
      ],
    ]);
  }
}

/// Quy đổi số phút sang dạng dễ đọc: dưới 60 phút giữ phút; từ 60 phút là giờ; từ 24 giờ là ngày.
String formatMinutes(int totalMinutes) {
  final min = totalMinutes < 0 ? 0 : totalMinutes;
  if (min < 60) return '$min phút';
  final hours = min ~/ 60, m = min % 60;
  if (hours < 24) return m == 0 ? '$hours giờ' : '$hours giờ $m phút';
  final days = hours ~/ 24, h = hours % 24;
  return h == 0 ? '$days ngày' : '$days ngày $h giờ';
}
