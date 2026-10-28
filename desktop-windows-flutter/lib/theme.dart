// SPDX-License-Identifier: GPL-3.0-only
import 'package:flutter/material.dart';

/// Visual language shared with the other desktop/TV clients: warm paper-like light theme,
/// near-black dark theme, coral primary and no Material 3 surface tinting.
abstract final class AppTheme {
  static const seed = Color(0xFFFF664F);
  static const lightBackground = Color(0xFFFAF8F6);
  static const darkBackground = Color(0xFF101114);

  static const _lightPrimary = Color(0xFFAD3826);
  static const _lightOnPrimary = Colors.white;
  static const _lightPrimaryContainer = Color(0xFFFFE0D8);
  static const _lightOnPrimaryContainer = Color(0xFF4B160D);
  static const _lightSurface = Colors.white;
  static const _lightOnSurface = Color(0xFF252328);
  static const _lightOnSurfaceVariant = Color(0xFF68636C);
  static const _lightOutline = Color(0xFF7C757D);
  static const _lightOutlineVariant = Color(0xFFE3DFDC);
  static const _lightContainerLow = Color(0xFFF7F3F0);
  static const _lightContainer = Color(0xFFF2EEEB);
  static const _lightContainerHigh = Color(0xFFEDE8E5);
  static const _lightContainerHighest = Color(0xFFE6E0DC);

  static const _darkPrimary = Color(0xFFFF765F);
  static const _darkOnPrimary = Color(0xFF3B0E07);
  static const _darkPrimaryContainer = Color(0xFF5C3027);
  static const _darkOnPrimaryContainer = Color(0xFFFFE0D8);
  static const _darkSurface = Color(0xFF16171B);
  static const _darkOnSurface = Color(0xFFF2F0F4);
  static const _darkOnSurfaceVariant = Color(0xFFB9B6C2);
  static const _darkOutline = Color(0xFF85818B);
  static const _darkOutlineVariant = Color(0xFF34343D);
  static const _darkContainerLowest = Color(0xFF0D0E11);
  static const _darkContainerLow = Color(0xFF191A20);
  static const _darkContainer = Color(0xFF1E2026);
  static const _darkContainerHigh = Color(0xFF25262D);
  static const _darkContainerHighest = Color(0xFF30313A);

  static const accentGold = Color(0xFFF6C86B);
  static const onAccentGold = Color(0xFF40300D);

  static ThemeData get light => _build(Brightness.light);
  static ThemeData get dark => _build(Brightness.dark);

  static ThemeData _build(Brightness brightness) {
    final isDark = brightness == Brightness.dark;
    final scheme = ColorScheme(
      brightness: brightness,
      primary: isDark ? _darkPrimary : _lightPrimary,
      onPrimary: isDark ? _darkOnPrimary : _lightOnPrimary,
      primaryContainer: isDark ? _darkPrimaryContainer : _lightPrimaryContainer,
      onPrimaryContainer: isDark ? _darkOnPrimaryContainer : _lightOnPrimaryContainer,
      secondary: isDark ? _darkPrimary : _lightPrimary,
      onSecondary: isDark ? _darkOnPrimary : _lightOnPrimary,
      secondaryContainer: isDark ? _darkPrimaryContainer : _lightPrimaryContainer,
      onSecondaryContainer: isDark ? _darkOnPrimaryContainer : _lightOnPrimaryContainer,
      tertiary: isDark ? accentGold : const Color(0xFF805500),
      onTertiary: isDark ? onAccentGold : Colors.white,
      error: isDark ? const Color(0xFFFFB4AB) : const Color(0xFFBA1A1A),
      onError: isDark ? const Color(0xFF690005) : Colors.white,
      surface: isDark ? _darkSurface : _lightSurface,
      onSurface: isDark ? _darkOnSurface : _lightOnSurface,
      onSurfaceVariant: isDark ? _darkOnSurfaceVariant : _lightOnSurfaceVariant,
      outline: isDark ? _darkOutline : _lightOutline,
      outlineVariant: isDark ? _darkOutlineVariant : _lightOutlineVariant,
      surfaceContainerLowest: isDark ? _darkContainerLowest : Colors.white,
      surfaceContainerLow: isDark ? _darkContainerLow : _lightContainerLow,
      surfaceContainer: isDark ? _darkContainer : _lightContainer,
      surfaceContainerHigh: isDark ? _darkContainerHigh : _lightContainerHigh,
      surfaceContainerHighest: isDark ? _darkContainerHighest : _lightContainerHighest,
      surfaceTint: Colors.transparent,
    );

    return ThemeData(
      useMaterial3: true,
      brightness: brightness,
      colorScheme: scheme,
      scaffoldBackgroundColor: isDark ? darkBackground : lightBackground,
      canvasColor: isDark ? darkBackground : lightBackground,
      dividerTheme: DividerThemeData(color: scheme.outlineVariant, thickness: 1, space: 1),
      appBarTheme: AppBarTheme(
        elevation: 0,
        scrolledUnderElevation: 0,
        centerTitle: false,
        backgroundColor: isDark ? darkBackground : lightBackground,
        surfaceTintColor: Colors.transparent,
        foregroundColor: scheme.onSurface,
      ),
      navigationRailTheme: NavigationRailThemeData(
        useIndicator: false,
        backgroundColor: isDark ? darkBackground : lightBackground,
        selectedIconTheme: IconThemeData(color: scheme.primary),
        unselectedIconTheme: IconThemeData(color: scheme.onSurfaceVariant),
        selectedLabelTextStyle: TextStyle(color: scheme.primary, fontWeight: FontWeight.w700, fontSize: 12),
        unselectedLabelTextStyle: TextStyle(color: scheme.onSurfaceVariant, fontSize: 12),
      ),
      inputDecorationTheme: InputDecorationTheme(
        filled: true,
        fillColor: scheme.surfaceContainer,
        isDense: true,
        contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
        border: OutlineInputBorder(
          borderRadius: BorderRadius.circular(16),
          borderSide: BorderSide.none,
        ),
        enabledBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(16),
          borderSide: BorderSide.none,
        ),
        focusedBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(16),
          borderSide: BorderSide(color: scheme.primary, width: 1.5),
        ),
      ),
      chipTheme: ChipThemeData(
        backgroundColor: scheme.surfaceContainer,
        selectedColor: scheme.primaryContainer,
        side: BorderSide.none,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
        labelStyle: TextStyle(color: scheme.onSurface, fontSize: 13),
        showCheckmark: false,
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
      ),
      filledButtonTheme: FilledButtonThemeData(
        style: FilledButton.styleFrom(
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
          padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 14),
        ),
      ),
      outlinedButtonTheme: OutlinedButtonThemeData(
        style: OutlinedButton.styleFrom(
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
        ),
      ),
      snackBarTheme: SnackBarThemeData(
        behavior: SnackBarBehavior.floating,
        backgroundColor: isDark ? _darkContainerHigh : const Color(0xFF3A3439),
        contentTextStyle: const TextStyle(color: Colors.white),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
      ),
      progressIndicatorTheme: ProgressIndicatorThemeData(color: scheme.primary),
      listTileTheme: ListTileThemeData(
        iconColor: scheme.onSurfaceVariant,
        textColor: scheme.onSurface,
      ),
    );
  }

  static ThemeMode mode(String value) => switch (value) {
    'light' => ThemeMode.light,
    'dark' => ThemeMode.dark,
    _ => ThemeMode.system,
  };

  static String modeLabel(String value) => switch (value) {
    'light' => '浅色',
    'dark' => '深色',
    _ => '跟随系统',
  };

  /// Always-dark theme used by the player and full-screen dialogs.
  static ThemeData get player => dark;
}
