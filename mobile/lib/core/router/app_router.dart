import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../../features/addresses/address_screens.dart';
import '../../features/auth/session.dart';
import '../../features/assistant/assistant_screen.dart';
import '../../features/auth/screens.dart';
import '../../features/dispatcher/dispatcher_screens.dart';
import '../../features/notifications/notification_screens.dart';
import '../../features/operator/operator_screens.dart';
import '../../features/operator/pickup_screens.dart';
import '../../features/orders/customer_dashboard.dart';
import '../../features/orders/order_detail_screen.dart';
import '../../features/orders/order_form_screen.dart';
import '../../features/orders/order_list_screen.dart';
import '../../features/orders/tracking_screen.dart';

String? sessionRedirect(Session session, String path) {
  if (session.phase == SessionPhase.restoring ||
      session.phase == SessionPhase.unavailable) {
    return path == '/session' ? null : '/session';
  }
  if (session.phase == SessionPhase.signedOut) {
    return path == '/login' ? null : '/login';
  }
  final user = session.user!;
  if (!user.supported) return path == '/unsupported' ? null : '/unsupported';
  if (path == '/profile') return null;
  if (path == user.home || path.startsWith('${user.home}/')) return null;
  return user.home;
}

final routerProvider = Provider<GoRouter>((ref) {
  final session = ref.read(sessionProvider);
  final router = GoRouter(
    initialLocation: '/session',
    refreshListenable: session,
    redirect: (context, state) => sessionRedirect(session, state.uri.path),
    routes: [
      GoRoute(path: '/session', builder: (_, __) => const SessionScreen()),
      GoRoute(path: '/login', builder: (_, __) => const LoginScreen()),
      GoRoute(
          path: '/customer',
          builder: (_, __) => const CustomerDashboardScreen(),
          routes: [
            GoRoute(path: 'orders', builder: (_, __) => const OrderListScreen()),
            GoRoute(path: 'assistant', builder: (_, __) => const AssistantScreen()),
            GoRoute(
                path: 'orders/:id',
                builder: (_, state) =>
                    OrderTrackingScreen(orderId: state.pathParameters['id']!)),
            GoRoute(
                path: 'orders/:id/detail',
                builder: (_, state) =>
                    OrderDetailScreen(orderId: state.pathParameters['id']!)),
            GoRoute(
                path: 'new-order',
                builder: (_, __) => const OrderFormScreen()),
            GoRoute(
                path: 'addresses',
                builder: (_, state) => AddressListScreen(
                    pick: state.uri.queryParameters['pick'] == '1',
                    selectedId: state.uri.queryParameters['selected'])),
            GoRoute(
                path: 'notifications',
                builder: (_, __) => const NotificationsScreen()),
          ]),
      GoRoute(
          path: '/operator',
          builder: (_, __) => const OperatorHomeScreen(),
          routes: [
            GoRoute(
                path: 'receipts/:id',
                builder: (_, state) =>
                    ReceiptDetailScreen(orderId: state.pathParameters['id']!)),
            GoRoute(
                path: 'pickups/:id',
                builder: (_, state) =>
                    PickupConfirmScreen(orderId: state.pathParameters['id']!)),
            GoRoute(
                path: 'returns/:id',
                builder: (_, state) =>
                    ReturnDetailScreen(orderId: state.pathParameters['id']!)),
            GoRoute(path: 'scan', builder: (_, __) => const QrScanScreen()),
          ]),
      GoRoute(
          path: '/dispatcher',
          builder: (_, __) => const DispatcherHomeScreen(),
          routes: [
            GoRoute(
                path: 'orders/:id',
                builder: (_, state) =>
                    DispatcherOrderScreen(orderId: state.pathParameters['id']!)),
          ]),
      GoRoute(path: '/profile', builder: (_, __) => const ProfileScreen()),
      GoRoute(
          path: '/unsupported', builder: (_, __) => const UnsupportedScreen()),
    ],
    errorBuilder: (context, state) => Scaffold(
        body: Center(
            child: TextButton(
                onPressed: () => context.go('/session'),
                child: const Text('Về trang chính')))),
  );
  ref.onDispose(router.dispose);
  return router;
});
