import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'core/router/app_router.dart';
import 'features/auth/screens.dart';
import 'features/notifications/realtime_host.dart';

void main() => runApp(const ProviderScope(child: MyApp()));

class MyApp extends ConsumerWidget {
  const MyApp({super.key});
  @override
  Widget build(BuildContext context, WidgetRef ref) => MaterialApp.router(
        title: 'SmartDroneDelivery',
        scaffoldMessengerKey: appMessengerKey,
        debugShowCheckedModeBanner: false,
        theme: ThemeData(
            useMaterial3: true,
            colorSchemeSeed: const Color(0xff087e8b),
            inputDecorationTheme:
                const InputDecorationTheme(border: OutlineInputBorder())),
        routerConfig: ref.watch(routerProvider),
        builder: (context, child) => RealtimeHost(child: child ?? const SizedBox()),
      );
}
