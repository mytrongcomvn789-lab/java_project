import 'package:flutter/material.dart';

/// Màu chủ đạo của app (xanh ngọc). Mọi màu khác sinh ra từ đây bằng Material 3.
const brandColor = Color(0xff087e8b);

/// Khoảng cách dùng chung, để các màn hình thưa/dày đều nhau.
const gapS = 8.0, gapM = 16.0, gapL = 24.0;

/// Theme chung: bo góc 12, thẻ phẳng có viền mảnh, nút cao 48 dễ bấm bằng một tay, AppBar phẳng.
/// Đổi ở đây là đổi cho cả app; màn hình không nên tự đặt màu hay bo góc riêng.
ThemeData buildAppTheme() {
  final scheme = ColorScheme.fromSeed(seedColor: brandColor);
  final radius = BorderRadius.circular(12);
  return ThemeData(
    useMaterial3: true,
    colorScheme: scheme,
    scaffoldBackgroundColor: scheme.surfaceContainerLowest,
    appBarTheme: AppBarTheme(
        backgroundColor: scheme.surface,
        surfaceTintColor: Colors.transparent,
        scrolledUnderElevation: 0,
        elevation: 0,
        centerTitle: false,
        titleTextStyle: TextStyle(
            color: scheme.onSurface, fontSize: 20, fontWeight: FontWeight.w600)),
    cardTheme: CardThemeData(
        elevation: 0,
        margin: EdgeInsets.zero,
        color: scheme.surface,
        surfaceTintColor: Colors.transparent,
        shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(14),
            side: BorderSide(color: scheme.outlineVariant))),
    filledButtonTheme: FilledButtonThemeData(
        style: FilledButton.styleFrom(
            minimumSize: const Size.fromHeight(48),
            shape: RoundedRectangleBorder(borderRadius: radius))),
    outlinedButtonTheme: OutlinedButtonThemeData(
        style: OutlinedButton.styleFrom(
            minimumSize: const Size.fromHeight(48),
            shape: RoundedRectangleBorder(borderRadius: radius))),
    inputDecorationTheme: InputDecorationTheme(
        filled: true,
        fillColor: scheme.surface,
        border: OutlineInputBorder(borderRadius: radius),
        enabledBorder: OutlineInputBorder(
            borderRadius: radius, borderSide: BorderSide(color: scheme.outlineVariant)),
        focusedBorder: OutlineInputBorder(
            borderRadius: radius,
            borderSide: BorderSide(color: scheme.primary, width: 2))),
    snackBarTheme: SnackBarThemeData(
        behavior: SnackBarBehavior.floating,
        shape: RoundedRectangleBorder(borderRadius: radius)),
    dialogTheme: DialogThemeData(
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(20))),
    chipTheme: ChipThemeData(shape: RoundedRectangleBorder(borderRadius: radius)),
    tabBarTheme: TabBarThemeData(
        dividerColor: scheme.outlineVariant,
        labelStyle: const TextStyle(fontWeight: FontWeight.w600)),
  );
}
