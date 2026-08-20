import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../core/theme/app_theme.dart';
import '../../features/products/domain/brand_models.dart';

/// A filter control as a single tappable pill.
///
/// Active state is carried by a green fill and weight rather than by a
/// separate checkbox or switch, so a row of these reads as one language: a
/// pill that is green is a filter that is on.
///
/// Shared by brand and category browsing so the two cannot drift into
/// different filter UIs - the same reason those screens share a product card.
class FilterPill extends StatelessWidget {
  const FilterPill({
    super.key,
    required this.icon,
    required this.label,
    required this.isActive,
    required this.onTap,
    this.prefix,
  });

  final IconData icon;
  final String label;

  /// Small leading word ("Sort by") shown above the value, for the pill whose
  /// label is a chosen value rather than a fixed name.
  final String? prefix;
  final bool isActive;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final foreground = isActive ? AppColors.primary : AppColors.textSecondary;

    return Material(
      color: isActive ? AppColors.tint(AppColors.primary) : AppColors.cardBackground,
      borderRadius: BorderRadius.circular(AppRadius.md),
      child: InkWell(
        borderRadius: BorderRadius.circular(AppRadius.md),
        onTap: () {
          HapticFeedback.selectionClick();
          onTap();
        },
        child: Container(
          height: 46,
          padding: const EdgeInsets.symmetric(horizontal: 12),
          decoration: BoxDecoration(
            borderRadius: BorderRadius.circular(AppRadius.md),
            border: Border.all(
              color: isActive ? AppColors.primary : AppColors.divider,
              width: isActive ? 1.4 : 1,
            ),
          ),
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(icon, size: 18, color: foreground),
              const SizedBox(width: 8),
              // Flexible, not Expanded: the "In stock" pill sizes to its own
              // content, and Expanded inside a min-width Row would force it to
              // claim the rest of the line.
              Flexible(
                child: Column(
                  mainAxisAlignment: MainAxisAlignment.center,
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    if (prefix != null)
                      Text(
                        prefix!,
                        style: const TextStyle(fontSize: 10, color: AppColors.textSecondary, height: 1.1),
                      ),
                    Text(
                      label,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(
                        fontSize: 13,
                        height: 1.2,
                        fontWeight: isActive ? FontWeight.w700 : FontWeight.w600,
                        color: isActive ? AppColors.primary : AppColors.textPrimary,
                      ),
                    ),
                  ],
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

/// Opens the sort sheet and returns the customer's choice.
///
/// Returns null when the sheet was dismissed without choosing, which is
/// deliberately different from choosing "Default" (also null as a sort). The
/// two are distinguished by wrapping the choice in a one-element list, so a
/// dismissal can never be mistaken for a reset to Default.
///
/// A sheet rather than a dropdown menu: eight options in a Material dropdown
/// open as a cramped overlay pinned to the field, whereas a sheet gives each
/// row a real tap target and shows which one is currently active - which is
/// what a shopper is actually checking when they open it.
class SortSheet {
  SortSheet._();

  static Future<List<BrandSortOption?>?> show(
    BuildContext context, {
    required BrandSortOption? current,
  }) {
    return showModalBottomSheet<List<BrandSortOption?>>(
      context: context,
      backgroundColor: AppColors.cardBackground,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(AppRadius.lg)),
      ),
      builder: (sheetContext) {
        Widget row(String label, BrandSortOption? value) {
          final isSelected = current == value;
          return ListTile(
            title: Text(
              label,
              style: TextStyle(
                fontWeight: isSelected ? FontWeight.w700 : FontWeight.w500,
                color: isSelected ? AppColors.primary : AppColors.textPrimary,
              ),
            ),
            trailing: isSelected ? const Icon(Icons.check, color: AppColors.primary, size: 20) : null,
            onTap: () => Navigator.of(sheetContext).pop(<BrandSortOption?>[value]),
          );
        }

        return SafeArea(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              const Padding(
                padding: EdgeInsets.fromLTRB(16, 16, 16, 4),
                child: Text('Sort by', style: TextStyle(fontWeight: FontWeight.w700, fontSize: 16)),
              ),
              row('Default', null),
              ...BrandSortOption.values.map((option) => row(option.label, option)),
              const SizedBox(height: 8),
            ],
          ),
        );
      },
    );
  }
}
