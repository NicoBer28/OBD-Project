import 'package:flutter/material.dart';
import 'package:google_fonts/google_fonts.dart';

/// Single source of truth for the Graphite palette.
/// The short aliases are the light-mode values used by static UI elements;
/// [buildAppTheme] selects the corresponding dark tokens at runtime.
abstract final class AppPalette {
  // Light graphite
  static const canvas = Color(0xFFF6F6F5);
  static const surface = Colors.white;
  static const surface2 = Color(0xFFEFEFEE);
  static const border = Color(0xFFE2E2E0);
  static const text = Color(0xFF131413);
  static const muted = Color(0xFF62645F);
  static const accent = Color(0xFF4B4E56);
  static const accentSubtle = Color(0xFFEBECEF);
  static const accent2 = Color(0xFFA85A3E);
  static const accent2Subtle = Color(0xFFF5E9E4);
  static const accent3 = Color(0xFF8A5570);
  static const success = Color(0xFF1F7A4D);
  static const warning = Color(0xFF8A6200);
  static const danger = Color(0xFFB01121);
  static const member1 = Color(0xFF3E6BB8);
  static const member2 = Color(0xFFC4562E);
  static const member3 = Color(0xFF2F7D5C);
  static const member4 = Color(0xFFA8862A);

  // Dark graphite
  // Dark graphite (Replaced with Modern Zinc/OLED for better contrast)
  static const darkCanvas = Color(0xFF09090B); // True deep background
  static const darkSurface = Color(0xFF18181B); // Elevated card color
  static const darkSurface2 = Color(0xFF27272A); // Dividers
  static const darkBorder = Color(0xFF3F3F46);
  static const darkText = Color(0xFFFAFAFA); // Crisp off-white for readability
  static const darkMuted = Color(0xFFA1A1AA);
  static const darkAccent = Color(0xFF60A5FA);
  static const darkAccentSubtle = Color(0xFF172554);
  static const darkAccent2 = Color(0xFFF472B6); // For the calendar
  static const darkAccent2Subtle = Color(0xFF381029);
  static const darkAccent3 = Color(0xFFC084FC);
  static const darkSuccess = Color(0xFF34D399);
  static const darkWarning = Color(0xFFFBBF24);
  static const darkDanger = Color(0xFFEF4444);
  // Dark Palette Refined Member Colors
  static const darkMember1 = Color(0xFF3B82F6); // 'Vos' / Próximo Turno
  static const darkMember2 = Color(0xFFEC4899); // Soft rose
  static const darkMember3 = Color(0xFF10B981); // Emerald green
  static const darkMember4 = Color(0xFFF59E0B); // Soft amber
}

typedef AppColors = AppPalette;

final themeModeNotifier = ValueNotifier<ThemeMode>(ThemeMode.light);

/// Brightness-aware color tokens.
/// [AppColors] holds the light values only, so widgets that must look right in
/// both modes read these through `context.tokens` instead.
class AppTokens {
  final Color canvas;
  final Color text;
  final Color muted;
  final Color surface;
  final Color surface2;
  final Color border;
  final Color accent;
  final Color accentSubtle;
  final Color accent2;
  final Color accent2Subtle;
  final Color accent3;
  final Color success;
  final Color warning;
  final Color danger;
  final Color member1;
  final Color member2;
  final Color member3;
  final Color member4;

  const AppTokens._({
    required this.canvas,
    required this.text,
    required this.muted,
    required this.surface,
    required this.surface2,
    required this.border,
    required this.accent,
    required this.accentSubtle,
    required this.accent2,
    required this.accent2Subtle,
    required this.accent3,
    required this.success,
    required this.warning,
    required this.danger,
    required this.member1,
    required this.member2,
    required this.member3,
    required this.member4,
  });

  static const light = AppTokens._(
    canvas: AppPalette.canvas,
    text: AppPalette.text,
    muted: AppPalette.muted,
    surface: AppPalette.surface,
    surface2: AppPalette.surface2,
    border: AppPalette.border,
    accent: AppPalette.accent,
    accentSubtle: AppPalette.accentSubtle,
    accent2: AppPalette.accent2,
    accent2Subtle: AppPalette.accent2Subtle,
    accent3: AppPalette.accent3,
    success: AppPalette.success,
    warning: AppPalette.warning,
    danger: AppPalette.danger,
    member1: AppPalette.member1,
    member2: AppPalette.member2,
    member3: AppPalette.member3,
    member4: AppPalette.member4,
  );

  static const dark = AppTokens._(
    canvas: AppPalette.darkCanvas,
    text: AppPalette.darkText,
    muted: AppPalette.darkMuted,
    surface: AppPalette.darkSurface,
    surface2: AppPalette.darkSurface2,
    border: AppPalette.darkBorder,
    accent: AppPalette.darkAccent,
    accentSubtle: AppPalette.darkAccentSubtle,
    accent2: AppPalette.darkAccent2,
    accent2Subtle: AppPalette.darkAccent2Subtle,
    accent3: AppPalette.darkAccent3,
    success: AppPalette.darkSuccess,
    warning: AppPalette.darkWarning,
    danger: AppPalette.darkDanger,
    member1: AppPalette.darkMember1,
    member2: AppPalette.darkMember2,
    member3: AppPalette.darkMember3,
    member4: AppPalette.darkMember4,
  );
}

extension AppTokensContext on BuildContext {
  AppTokens get tokens => Theme.of(this).brightness == Brightness.dark
      ? AppTokens.dark
      : AppTokens.light;
}

ThemeData buildAppTheme([Brightness brightness = Brightness.light]) {
  final isDark = brightness == Brightness.dark;
  final canvas = isDark ? AppPalette.darkCanvas : AppPalette.canvas;
  final surface = isDark ? AppPalette.darkSurface : AppPalette.surface;
  final border = isDark ? AppPalette.darkBorder : AppPalette.border;
  final text = isDark ? AppPalette.darkText : AppPalette.text;
  final muted = isDark ? AppPalette.darkMuted : AppPalette.muted;
  final accent = isDark ? AppPalette.darkAccent : AppPalette.accent;
  final accentSubtle = isDark
      ? AppPalette.darkAccentSubtle
      : AppPalette.accentSubtle;
  final scheme = ColorScheme.fromSeed(
    seedColor: accent,
    brightness: brightness,
    surface: surface,
  );
  final outline = OutlineInputBorder(
    borderRadius: BorderRadius.circular(14),
    borderSide: BorderSide(color: border),
  );
  return ThemeData(
    useMaterial3: true,
    // ADD THIS TEXT THEME BLOCK:
    // Inside buildAppTheme()
    textTheme: GoogleFonts.plusJakartaSansTextTheme(
      ThemeData(brightness: brightness).textTheme,
    ).apply(bodyColor: text, displayColor: text),
    colorScheme: scheme.copyWith(
      primary: accent,
      onPrimary: isDark ? const Color(0xFF14151A) : Colors.white,
      surface: surface,
      onSurface: text,
      outline: border,
    ),
    scaffoldBackgroundColor: canvas,
    dividerColor: border,
    // ... leave the rest of your theme (appBarTheme, cardTheme, etc.) as is
    appBarTheme: AppBarTheme(
      backgroundColor: canvas,
      foregroundColor: text,
      elevation: 0,
      surfaceTintColor: Colors.transparent,
      titleTextStyle: TextStyle(fontSize: 19, fontWeight: FontWeight.w700),
    ),
    cardTheme: CardThemeData(
      color: surface,
      elevation: 0,
      margin: EdgeInsets.zero,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(17),
        side: BorderSide(color: border),
      ),
    ),
    inputDecorationTheme: InputDecorationTheme(
      filled: true,
      fillColor: surface,
      labelStyle: TextStyle(color: muted),
      prefixIconColor: muted,
      contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 17),
      border: outline,
      enabledBorder: outline,
      focusedBorder: outline.copyWith(
        borderSide: BorderSide(color: accent, width: 1.5),
      ),
      errorBorder: outline.copyWith(
        borderSide: const BorderSide(color: AppColors.danger),
      ),
    ),
    elevatedButtonTheme: ElevatedButtonThemeData(
      style: ElevatedButton.styleFrom(
        backgroundColor: accent,
        foregroundColor: isDark ? const Color(0xFF14151A) : Colors.white,
        elevation: 0,
        minimumSize: const Size.fromHeight(52),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(15)),
        textStyle: const TextStyle(fontSize: 15, fontWeight: FontWeight.w700),
      ),
    ),
    navigationBarTheme: NavigationBarThemeData(
      backgroundColor: surface,
      indicatorColor: accentSubtle,
      height: 72,
      labelTextStyle: WidgetStatePropertyAll(
        TextStyle(fontSize: 11, fontWeight: FontWeight.w600),
      ),
    ),
  );
}
