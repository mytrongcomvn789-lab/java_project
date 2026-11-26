import 'package:flutter/material.dart';
import '../theme/app_theme.dart';

/// Nhãn "Demo" cho dữ liệu mẫu hiển thị khi backend chưa có API tương ứng. Bắt buộc gắn vào mọi
/// vùng dùng dữ liệu mẫu để không ai hiểu nhầm là dữ liệu thật.
class DemoBadge extends StatelessWidget {
  const DemoBadge({super.key, this.onDark = false});

  /// true khi đặt trên nền tối (thẻ #073B45).
  final bool onDark;

  @override
  Widget build(BuildContext context) => Semantics(
      label: 'Dữ liệu mẫu, chưa phải dữ liệu thật',
      excludeSemantics: true,
      child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
          decoration: BoxDecoration(
              color: onDark ? AppColors.amber : StatusPalette.pending.background,
              borderRadius: BorderRadius.circular(AppRadii.pill)),
          child: Text('Demo',
              style: TextStyle(
                  fontSize: 11,
                  fontWeight: FontWeight.w800,
                  color: onDark ? AppColors.dark : StatusPalette.pending.foreground))));
}
