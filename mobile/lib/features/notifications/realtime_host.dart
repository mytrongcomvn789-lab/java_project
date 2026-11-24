import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/realtime/realtime_provider.dart';
import '../../core/router/app_router.dart';
import '../auth/screens.dart';
import '../orders/order_screens.dart';
import 'notifications_controller.dart';

/// App-wide glue for realtime: app lifecycle (pause/resume), foreground
/// notification banners, and an opt-in diagnostics line.
class RealtimeHost extends ConsumerStatefulWidget {
  const RealtimeHost({super.key, required this.child});
  final Widget child;
  @override
  ConsumerState<RealtimeHost> createState() => _RealtimeHostState();
}

class _RealtimeHostState extends ConsumerState<RealtimeHost>
    with WidgetsBindingObserver {
  Timer? _diagTimer;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    if (realtimeDiag) {
      _diagTimer = Timer.periodic(
          const Duration(seconds: 1), (_) => mounted ? setState(() {}) : null);
    }
  }

  @override
  void dispose() {
    _diagTimer?.cancel();
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    final client = ref.read(realtimeClientProvider);
    if (client == null) return;
    switch (state) {
      case AppLifecycleState.resumed:
        client.resume();
      case AppLifecycleState.paused || AppLifecycleState.hidden:
        client.pause();
      case AppLifecycleState.inactive || AppLifecycleState.detached:
        break;
    }
  }

  void _showBanner(NotificationsState state) {
    final n = state.incoming;
    if (n == null) return;
    final messenger = appMessengerKey.currentState;
    messenger
      ?..hideCurrentSnackBar()
      ..showSnackBar(SnackBar(
          key: const Key('notification-banner'),
          behavior: SnackBarBehavior.floating,
          duration: const Duration(seconds: 6),
          content: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(n.title, style: const TextStyle(fontWeight: FontWeight.bold)),
                Text(n.body),
              ]),
          action: SnackBarAction(
              label: 'Xem',
              onPressed: () {
                ref.read(notificationsProvider.notifier).markRead(n);
                ref.read(routerProvider).go('/customer/orders/${n.orderId}');
              })));
  }

  @override
  Widget build(BuildContext context) {
    // Keeps the notification subscription alive for the whole customer
    // session and shows live arrivals; rebuilds nothing on its own.
    ref.listen<NotificationsState>(notificationsProvider, (prev, next) {
      if (next.incomingSeq != (prev?.incomingSeq ?? 0)) _showBanner(next);
    });
    // A banner from the previous account must never outlive its session.
    ref.listen<String?>(realtimeUserProvider,
        (_, __) => appMessengerKey.currentState?.clearSnackBars());
    final client = ref.watch(realtimeClientProvider);
    return Stack(children: [
      widget.child,
      if (realtimeDiag && client != null)
        Positioned(
            left: 0,
            right: 0,
            bottom: 0,
            child: IgnorePointer(
                child: Container(
                    color: Colors.black54,
                    padding: const EdgeInsets.all(2),
                    child: Text(
                        'ws=${client.status.name} subs=${client.activeDestinations.length} '
                        '[${client.activeDestinations.map((d) => '${d.split('/')[2]}:${d.substring(d.length - 4)}').join(',')}] '
                        'try=${client.reconnectAttempts}',
                        key: const Key('rt-diag'),
                        textDirection: TextDirection.ltr,
                        style: const TextStyle(
                            fontSize: 10,
                            color: Colors.white,
                            decoration: TextDecoration.none))))),
    ]);
  }
}
