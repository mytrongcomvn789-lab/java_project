import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../../features/auth/session.dart';
import '../../features/auth/screens.dart';
import '../../features/notifications/notification_screens.dart';
import '../../features/orders/order_list_screen.dart';
import '../../features/orders/order_screens.dart';

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
          builder: (_, __) => const CustomerHomeScreen(),
          routes: [
            GoRoute(
                path: 'orders/:id',
                builder: (_, state) =>
                    OrderTrackingScreen(orderId: state.pathParameters['id']!)),
            GoRoute(
                path: 'notifications',
                builder: (_, __) => const NotificationsScreen()),
          ]),
      GoRoute(
          path: '/operator',
          builder: (_, __) => const WorkspaceScreen(operator: true)),
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
