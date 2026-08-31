import 'package:flutter/material.dart';

import 'admin_tokens.dart';

/// Shared admin building blocks. Every admin screen composes these instead of
/// styling itself, so spacing, radius, and status colour stay identical
/// across 27 screens without anyone having to remember the numbers.

/// Semantic meaning of a badge. Screens pass intent, not colour, so the
/// palette can change in one place and no screen hardcodes a hex.
enum AdminStatusTone { success, warning, danger, info, neutral }

class AdminStatusBadge extends StatelessWidget {
  const AdminStatusBadge({
    super.key,
    required this.label,
    required this.tone,
    this.dense = false,
  });

  final String label;
  final AdminStatusTone tone;
  final bool dense;

  /// Maps GP-STORE's real OrderStatus values onto a tone.
  ///
  /// Unknown values fall back to neutral rather than throwing: the backend
  /// order state machine can gain a status before this app is rebuilt, and a
  /// crashed order list is far worse than a grey badge.
  static AdminStatusTone toneForOrderStatus(String? status) {
    switch (status?.toUpperCase()) {
      case 'DELIVERED':
        return AdminStatusTone.success;
      case 'OUT_FOR_DELIVERY':
      case 'CONFIRMED':
        return AdminStatusTone.info;
      case 'PROCESSING':
      case 'PACKED':
        return AdminStatusTone.warning;
      case 'PENDING_CONFIRMATION':
      case 'PENDING':
        return AdminStatusTone.warning;
      case 'CANCELLED':
      case 'RETURNED':
        return AdminStatusTone.danger;
      default:
        return AdminStatusTone.neutral;
    }
  }

  /// PENDING_CONFIRMATION -> "Pending Confirmation". Backend enum names must
  /// never reach an operator's screen verbatim.
  static String humanizeStatus(String? status) {
    if (status == null || status.isEmpty) return 'Unknown';
    return status
        .split('_')
        .where((part) => part.isNotEmpty)
        .map((part) => part[0].toUpperCase() + part.substring(1).toLowerCase())
        .join(' ');
  }

  Color get _fg {
    switch (tone) {
      case AdminStatusTone.success:
        return AdminColors.success;
      case AdminStatusTone.warning:
        return const Color(0xFFB45309);
      case AdminStatusTone.danger:
        return AdminColors.danger;
      case AdminStatusTone.info:
        return AdminColors.info;
      case AdminStatusTone.neutral:
        return AdminColors.textSecondary;
    }
  }

  Color get _bg {
    switch (tone) {
      case AdminStatusTone.success:
        return AdminColors.successBg;
      case AdminStatusTone.warning:
        return AdminColors.warningBg;
      case AdminStatusTone.danger:
        return AdminColors.dangerBg;
      case AdminStatusTone.info:
        return AdminColors.infoBg;
      case AdminStatusTone.neutral:
        return AdminColors.neutralBg;
    }
  }

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: EdgeInsets.symmetric(
        horizontal: dense ? AdminSpacing.sm : AdminSpacing.md,
        vertical: dense ? 2 : AdminSpacing.xs,
      ),
      decoration: BoxDecoration(color: _bg, borderRadius: AdminRadius.badge),
      child: Text(
        label,
        style: TextStyle(
          fontSize: dense ? 11 : 12,
          fontWeight: FontWeight.w600,
          color: _fg,
          height: 1.2,
        ),
        maxLines: 1,
        overflow: TextOverflow.ellipsis,
      ),
    );
  }
}

/// A white panel with a hairline border. The single container primitive for
/// the admin app - nothing else should be drawing its own card decoration.
class AdminSectionCard extends StatelessWidget {
  const AdminSectionCard({
    super.key,
    required this.child,
    this.title,
    this.subtitle,
    this.trailing,
    this.padding = const EdgeInsets.all(AdminSpacing.lg),
  });

  final Widget child;
  final String? title;
  final String? subtitle;
  final Widget? trailing;
  final EdgeInsetsGeometry padding;

  @override
  Widget build(BuildContext context) {
    final hasHeader = title != null || trailing != null;
    return Container(
      decoration: BoxDecoration(
        color: AdminColors.surface,
        borderRadius: AdminRadius.card,
        border: Border.all(color: AdminColors.border),
        boxShadow: AdminShadows.card,
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        mainAxisSize: MainAxisSize.min,
        children: [
          if (hasHeader)
            Padding(
              padding: const EdgeInsets.fromLTRB(
                AdminSpacing.lg,
                AdminSpacing.lg,
                AdminSpacing.lg,
                0,
              ),
              child: Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        if (title != null)
                          Text(title!, style: AdminText.sectionTitle),
                        if (subtitle != null) ...[
                          const SizedBox(height: 2),
                          Text(subtitle!, style: AdminText.caption),
                        ],
                      ],
                    ),
                  ),
                  if (trailing != null) trailing!,
                ],
              ),
            ),
          Padding(padding: padding, child: child),
        ],
      ),
    );
  }
}

/// KPI tile: icon, label, big number, and a delta against a comparison
/// period. [deltaPercent] null means "no comparison available" and renders
/// nothing rather than a misleading 0%.
class AdminKpiCard extends StatelessWidget {
  const AdminKpiCard({
    super.key,
    required this.icon,
    required this.label,
    required this.value,
    this.deltaPercent,
    this.comparisonLabel,
    this.higherIsBetter = true,
    this.onTap,
  });

  final IconData icon;
  final String label;
  final String value;
  final double? deltaPercent;
  final String? comparisonLabel;

  /// Pending orders going UP is bad news; sales going up is good. Without
  /// this the same red/green would mean opposite things card to card.
  final bool higherIsBetter;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final delta = deltaPercent;
    final bool? positive = delta == null
        ? null
        : (delta == 0 ? null : (delta > 0) == higherIsBetter);
    final Color deltaColor = positive == null
        ? AdminColors.textSecondary
        : (positive ? AdminColors.success : AdminColors.danger);

    return Material(
      color: AdminColors.surface,
      borderRadius: AdminRadius.card,
      child: InkWell(
        onTap: onTap,
        borderRadius: AdminRadius.card,
        child: Container(
          padding: const EdgeInsets.all(AdminSpacing.lg),
          decoration: BoxDecoration(
            borderRadius: AdminRadius.card,
            border: Border.all(color: AdminColors.border),
            boxShadow: AdminShadows.card,
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            mainAxisSize: MainAxisSize.min,
            children: [
              Row(
                children: [
                  Container(
                    padding: const EdgeInsets.all(AdminSpacing.sm),
                    decoration: const BoxDecoration(
                      color: AdminColors.primaryFaint,
                      borderRadius: AdminRadius.control,
                    ),
                    child: Icon(icon, size: 18, color: AdminColors.primaryDark),
                  ),
                  const SizedBox(width: AdminSpacing.md),
                  Expanded(
                    child: Text(
                      label,
                      style: AdminText.caption,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                    ),
                  ),
                ],
              ),
              const SizedBox(height: AdminSpacing.md),
              FittedBox(
                fit: BoxFit.scaleDown,
                alignment: Alignment.centerLeft,
                child: Text(value, style: AdminText.metric),
              ),
              if (delta != null) ...[
                const SizedBox(height: AdminSpacing.xs),
                Row(
                  children: [
                    Icon(
                      delta > 0
                          ? Icons.arrow_upward_rounded
                          : delta < 0
                              ? Icons.arrow_downward_rounded
                              : Icons.remove_rounded,
                      size: 14,
                      color: deltaColor,
                    ),
                    const SizedBox(width: 2),
                    Text(
                      '${delta.abs().toStringAsFixed(1)}%',
                      style: TextStyle(
                        fontSize: 12,
                        fontWeight: FontWeight.w600,
                        color: deltaColor,
                      ),
                    ),
                    if (comparisonLabel != null) ...[
                      const SizedBox(width: AdminSpacing.xs),
                      Expanded(
                        child: Text(
                          comparisonLabel!,
                          style: AdminText.caption,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                        ),
                      ),
                    ],
                  ],
                ),
              ],
            ],
          ),
        ),
      ),
    );
  }
}

/// Shimmer-free skeleton. A plain animated opacity block costs far less than
/// a gradient sweep on a mid-range Android phone, and reads the same.
class AdminSkeleton extends StatefulWidget {
  const AdminSkeleton({
    super.key,
    this.height = 16,
    this.width,
    this.radius = AdminRadius.sm,
  });

  final double height;
  final double? width;
  final double radius;

  @override
  State<AdminSkeleton> createState() => _AdminSkeletonState();
}

class _AdminSkeletonState extends State<AdminSkeleton>
    with SingleTickerProviderStateMixin {
  late final AnimationController _controller = AnimationController(
    vsync: this,
    duration: const Duration(milliseconds: 900),
  )..repeat(reverse: true);

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return FadeTransition(
      opacity: Tween<double>(begin: 0.45, end: 0.9).animate(_controller),
      child: Container(
        height: widget.height,
        width: widget.width,
        decoration: BoxDecoration(
          color: AdminColors.neutralBg,
          borderRadius: BorderRadius.circular(widget.radius),
        ),
      ),
    );
  }
}

class AdminEmptyState extends StatelessWidget {
  const AdminEmptyState({
    super.key,
    required this.icon,
    required this.title,
    this.message,
    this.action,
  });

  final IconData icon;
  final String title;
  final String? message;
  final Widget? action;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(
        vertical: AdminSpacing.xxl,
        horizontal: AdminSpacing.lg,
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Container(
            padding: const EdgeInsets.all(AdminSpacing.lg),
            decoration: const BoxDecoration(
              color: AdminColors.neutralBg,
              shape: BoxShape.circle,
            ),
            child: Icon(icon, size: 28, color: AdminColors.textMuted),
          ),
          const SizedBox(height: AdminSpacing.lg),
          Text(title, style: AdminText.sectionTitle, textAlign: TextAlign.center),
          if (message != null) ...[
            const SizedBox(height: AdminSpacing.sm),
            Text(message!, style: AdminText.bodyMuted, textAlign: TextAlign.center),
          ],
          if (action != null) ...[
            const SizedBox(height: AdminSpacing.lg),
            action!,
          ],
        ],
      ),
    );
  }
}

/// Error state with a retry. Always offers the retry: an admin staring at a
/// failed panel mid-shift needs a way forward that is not "restart the app".
class AdminErrorState extends StatelessWidget {
  const AdminErrorState({
    super.key,
    required this.message,
    this.onRetry,
    this.compact = false,
  });

  final String message;
  final VoidCallback? onRetry;
  final bool compact;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: EdgeInsets.symmetric(
        vertical: compact ? AdminSpacing.lg : AdminSpacing.xxl,
        horizontal: AdminSpacing.lg,
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          const Icon(Icons.error_outline_rounded,
              size: 28, color: AdminColors.danger),
          const SizedBox(height: AdminSpacing.md),
          Text(message, style: AdminText.bodyMuted, textAlign: TextAlign.center),
          if (onRetry != null) ...[
            const SizedBox(height: AdminSpacing.md),
            TextButton.icon(
              onPressed: onRetry,
              icon: const Icon(Icons.refresh_rounded, size: 18),
              label: const Text('Try again'),
              style: TextButton.styleFrom(
                foregroundColor: AdminColors.primaryDark,
              ),
            ),
          ],
        ],
      ),
    );
  }
}

/// Screen title block. Keeps every page's heading identical instead of each
/// screen inventing its own AppBar treatment.
class AdminPageHeader extends StatelessWidget {
  const AdminPageHeader({
    super.key,
    required this.title,
    this.subtitle,
    this.actions = const [],
  });

  final String title;
  final String? subtitle;
  final List<Widget> actions;

  @override
  Widget build(BuildContext context) {
    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(title, style: AdminText.pageTitle),
              if (subtitle != null) ...[
                const SizedBox(height: 2),
                Text(subtitle!, style: AdminText.bodyMuted),
              ],
            ],
          ),
        ),
        ...actions,
      ],
    );
  }
}
