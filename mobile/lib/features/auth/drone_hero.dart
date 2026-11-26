import 'dart:math' as math;
import 'package:flutter/material.dart';
import '../../core/theme/app_theme.dart';

/// Hình nền của màn đăng nhập: lưới bản đồ mờ, đường bay nét đứt màu hổ phách và một drone 4
/// cánh. Chỉ là trang trí (vẽ bằng code, không dùng ảnh) nên bị loại khỏi cây trợ năng.
class DroneHero extends StatelessWidget {
  const DroneHero({super.key});

  @override
  Widget build(BuildContext context) => ExcludeSemantics(
      child: SizedBox.expand(child: CustomPaint(painter: _HeroPainter())));
}

class _HeroPainter extends CustomPainter {
  const _HeroPainter();

  @override
  void paint(Canvas canvas, Size size) {
    canvas.drawRect(Offset.zero & size, Paint()..color = AppColors.dark);
    _grid(canvas, size);
    final path = _flightPath(size);
    _dashed(canvas, path);
    _pin(canvas, Offset(size.width * 0.14, size.height * 0.80));
    _pin(canvas, Offset(size.width * 0.86, size.height * 0.30), filled: true);
    _drone(canvas, Offset(size.width * 0.55, size.height * 0.52), size.width * 0.11);
  }

  void _grid(Canvas canvas, Size size) {
    final line = Paint()
      ..color = Colors.white.withValues(alpha: 0.06)
      ..strokeWidth = 1;
    const step = 28.0;
    for (var x = 0.0; x < size.width; x += step) {
      canvas.drawLine(Offset(x, 0), Offset(x, size.height), line);
    }
    for (var y = 0.0; y < size.height; y += step) {
      canvas.drawLine(Offset(0, y), Offset(size.width, y), line);
    }
    // vài "con đường" lớn và một vùng sáng như công viên trên bản đồ
    final road = Paint()
      ..color = Colors.white.withValues(alpha: 0.09)
      ..strokeWidth = 5
      ..strokeCap = StrokeCap.round;
    canvas.drawLine(Offset(0, size.height * 0.62), Offset(size.width, size.height * 0.40), road);
    canvas.drawLine(Offset(size.width * 0.32, 0), Offset(size.width * 0.46, size.height), road);
    canvas.drawCircle(Offset(size.width * 0.80, size.height * 0.78), size.width * 0.13,
        Paint()..color = AppColors.tealBright.withValues(alpha: 0.10));
  }

  Path _flightPath(Size size) => Path()
    ..moveTo(size.width * 0.14, size.height * 0.80)
    ..cubicTo(size.width * 0.30, size.height * 0.28, size.width * 0.62, size.height * 0.80,
        size.width * 0.86, size.height * 0.30);

  void _dashed(Canvas canvas, Path path) {
    final paint = Paint()
      ..color = AppColors.amber
      ..style = PaintingStyle.stroke
      ..strokeWidth = 3
      ..strokeCap = StrokeCap.round;
    for (final metric in path.computeMetrics()) {
      var d = 0.0;
      while (d < metric.length) {
        canvas.drawPath(metric.extractPath(d, math.min(d + 10, metric.length)), paint);
        d += 18;
      }
    }
  }

  void _pin(Canvas canvas, Offset c, {bool filled = false}) {
    canvas.drawCircle(c, 11, Paint()..color = AppColors.amber.withValues(alpha: 0.25));
    canvas.drawCircle(
        c,
        6,
        Paint()
          ..color = filled ? AppColors.amber : AppColors.dark
          ..style = PaintingStyle.fill);
    canvas.drawCircle(
        c,
        6,
        Paint()
          ..color = AppColors.amber
          ..style = PaintingStyle.stroke
          ..strokeWidth = 2.5);
  }

  void _drone(Canvas canvas, Offset c, double r) {
    final shadow = Paint()..color = Colors.black.withValues(alpha: 0.22);
    canvas.drawOval(Rect.fromCenter(center: c.translate(0, r * 1.9), width: r * 2.4, height: r * 0.5), shadow);
    final arm = Paint()
      ..color = Colors.white
      ..strokeWidth = r * 0.22
      ..strokeCap = StrokeCap.round;
    final rotor = Paint()..color = Colors.white.withValues(alpha: 0.28);
    final rotorEdge = Paint()
      ..color = Colors.white
      ..style = PaintingStyle.stroke
      ..strokeWidth = 2;
    for (final (dx, dy) in const [(-1.0, -0.8), (1.0, -0.8), (-1.0, 0.8), (1.0, 0.8)]) {
      final tip = c.translate(dx * r * 1.55, dy * r * 1.1);
      canvas.drawLine(c, tip, arm);
      canvas.drawCircle(tip, r * 0.82, rotor);
      canvas.drawCircle(tip, r * 0.82, rotorEdge);
      canvas.drawCircle(tip, r * 0.16, Paint()..color = Colors.white);
    }
    final body = RRect.fromRectAndRadius(
        Rect.fromCenter(center: c, width: r * 1.5, height: r * 1.1), Radius.circular(r * 0.4));
    canvas.drawRRect(body, Paint()..color = Colors.white);
    canvas.drawCircle(c, r * 0.28, Paint()..color = AppColors.amber);
  }

  @override
  bool shouldRepaint(covariant CustomPainter oldDelegate) => false;
}
