import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import '../../core/ui/app_widgets.dart';

/// Thanh điều hướng 5 mục của khách: Trang chủ, Đơn hàng, Tạo đơn (nút tròn giữa), Trợ lý AI, Hồ sơ.
/// Dùng chung cho mọi màn cấp cao của khách; [selectedIndex] chọn mục đang sáng.
class CustomerBottomNav extends StatelessWidget {
  const CustomerBottomNav({super.key, required this.selectedIndex});
  final int selectedIndex;

  static const items = [
    NavItem(Icons.home_outlined, 'Trang chủ', key: Key('nav-home')),
    NavItem(Icons.receipt_long_outlined, 'Đơn hàng', key: Key('nav-orders')),
    NavItem(Icons.add, 'Tạo đơn', key: Key('nav-new-order')),
    NavItem(Icons.smart_toy_outlined, 'Trợ lý AI', key: Key('nav-assistant')),
    NavItem(Icons.person_outline, 'Hồ sơ', key: Key('nav-profile')),
  ];

  @override
  Widget build(BuildContext context) => AppBottomNav(
      items: items,
      selectedIndex: selectedIndex,
      centerIndex: 2,
      onTap: (i) {
        switch (i) {
          case 0:
            context.go('/customer');
          case 1:
            context.go('/customer/orders');
          case 2:
            context.push('/customer/new-order');
          case 3:
            context.go('/customer/assistant');
          case 4:
            context.go('/profile');
        }
      });
}
