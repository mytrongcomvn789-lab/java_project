import 'package:flutter/material.dart';
import '../../core/ui/async_state_view.dart';
import '../../core/ui/demo_badge.dart';
import '../orders/customer_nav.dart';

/// Trợ lý AI (màn 5). Hiện là màn giữ chỗ: backend chưa có `POST /v1/assistant/messages`. Sẽ được
/// thay bằng khung chat đầy đủ (dữ liệu mẫu có nhãn Demo) ở bước sau.
class AssistantScreen extends StatelessWidget {
  const AssistantScreen({super.key});

  @override
  Widget build(BuildContext context) => Scaffold(
      appBar: AppBar(
          title: const Row(children: [
            Text('Trợ lý Drone AI'),
            SizedBox(width: 8),
            DemoBadge(),
          ])),
      body: const AsyncStateView(
          isEmpty: true,
          emptyIcon: Icons.smart_toy_outlined,
          emptyTitle: 'Trợ lý AI sắp có',
          emptyHint:
              'Chức năng hỏi đáp về đơn hàng đang được xây dựng và sẽ xuất hiện ở đây.',
          child: SizedBox.shrink()),
      bottomNavigationBar: const CustomerBottomNav(selectedIndex: 3));
}
