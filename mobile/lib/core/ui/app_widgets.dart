import 'package:flutter/material.dart';
import '../theme/app_theme.dart';

/// Thanh sức chứa của trạm trên nền tối: "đang giữ / tối đa" số lịch giao của khung giờ hiện
/// tại. Teal khi còn nhiều chỗ, hổ phách khi gần đầy (≥ 80%), đỏ nhạt khi đã đầy.
class StationCapacityBar extends StatelessWidget {
  const StationCapacityBar(
      {super.key,
      required this.used,
      required this.capacity,
      this.label = 'Sức chứa khung giờ',
      this.onDark = true});
  final int used, capacity;
  final String label;

  /// true: dùng trên nền tối (header); false: trên nền trắng (danh sách trạm).
  final bool onDark;

  @override
  Widget build(BuildContext context) {
    final ratio = capacity <= 0 ? 0.0 : (used / capacity).clamp(0.0, 1.0);
    final color = ratio >= 1
        ? (onDark ? const Color(0xffff8a8f) : const Color(0xffd32f2f))
        : ratio >= 0.8
            ? AppColors.amber
            : (onDark ? AppColors.tealBright : AppColors.primary);
    final text = capacity <= 0 ? 'Chưa có dữ liệu' : '$used/$capacity';
    final muted = onDark ? Colors.white70 : AppColors.textMuted;
    final strong = onDark ? Colors.white : AppColors.text;
    return Semantics(
        label: '$label: $text',
        excludeSemantics: true,
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Row(children: [
            Expanded(
                child: Text(label,
                    style: TextStyle(
                        color: muted, fontSize: 12, fontWeight: FontWeight.w500))),
            Text(text,
                style: TextStyle(
                    color: strong, fontSize: 12, fontWeight: FontWeight.w700)),
          ]),
          const SizedBox(height: 6),
          ClipRRect(
              borderRadius: BorderRadius.circular(AppRadii.pill),
              child: LinearProgressIndicator(
                  value: ratio,
                  minHeight: 8,
                  backgroundColor: onDark ? Colors.white24 : AppColors.border,
                  valueColor: AlwaysStoppedAnimation(color))),
        ]));
  }
}

/// Ô thống kê trên nền tối: số lớn và nhãn nhỏ.
class StatTile extends StatelessWidget {
  const StatTile({super.key, required this.value, required this.label});

  /// Rỗng khi chưa tải được: hiện "—" thay vì số 0 sai.
  final String value, label;

  @override
  Widget build(BuildContext context) => Semantics(
      label: '$label: $value',
      excludeSemantics: true,
      child: Container(
          padding: const EdgeInsets.symmetric(vertical: 12, horizontal: 10),
          decoration: BoxDecoration(
              color: Colors.white.withValues(alpha: 0.10),
              borderRadius: BorderRadius.circular(16)),
          child: Column(children: [
            Text(value.isEmpty ? '—' : value,
                style: const TextStyle(
                    color: Colors.white, fontSize: 22, fontWeight: FontWeight.w800)),
            const SizedBox(height: 2),
            Text(label,
                textAlign: TextAlign.center,
                style: const TextStyle(
                    color: Colors.white70, fontSize: 12, fontWeight: FontWeight.w500)),
          ])));
}

class NavItem {
  const NavItem(this.icon, this.label, {this.key});
  final IconData icon;
  final String label;
  final Key? key;
}

/// Thanh điều hướng dưới cao 84dp. Mục ở vị trí [centerIndex] là nút tròn nổi 56dp (Tạo đơn của
/// khách, Quét QR của operator). Vùng chạm mỗi mục ≥ 44dp, có nhãn cho trình đọc màn hình.
class AppBottomNav extends StatelessWidget {
  const AppBottomNav(
      {super.key,
      required this.items,
      required this.selectedIndex,
      required this.onTap,
      this.centerIndex});
  final List<NavItem> items;
  final int selectedIndex;
  final ValueChanged<int> onTap;
  final int? centerIndex;

  @override
  Widget build(BuildContext context) {
    // Thanh cao 84dp chưa kể vùng thanh cử chỉ của hệ thống; cỡ chữ bị chặn trần để nhãn không
    // tràn khỏi chiều cao cố định khi người dùng phóng to chữ.
    final inset = MediaQuery.paddingOf(context).bottom;
    return MediaQuery.withClampedTextScaling(
        maxScaleFactor: 1.15,
        child: Container(
            height: 84 + inset,
            padding: EdgeInsets.only(bottom: inset),
            decoration: const BoxDecoration(
                color: AppColors.surface,
                boxShadow: [
                  BoxShadow(
                      color: Color(0x1a073b45), blurRadius: 18, offset: Offset(0, -4))
                ]),
            child: centerIndex == null ? _plain() : _withCenter(centerIndex!)));
  }

  Widget _plain() => Row(children: [
        for (var i = 0; i < items.length; i++) Expanded(child: _tab(items[i], i)),
      ]);

  /// Nút giữa luôn nằm đúng giữa màn hình: hai bên dành chung một bề rộng (bên nhiều mục
  /// hơn quyết định), mục ít hơn được dàn đều trong phần của nó.
  Widget _withCenter(int c) {
    final left = [for (var i = 0; i < c; i++) i];
    final right = [for (var i = c + 1; i < items.length; i++) i];
    final side = left.length > right.length ? left.length : right.length;
    Widget group(List<int> idx) => Expanded(
        flex: side,
        child: Row(children: [for (final i in idx) Expanded(child: _tab(items[i], i))]));
    return Row(children: [
      group(left),
      Expanded(child: _center(items[c], c)),
      group(right),
    ]);
  }

  Widget _tab(NavItem item, int i) {
    final selected = i == selectedIndex;
    final color = selected ? AppColors.primary : AppColors.textMuted;
    return Semantics(
        button: true,
        selected: selected,
        label: item.label,
        excludeSemantics: true,
        child: InkWell(
            key: item.key,
            onTap: () => onTap(i),
            child: ConstrainedBox(
                constraints: const BoxConstraints(minHeight: 44, minWidth: 44),
                child: Column(mainAxisAlignment: MainAxisAlignment.center, children: [
                  Icon(item.icon, color: color, size: 26),
                  const SizedBox(height: 4),
                  Text(item.label,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(
                          color: color,
                          fontSize: 11,
                          fontWeight: selected ? FontWeight.w700 : FontWeight.w500)),
                ]))));
  }

  Widget _center(NavItem item, int i) => Semantics(
      button: true,
      label: item.label,
      excludeSemantics: true,
      child: Transform.translate(
          offset: const Offset(0, -14),
          child: Column(mainAxisAlignment: MainAxisAlignment.center, children: [
            Material(
                color: AppColors.primary,
                shape: const CircleBorder(),
                elevation: 6,
                shadowColor: const Color(0x66087e8b),
                child: InkWell(
                    key: item.key,
                    customBorder: const CircleBorder(),
                    onTap: () => onTap(i),
                    child: SizedBox(
                        width: 56,
                        height: 56,
                        child: Icon(item.icon, color: Colors.white, size: 28)))),
            const SizedBox(height: 4),
            Text(item.label,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(
                    color: AppColors.textMuted, fontSize: 11, fontWeight: FontWeight.w500)),
          ])));
}

/// Thẻ thông tin có tiêu đề (Lộ trình, Kiện hàng…): nền trắng, bo góc, bóng nhẹ.
class InfoCard extends StatelessWidget {
  const InfoCard(this.title, this.rows, {super.key, this.trailing});
  final String title;
  final List<Widget> rows;

  /// Phần phụ bên phải tiêu đề (ví dụ nhãn Demo).
  final Widget? trailing;

  @override
  Widget build(BuildContext context) => Padding(
      padding: const EdgeInsets.only(bottom: 12),
      // Bóng ở lớp ngoài, nền ở Material bên trong để ô tích/ô nhập con vẫn thấy hiệu ứng chạm.
      child: DecoratedBox(
          decoration: BoxDecoration(
              borderRadius: BorderRadius.circular(AppRadii.card), boxShadow: cardShadow),
          child: Material(
              color: AppColors.surface,
              borderRadius: BorderRadius.circular(AppRadii.card),
              clipBehavior: Clip.antiAlias,
              child: Padding(
                  padding: const EdgeInsets.all(16),
                  child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                    Row(children: [
                      Expanded(
                          child: Text(title,
                              style: Theme.of(context).textTheme.titleSmall?.copyWith(
                                  fontWeight: FontWeight.w700, color: AppColors.primary))),
                      if (trailing != null) trailing!,
                    ]),
                    const SizedBox(height: 10),
                    ...rows,
                  ])))));
}

/// Một dòng "nhãn: giá trị" trong [InfoCard].
class InfoRow extends StatelessWidget {
  const InfoRow(this.label, this.value, {super.key});
  final String label, value;

  @override
  Widget build(BuildContext context) => Padding(
      padding: const EdgeInsets.symmetric(vertical: 3),
      child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
        SizedBox(
            width: 104,
            child: Text(label,
                style: const TextStyle(color: AppColors.textMuted, fontSize: 13))),
        Expanded(
            child: Text(value, style: const TextStyle(fontWeight: FontWeight.w500))),
      ]));
}

/// Banner thông báo có nút đóng, bo góc, nằm trên cùng màn hình.
class MessageBanner extends StatelessWidget {
  const MessageBanner(
      {super.key, required this.text, required this.color, required this.onClose});
  final String text;
  final Color color;
  final VoidCallback onClose;

  @override
  Widget build(BuildContext context) => Container(
      width: double.infinity,
      margin: const EdgeInsets.fromLTRB(16, 12, 16, 0),
      padding: const EdgeInsets.only(left: 14, top: 4, bottom: 4),
      decoration:
          BoxDecoration(color: color, borderRadius: BorderRadius.circular(14)),
      child: Row(children: [
        Expanded(child: Text(text)),
        IconButton(tooltip: 'Đóng', onPressed: onClose, icon: const Icon(Icons.close)),
      ]));
}

/// Thanh hành động cố định ở đáy màn hình (nút chính luôn trong tầm ngón tay cái). Tự chừa vùng
/// thanh cử chỉ của hệ thống.
class BottomActionBar extends StatelessWidget {
  const BottomActionBar({super.key, required this.child, this.hint});
  final Widget child;

  /// Dòng nhắc nhỏ phía trên nút (ví dụ "Chưa quét mã QR.").
  final Widget? hint;

  @override
  Widget build(BuildContext context) => Container(
      padding: EdgeInsets.fromLTRB(16, 12, 16, 12 + MediaQuery.paddingOf(context).bottom),
      decoration: const BoxDecoration(color: AppColors.surface, boxShadow: [
        BoxShadow(color: Color(0x1a073b45), blurRadius: 18, offset: Offset(0, -4))
      ]),
      child: Column(mainAxisSize: MainAxisSize.min, children: [
        if (hint != null) Padding(padding: const EdgeInsets.only(bottom: 8), child: hint),
        child,
      ]));
}
