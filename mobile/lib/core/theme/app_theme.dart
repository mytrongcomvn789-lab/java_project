import 'package:flutter/material.dart';

/// Design token của SmartDrone (đặc tả giao diện mobile). Mọi màn hình lấy màu ở đây, không tự
/// đặt mã màu riêng, để đổi thương hiệu chỉ sửa một chỗ.
abstract final class AppColors {
  static const primary = Color(0xff087e8b); // teal chủ đạo
  static const dark = Color(0xff073b45); // nền hero và thẻ nổi bật
  static const tealBright = Color(0xff0fb5a6); // teal sáng nhấn
  static const amber = Color(0xffffb703); // drone, cảnh báo, tiến độ
  static const background = Color(0xfff2f7f8);
  static const surface = Colors.white;
  static const text = Color(0xff0b1f2a);
  static const textMuted = Color(0xff51646e);
  static const border = Color(0xffd3e0e3);
  static const danger = Color(0xff8a1f24);
}

/// Cặp màu nền nhạt + chữ đậm của chip trạng thái (tương phản chữ/nền ≥ 4.5:1).
class ChipColors {
  const ChipColors(this.background, this.foreground);
  final Color background, foreground;
}

abstract final class StatusPalette {
  static const pending = ChipColors(Color(0xffffeab8), Color(0xff7a4300));
  static const active = ChipColors(Color(0xffd9f3f0), Color(0xff075b57));
  static const done = ChipColors(Color(0xffd4f1db), Color(0xff14602d));
  static const failed = ChipColors(Color(0xfffde0e1), Color(0xff8a1f24));

  /// Đặc tả không quy định màu cho trạng thái đã đóng (hủy, hoàn xong): dùng xám trung tính.
  static const closed = ChipColors(Color(0xffeceff1), Color(0xff37474f));

  /// Gom 12 trạng thái đơn vào 5 nhóm màu. Nhãn tiếng Việt lấy từ `statusLabels` (order_models).
  static ChipColors of(String status) => switch (status) {
        'PENDING' || 'RETURNING' => pending,
        'APPROVED' ||
        'SCHEDULED' ||
        'PREPARING' ||
        'IN_TRANSIT' ||
        'AWAITING_PICKUP' =>
          active,
        'DELIVERED' => done,
        'REJECTED' || 'FAILED' => failed,
        'CANCELLED' || 'RETURNED' => closed,
        _ => active,
      };
}

/// Bo góc và khoảng cách dùng chung.
abstract final class AppRadii {
  static const card = 20.0; // thẻ 18–26
  static const button = 16.0; // nút 14–16
  static const sheet = 30.0; // góc trên của bottom sheet
  static const pill = 99.0; // chip
}

const gapS = 8.0, gapM = 16.0, gapL = 24.0;
const fontFamily = 'BeVietnamPro';

/// Bóng nhẹ cho thẻ nổi trên nền #F2F7F8.
const cardShadow = [
  BoxShadow(color: Color(0x14073b45), blurRadius: 18, offset: Offset(0, 6)),
];

ThemeData buildAppTheme() {
  final scheme = ColorScheme.fromSeed(
    seedColor: AppColors.primary,
    primary: AppColors.primary,
    surface: AppColors.surface,
    onSurface: AppColors.text,
    onSurfaceVariant: AppColors.textMuted,
    outlineVariant: AppColors.border,
    tertiary: AppColors.amber,
  );
  final button = RoundedRectangleBorder(
      borderRadius: BorderRadius.circular(AppRadii.button));
  final input = OutlineInputBorder(
      borderRadius: BorderRadius.circular(AppRadii.button),
      borderSide: const BorderSide(color: AppColors.border));
  return ThemeData(
    useMaterial3: true,
    fontFamily: fontFamily,
    colorScheme: scheme,
    scaffoldBackgroundColor: AppColors.background,
    appBarTheme: const AppBarTheme(
        backgroundColor: AppColors.surface,
        surfaceTintColor: Colors.transparent,
        scrolledUnderElevation: 0,
        elevation: 0,
        centerTitle: false,
        titleTextStyle: TextStyle(
            fontFamily: fontFamily,
            color: AppColors.text,
            fontSize: 20,
            fontWeight: FontWeight.w700)),
    cardTheme: CardThemeData(
        elevation: 0,
        margin: EdgeInsets.zero,
        color: AppColors.surface,
        surfaceTintColor: Colors.transparent,
        shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(AppRadii.card),
            side: const BorderSide(color: AppColors.border))),
    // Nút chính: cao 54, nền primary, chữ trắng 16/700.
    filledButtonTheme: FilledButtonThemeData(
        style: FilledButton.styleFrom(
            backgroundColor: AppColors.primary,
            foregroundColor: Colors.white,
            minimumSize: const Size.fromHeight(54),
            textStyle: const TextStyle(
                fontFamily: fontFamily, fontSize: 16, fontWeight: FontWeight.w700),
            shape: button)),
    outlinedButtonTheme: OutlinedButtonThemeData(
        style: OutlinedButton.styleFrom(
            minimumSize: const Size.fromHeight(54),
            foregroundColor: AppColors.primary,
            side: const BorderSide(color: AppColors.border),
            textStyle: const TextStyle(
                fontFamily: fontFamily, fontSize: 16, fontWeight: FontWeight.w600),
            shape: button)),
    textButtonTheme: TextButtonThemeData(
        style: TextButton.styleFrom(
            minimumSize: const Size(44, 44),
            textStyle: const TextStyle(
                fontFamily: fontFamily, fontWeight: FontWeight.w600))),
    inputDecorationTheme: InputDecorationTheme(
        filled: true,
        fillColor: AppColors.surface,
        border: input,
        enabledBorder: input,
        focusedBorder: OutlineInputBorder(
            borderRadius: BorderRadius.circular(AppRadii.button),
            borderSide: const BorderSide(color: AppColors.primary, width: 2)),
        errorBorder: OutlineInputBorder(
            borderRadius: BorderRadius.circular(AppRadii.button),
            borderSide: const BorderSide(color: AppColors.danger)),
        focusedErrorBorder: OutlineInputBorder(
            borderRadius: BorderRadius.circular(AppRadii.button),
            borderSide: const BorderSide(color: AppColors.danger, width: 2)),
        contentPadding:
            const EdgeInsets.symmetric(horizontal: 16, vertical: 16)),
    snackBarTheme: SnackBarThemeData(
        behavior: SnackBarBehavior.floating,
        backgroundColor: AppColors.dark,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14))),
    dialogTheme: DialogThemeData(
        backgroundColor: AppColors.surface,
        surfaceTintColor: Colors.transparent,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(24))),
    bottomSheetTheme: const BottomSheetThemeData(
        backgroundColor: AppColors.surface,
        surfaceTintColor: Colors.transparent,
        shape: RoundedRectangleBorder(
            borderRadius:
                BorderRadius.vertical(top: Radius.circular(AppRadii.sheet)))),
    // Màu chữ và nền ghi rõ: nếu để theme tự suy ra, nhãn của chip chưa chọn gần như trắng trên nền trắng.
    chipTheme: ChipThemeData(
        shape: const StadiumBorder(),
        side: const BorderSide(color: AppColors.border),
        backgroundColor: AppColors.surface,
        selectedColor: StatusPalette.active.background,
        checkmarkColor: StatusPalette.active.foreground,
        labelStyle: const TextStyle(
            fontFamily: fontFamily,
            fontWeight: FontWeight.w600,
            fontSize: 13,
            color: AppColors.text),
        secondaryLabelStyle: TextStyle(
            fontFamily: fontFamily,
            fontWeight: FontWeight.w700,
            fontSize: 13,
            color: StatusPalette.active.foreground)),
    tabBarTheme: const TabBarThemeData(
        dividerColor: Colors.transparent,
        labelStyle:
            TextStyle(fontFamily: fontFamily, fontWeight: FontWeight.w700)),
  );
}
