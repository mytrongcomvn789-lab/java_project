import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../features/home/providers/home_provider.dart';
import 'package:go_router/go_router.dart';
import 'package:flutter/material.dart';

final appRouter = GoRouter(
  initialLocation: '/',
  routes: [
    GoRoute(
      path: '/',
      builder: (context, state) => const HomePage(),
    ),
  ],
);

class HomePage extends ConsumerWidget {
  const HomePage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final message = ref.watch(homeMessageProvider);

    return Scaffold(
      body: Center(
        child: Text(message),
      ),
    );
  }
}

