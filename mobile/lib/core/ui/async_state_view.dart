import 'package:flutter/material.dart';
import '../network/api_failure.dart';
import '../theme/app_theme.dart';

/// Khối xám bo góc làm "khung xương" khi đang tải.
class SkeletonBox extends StatelessWidget {
  const SkeletonBox({super.key, this.width, this.height = 16, this.radius = 8});
  final double? width;
  final double height, radius;

  @override
  Widget build(BuildContext context) => Container(
      width: width,
      height: height,
      decoration: BoxDecoration(
          color: AppColors.border.withValues(alpha: 0.7),
          borderRadius: BorderRadius.circular(radius)));
}

/// Khung chung cho mọi vùng gọi API, đủ 4 trạng thái theo đặc tả: đang tải (khung xương), rỗng
/// (icon + câu hướng dẫn), lỗi (có "Thử lại") và mất mạng. Dùng một chỗ để mọi màn nhất quán và
/// chỉ cần test một lần.
class AsyncStateView extends StatelessWidget {
  const AsyncStateView(
      {super.key,
      required this.child,
      this.loading = false,
      this.error,
      this.isEmpty = false,
      this.onRetry,
      this.emptyIcon = Icons.inbox_outlined,
      this.emptyTitle = 'Chưa có dữ liệu',
      this.emptyHint,
      this.skeleton});

  /// Nội dung thật, chỉ hiện khi không tải, không lỗi, không rỗng.
  final Widget child;
  final bool loading, isEmpty;
  final ApiFailure? error;
  final VoidCallback? onRetry;
  final IconData emptyIcon;
  final String emptyTitle;
  final String? emptyHint;

  /// Khung xương riêng của vùng; mặc định là vài khối xám.
  final Widget? skeleton;

  @override
  Widget build(BuildContext context) {
    if (loading) {
      return KeyedSubtree(
          key: const Key('state-loading'),
          child: skeleton ?? const _DefaultSkeleton());
    }
    final failure = error;
    if (failure != null) {
      final offline = failure.kind == FailureKind.offline;
      return _Message(
          key: const Key('state-error'),
          icon: offline ? Icons.wifi_off_rounded : Icons.error_outline,
          title: offline ? 'Không có kết nối mạng' : 'Không tải được dữ liệu',
          hint: failure.message,
          onRetry: onRetry);
    }
    if (isEmpty) {
      return _Message(
          key: const Key('state-empty'),
          icon: emptyIcon,
          title: emptyTitle,
          hint: emptyHint);
    }
    return child;
  }
}

class _DefaultSkeleton extends StatelessWidget {
  const _DefaultSkeleton();
  @override
  Widget build(BuildContext context) => const ExcludeSemantics(
      child: Padding(
          padding: EdgeInsets.all(16),
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            SkeletonBox(width: 160, height: 18),
            SizedBox(height: 12),
            SkeletonBox(height: 14),
            SizedBox(height: 8),
            SkeletonBox(width: 220, height: 14),
          ])));
}

class _Message extends StatelessWidget {
  const _Message(
      {super.key, required this.icon, required this.title, this.hint, this.onRetry});
  final IconData icon;
  final String title;
  final String? hint;
  final VoidCallback? onRetry;

  @override
  Widget build(BuildContext context) => Center(
      child: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(mainAxisSize: MainAxisSize.min, children: [
            Container(
                width: 72,
                height: 72,
                decoration: BoxDecoration(
                    color: AppColors.primary.withValues(alpha: 0.10),
                    shape: BoxShape.circle),
                child: Icon(icon, size: 34, color: AppColors.primary)),
            const SizedBox(height: 16),
            Text(title,
                textAlign: TextAlign.center,
                style: Theme.of(context)
                    .textTheme
                    .titleMedium
                    ?.copyWith(fontWeight: FontWeight.w700)),
            if (hint != null && hint!.isNotEmpty) ...[
              const SizedBox(height: 6),
              Text(hint!,
                  textAlign: TextAlign.center,
                  style: const TextStyle(color: AppColors.textMuted, height: 1.4)),
            ],
            if (onRetry != null) ...[
              const SizedBox(height: 16),
              FilledButton(
                  key: const Key('state-retry'),
                  onPressed: onRetry,
                  style: FilledButton.styleFrom(minimumSize: const Size(140, 48)),
                  child: const Text('Thử lại')),
            ],
          ])));
}
