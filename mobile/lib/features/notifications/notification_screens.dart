import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../orders/order_screens.dart';
import 'notifications_controller.dart';

class NotificationsScreen extends ConsumerWidget {
  const NotificationsScreen({super.key});
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final state = ref.watch(notificationsProvider);
    return Scaffold(
        appBar: AppBar(
            title: const Text('Thông báo'),
            leading: IconButton(
                tooltip: 'Quay lại',
                icon: const Icon(Icons.arrow_back),
                onPressed: () => context.go('/customer'))),
        body: Column(children: [
          const ConnectionBanner(),
          Expanded(
              child: RefreshIndicator(
                  onRefresh: () => ref.read(notificationsProvider.notifier).reload(),
                  child: state.items.isEmpty
                      ? ListView(children: [
                          Padding(
                              padding: const EdgeInsets.all(32),
                              child: Text(
                                  state.error
                                      ? 'Không tải được thông báo. Kéo xuống để thử lại.'
                                      : 'Chưa có thông báo nào.',
                                  textAlign: TextAlign.center))
                        ])
                      : ListView.separated(
                          itemCount: state.items.length,
                          separatorBuilder: (_, __) => const Divider(height: 1),
                          itemBuilder: (context, i) {
                            final n = state.items[i];
                            return ListTile(
                                key: Key('notification-${n.eventId}'),
                                leading: Icon(n.unread
                                    ? Icons.mark_email_unread_outlined
                                    : Icons.drafts_outlined),
                                title: Text(n.title,
                                    style: TextStyle(
                                        fontWeight: n.unread
                                            ? FontWeight.bold
                                            : FontWeight.normal)),
                                subtitle: Text('${n.body}\n${formatTime(n.createdAt)}'),
                                isThreeLine: true,
                                onTap: () {
                                  ref.read(notificationsProvider.notifier).markRead(n);
                                  context.go('/customer/orders/${n.orderId}');
                                });
                          }))),
        ]));
  }
}
