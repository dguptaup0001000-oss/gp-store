import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/theme/app_theme.dart';
import '../../../core/theme/customer_surface_theme.dart';
import '../../../core/util/app_haptics.dart';

/// Compact entry point for the ten customer colour presets.
///
/// The selected colour is shown as the tiny dot under the palette icon, so the
/// control is understandable without occupying valuable header space.
class CustomerThemePickerButton extends ConsumerWidget {
  const CustomerThemePickerButton({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final selected = ref.watch(customerSurfaceThemeProvider);

    return Tooltip(
      message: 'Change app colour',
      child: InkResponse(
        radius: 22,
        onTap: () {
          AppHaptics.selection();
          showModalBottomSheet<void>(
            context: context,
            showDragHandle: true,
            useSafeArea: true,
            backgroundColor: Colors.white,
            builder: (_) => const _CustomerThemeSheet(),
          );
        },
        child: SizedBox.square(
          dimension: 34,
          child: Stack(
            alignment: Alignment.center,
            children: [
              const Icon(
                Icons.palette_outlined,
                size: 21,
                color: AppColors.primary,
              ),
              Positioned(
                right: 4,
                bottom: 4,
                child: Container(
                  width: 9,
                  height: 9,
                  decoration: BoxDecoration(
                    color: selected.ground,
                    shape: BoxShape.circle,
                    border: Border.all(color: AppColors.primary, width: 1),
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _CustomerThemeSheet extends ConsumerWidget {
  const _CustomerThemeSheet();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final selected = ref.watch(customerSurfaceThemeProvider);

    return Padding(
      padding: const EdgeInsets.fromLTRB(18, 0, 18, 18),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text(
            'Choose your GP-STORE colour',
            style: TextStyle(
              fontSize: 19,
              fontWeight: FontWeight.w800,
              color: AppColors.textPrimary,
            ),
          ),
          const SizedBox(height: 4),
          const Text(
            'Only the light background changes. GP-STORE green, buttons, prices and product photos stay the same.',
            style: TextStyle(
              fontSize: 12.5,
              height: 1.35,
              color: AppColors.textSecondary,
            ),
          ),
          const SizedBox(height: 16),
          LayoutBuilder(
            builder: (context, constraints) {
              const spacing = 8.0;
              final itemWidth =
                  (constraints.maxWidth - (spacing * 4)) / 5;

              return Wrap(
                spacing: spacing,
                runSpacing: 14,
                children: CustomerSurfaceThemes.all.map((theme) {
                  final active = selected.id == theme.id;
                  return SizedBox(
                    width: itemWidth,
                    child: _ThemeChoice(
                      theme: theme,
                      selected: active,
                      onTap: () {
                        AppHaptics.selection();
                        ref
                            .read(customerSurfaceThemeProvider.notifier)
                            .select(theme);
                      },
                    ),
                  );
                }).toList(growable: false),
              );
            },
          ),
          const SizedBox(height: 18),
          SizedBox(
            width: double.infinity,
            child: FilledButton(
              onPressed: () => Navigator.of(context).pop(),
              child: const Text('Done'),
            ),
          ),
          const SizedBox(height: 2),
          const Center(
            child: Text(
              'Your choice is remembered on this phone.',
              style: TextStyle(
                color: AppColors.textSecondary,
                fontSize: 11.5,
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _ThemeChoice extends StatelessWidget {
  const _ThemeChoice({
    required this.theme,
    required this.selected,
    required this.onTap,
  });

  final CustomerSurfaceTheme theme;
  final bool selected;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return Semantics(
      button: true,
      selected: selected,
      label: '${theme.label} colour theme',
      child: InkWell(
        borderRadius: BorderRadius.circular(14),
        onTap: onTap,
        child: Padding(
          padding: const EdgeInsets.symmetric(vertical: 4),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              AnimatedContainer(
                duration: const Duration(milliseconds: 140),
                width: 44,
                height: 44,
                decoration: BoxDecoration(
                  color: theme.ground,
                  shape: BoxShape.circle,
                  border: Border.all(
                    color: selected
                        ? AppColors.primary
                        : AppColors.divider,
                    width: selected ? 3 : 1,
                  ),
                  boxShadow: selected
                      ? const [
                          BoxShadow(
                            blurRadius: 6,
                            offset: Offset(0, 2),
                            color: Color(0x22075A39),
                          ),
                        ]
                      : null,
                ),
                child: selected
                    ? const Icon(
                        Icons.check_rounded,
                        color: AppColors.primary,
                        size: 23,
                      )
                    : null,
              ),
              const SizedBox(height: 5),
              Text(
                theme.label,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                textAlign: TextAlign.center,
                style: TextStyle(
                  fontSize: 10.5,
                  fontWeight: selected ? FontWeight.w700 : FontWeight.w500,
                  color: AppColors.textPrimary,
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
