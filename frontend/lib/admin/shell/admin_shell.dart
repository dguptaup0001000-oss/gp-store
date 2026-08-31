import 'package:flutter/material.dart';

import '../../core/util/haptic_widgets.dart';
import '../dashboard/admin_dashboard_screen.dart';
import '../design/admin_tokens.dart';
import 'admin_destinations.dart';

/// The admin console's frame.
///
/// TWO LAYOUTS, AND THE PHONE ONE IS THE REAL ONE. This ships as an Android
/// APK that a shopkeeper uses standing behind a counter, so below
/// [AdminBreakpoints.expanded] navigation is a drawer and a chosen screen
/// takes the whole display - the pattern every other Android app uses, and
/// the only one that leaves room for content on a 360px-wide phone. The
/// permanent sidebar is the exception, for a tablet or a desktop window.
///
/// WHY THE TWO LAYOUTS NAVIGATE DIFFERENTLY. On a phone, picking a screen
/// PUSHES it: it arrives with its own app bar and a back button, which is
/// what a thumb expects. On a wide screen, pushing would cover the sidebar
/// you just used, so the screen is swapped into the content pane instead and
/// the sidebar stays put. Same destinations, same widgets, different
/// presentation - no screen knows which layout it is in.
class AdminShell extends StatefulWidget {
  const AdminShell({super.key, this.onSignOut, this.operatorName});

  final VoidCallback? onSignOut;

  /// Shown in the account block. Null renders a generic label rather than an
  /// empty row - a profile can still be loading when the shell first paints.
  final String? operatorName;

  @override
  State<AdminShell> createState() => _AdminShellState();
}

class _AdminShellState extends State<AdminShell> {
  String _selectedId = AdminNav.dashboardId;

  void _select(AdminDestination destination, {required bool wide}) {
    if (wide) {
      setState(() => _selectedId = destination.id);
      return;
    }

    // Phone: the drawer is already closing, and the dashboard is what is
    // behind it, so only a real destination needs a route.
    if (destination.id == AdminNav.dashboardId) {
      setState(() => _selectedId = AdminNav.dashboardId);
      return;
    }
    Navigator.of(context).push(MaterialPageRoute(builder: destination.builder));
  }

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(
      builder: (context, constraints) {
        final wide = AdminBreakpoints.isExpanded(constraints.maxWidth);
        return wide ? _buildWide(context) : _buildCompact(context);
      },
    );
  }

  // ------------------------------------------------------------------
  // Phone and tablet
  // ------------------------------------------------------------------

  Widget _buildCompact(BuildContext context) {
    return Scaffold(
      backgroundColor: AdminColors.background,
      appBar: AppBar(
        backgroundColor: AdminColors.surface,
        surfaceTintColor: AdminColors.surface,
        foregroundColor: AdminColors.textPrimary,
        elevation: 0,
        scrolledUnderElevation: 0.5,
        shape: const Border(
          bottom: BorderSide(color: AdminColors.border),
        ),
        title: const Text('Dashboard', style: AdminText.sectionTitle),
        actions: _headerActions(context),
      ),
      drawer: Drawer(
        backgroundColor: AdminColors.sidebar,
        child: SafeArea(
          child: _AdminNavList(
            selectedId: _selectedId,
            onSelect: (destination) {
              Navigator.of(context).pop();
              _select(destination, wide: false);
            },
          ),
        ),
      ),
      body: const AdminDashboardScreen(),
    );
  }

  // ------------------------------------------------------------------
  // Wide
  // ------------------------------------------------------------------

  Widget _buildWide(BuildContext context) {
    final destination = AdminNav.byId(_selectedId);
    return Scaffold(
      backgroundColor: AdminColors.background,
      body: Column(
        children: [
          _AdminTopBar(
            operatorName: widget.operatorName,
            actions: _headerActions(context),
          ),
          Expanded(
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                SizedBox(
                  width: 256,
                  child: ColoredBox(
                    color: AdminColors.sidebar,
                    child: _AdminNavList(
                      selectedId: _selectedId,
                      onSelect: (d) => _select(d, wide: true),
                      showBrand: false,
                    ),
                  ),
                ),
                Expanded(
                  // Keyed so swapping destinations disposes the previous
                  // screen's state instead of quietly handing it to the
                  // next one, which is how a filter typed on Orders ends up
                  // applied to Products.
                  child: KeyedSubtree(
                    key: ValueKey(destination.id),
                    child: destination.id == AdminNav.dashboardId
                        ? _paneWithTitle(
                            destination.label, const AdminDashboardScreen())
                        : Builder(builder: destination.builder),
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _paneWithTitle(String title, Widget child) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Container(
          padding: const EdgeInsets.symmetric(
            horizontal: AdminSpacing.xl,
            vertical: AdminSpacing.lg,
          ),
          decoration: const BoxDecoration(
            color: AdminColors.surface,
            border: Border(bottom: BorderSide(color: AdminColors.border)),
          ),
          child: Text(title, style: AdminText.sectionTitle),
        ),
        Expanded(child: child),
      ],
    );
  }

  List<Widget> _headerActions(BuildContext context) {
    return [
      if (widget.onSignOut != null)
        IconButton(
          icon: const Icon(Icons.logout_rounded, size: 20),
          tooltip: 'Sign out',
          color: AdminColors.textSecondary,
          onPressed: hapticize(widget.onSignOut!),
        ),
      const SizedBox(width: AdminSpacing.sm),
    ];
  }
}

/// Full-width bar above the sidebar. Only exists on wide layouts - a phone
/// gets the app bar instead, because stacking a brand bar on top of an app
/// bar on a 360px screen spends vertical space the content needs.
class _AdminTopBar extends StatelessWidget {
  const _AdminTopBar({required this.operatorName, required this.actions});

  final String? operatorName;
  final List<Widget> actions;

  @override
  Widget build(BuildContext context) {
    return Container(
      height: 60,
      padding: const EdgeInsets.symmetric(horizontal: AdminSpacing.lg),
      decoration: const BoxDecoration(
        color: AdminColors.surface,
        border: Border(bottom: BorderSide(color: AdminColors.border)),
      ),
      child: Row(
        children: [
          const _AdminBrand(onDarkGround: false),
          const Spacer(),
          Text(
            operatorName?.trim().isNotEmpty == true
                ? operatorName!.trim()
                : 'Store staff',
            style: AdminText.bodyMuted,
          ),
          const SizedBox(width: AdminSpacing.md),
          ...actions,
        ],
      ),
    );
  }
}

class _AdminBrand extends StatelessWidget {
  const _AdminBrand({required this.onDarkGround});

  final bool onDarkGround;

  @override
  Widget build(BuildContext context) {
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Container(
          width: 30,
          height: 30,
          decoration: BoxDecoration(
            color: AdminColors.primary,
            borderRadius: BorderRadius.circular(AdminRadius.md),
          ),
          alignment: Alignment.center,
          child: const Text(
            'GP',
            style: TextStyle(
              color: AdminColors.textOnPrimary,
              fontWeight: FontWeight.w700,
              fontSize: 12,
              letterSpacing: 0.3,
            ),
          ),
        ),
        const SizedBox(width: AdminSpacing.md),
        Text(
          'GP-STORE Admin',
          style: TextStyle(
            fontSize: 15,
            fontWeight: FontWeight.w700,
            color: onDarkGround
                ? AdminColors.sidebarTextActive
                : AdminColors.textPrimary,
          ),
        ),
      ],
    );
  }
}

/// The navigation list itself, shared by the drawer and the permanent rail so
/// the two can never drift out of sync.
class _AdminNavList extends StatelessWidget {
  const _AdminNavList({
    required this.selectedId,
    required this.onSelect,
    this.showBrand = true,
  });

  final String selectedId;
  final ValueChanged<AdminDestination> onSelect;
  final bool showBrand;

  @override
  Widget build(BuildContext context) {
    final children = <Widget>[];
    if (showBrand) {
      children.add(const Padding(
        padding: EdgeInsets.fromLTRB(
          AdminSpacing.lg,
          AdminSpacing.xl,
          AdminSpacing.lg,
          AdminSpacing.lg,
        ),
        child: _AdminBrand(onDarkGround: true),
      ));
    } else {
      children.add(const SizedBox(height: AdminSpacing.lg));
    }

    for (final group in AdminNav.groups) {
      children.add(Padding(
        padding: const EdgeInsets.fromLTRB(
          AdminSpacing.lg,
          AdminSpacing.lg,
          AdminSpacing.lg,
          AdminSpacing.sm,
        ),
        child: Text(group.title.toUpperCase(), style: AdminText.overline),
      ));
      for (final destination in group.destinations) {
        children.add(_AdminNavTile(
          destination: destination,
          selected: destination.id == selectedId,
          onTap: () => onSelect(destination),
        ));
      }
    }
    children.add(const SizedBox(height: AdminSpacing.xl));

    return ListView(padding: EdgeInsets.zero, children: children);
  }
}

class _AdminNavTile extends StatelessWidget {
  const _AdminNavTile({
    required this.destination,
    required this.selected,
    required this.onTap,
  });

  final AdminDestination destination;
  final bool selected;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final color = selected
        ? AdminColors.sidebarTextActive
        : AdminColors.sidebarText;
    return Padding(
      padding: const EdgeInsets.symmetric(
        horizontal: AdminSpacing.sm,
        vertical: 1,
      ),
      child: Material(
        color: selected ? AdminColors.sidebarHover : Colors.transparent,
        borderRadius: AdminRadius.control,
        child: InkWell(
          onTap: hapticize(onTap),
          borderRadius: AdminRadius.control,
          child: Container(
            // 44px is the smallest target that is still comfortable for a
            // thumb; the drawer uses the same tile as the desktop rail, so
            // this height is set by the phone, not by the mouse.
            constraints: const BoxConstraints(minHeight: 44),
            padding: const EdgeInsets.symmetric(
              horizontal: AdminSpacing.md,
              vertical: AdminSpacing.sm,
            ),
            child: Row(
              children: [
                Icon(destination.icon, size: 19, color: color),
                const SizedBox(width: AdminSpacing.md),
                Expanded(
                  child: Text(
                    destination.label,
                    style: TextStyle(
                      fontSize: 14,
                      fontWeight:
                          selected ? FontWeight.w600 : FontWeight.w400,
                      color: color,
                    ),
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                  ),
                ),
                if (selected)
                  Container(
                    width: 3,
                    height: 18,
                    decoration: BoxDecoration(
                      color: AdminColors.primary,
                      borderRadius: BorderRadius.circular(2),
                    ),
                  ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
