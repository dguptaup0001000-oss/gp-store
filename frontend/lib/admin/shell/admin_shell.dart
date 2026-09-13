import 'package:flutter/material.dart';
import 'shop_switcher_bar.dart';

import '../../core/util/haptic_widgets.dart';
import '../dashboard/admin_dashboard_screen.dart';
import '../auth/admin_permissions.dart';
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
  const AdminShell({
    super.key,
    this.onSignOut,
    this.operatorName,
    this.role,
    this.home,
  });

  /// The destination this shell opens on, and returns to.
  ///
  /// Null means [AdminNav.dashboard] - one shop's working day, which is what
  /// the admin APK wants. The super admin APK passes the platform console
  /// instead: the platform owner's first question is never "how did this shop
  /// trade today", it is "which merchants and shops exist". Landing them on a
  /// shop dashboard and asking them to find Marketplace in a sidebar is what
  /// the separate APK exists to stop.
  final AdminDestination? home;

  final VoidCallback? onSignOut;

  /// The signed-in account's backend role. Decides which destinations are
  /// worth showing - the server decides what they can actually do.
  final String? role;

  /// Shown in the account block. Null renders a generic label rather than an
  /// empty row - a profile can still be loading when the shell first paints.
  final String? operatorName;

  @override
  State<AdminShell> createState() => _AdminShellState();
}

class _AdminShellState extends State<AdminShell> {
  late String _selectedId = _home.id;

  AdminDestination get _home => widget.home ?? AdminNav.dashboard;

  Set<AdminPermission> get _permissions =>
      AdminRoles.permissionsFor(widget.role);

  List<AdminNavGroup> get _groups => AdminNav.groupsFor(_permissions);

  void _select(AdminDestination destination, {required bool wide}) {
    if (wide) {
      setState(() => _selectedId = destination.id);
      return;
    }

    // Phone: the drawer is already closing, and the home destination is what
    // is behind it, so only a different destination needs a route.
    if (destination.id == _home.id) {
      setState(() => _selectedId = _home.id);
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
        title: Text(_home.label, style: AdminText.sectionTitle),
        actions: _headerActions(context),
        // WHICH SHOP AM I IN. Renders nothing at all for a merchant with one
        // shop, so the single-shop app is untouched (§59); appears the moment
        // there are two, because from then on a merchant who thinks they are
        // in GP Store and is actually in Deepak Hardware will change the wrong
        // prices and not find out until a customer complains (§64).
        bottom: const PreferredSize(
          preferredSize: Size.fromHeight(0),
          child: ShopSwitcherBar(),
        ),
      ),
      drawer: Drawer(
        backgroundColor: AdminColors.sidebar,
        child: SafeArea(
          child: _AdminNavList(
            groups: _groups,
            selectedId: _selectedId,
            // THE ONLY PLACE A PHONE CAN SAY WHO YOU ARE. The wide layout has
            // _AdminTopBar for this; the compact one is a bare AppBar reading
            // "Dashboard", so until now the role appeared nowhere on a phone
            // at all - and the role is what decides which of these groups
            // exist. Somebody looking for a destination they cannot see had
            // no way to find out why.
            operatorName: widget.operatorName,
            role: widget.role,
            onSelect: (destination) {
              Navigator.of(context).pop();
              _select(destination, wide: false);
            },
          ),
        ),
      ),
      body: Builder(builder: _home.builder),
    );
  }

  // ------------------------------------------------------------------
  // Wide
  // ------------------------------------------------------------------

  Widget _buildWide(BuildContext context) {
    var destination = AdminNav.byId(_selectedId);
    // A selection can outlive the permission that allowed it - a role change
    // takes effect on the next request, and the server would refuse the
    // screen anyway. Fall back rather than render a pane that only 403s.
    if (!AdminNav.isVisible(destination, _permissions)) {
      destination = _home;
    }
    return Scaffold(
      backgroundColor: AdminColors.background,
      body: Column(
        children: [
          _AdminTopBar(
            operatorName: widget.operatorName,
            role: widget.role,
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
                      groups: _groups,
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
  const _AdminTopBar({
    required this.operatorName,
    required this.role,
    required this.actions,
  });

  final String? operatorName;
  final String? role;
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
          Column(
            crossAxisAlignment: CrossAxisAlignment.end,
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(
                operatorName?.trim().isNotEmpty == true
                    ? operatorName!.trim()
                    : 'Store staff',
                style: AdminText.bodyMuted,
              ),
              // Which hat they are wearing. Worth stating: two roles see
              // different menus, and "why can I not see Inventory" is
              // answered by this line.
              Text(AdminRoles.humanize(role), style: AdminText.caption),
            ],
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
    required this.groups,
    required this.selectedId,
    required this.onSelect,
    this.showBrand = true,
    this.operatorName,
    this.role,
  });

  final List<AdminNavGroup> groups;
  final String selectedId;
  final ValueChanged<AdminDestination> onSelect;
  final bool showBrand;

  /// Shown under the brand, and ONLY when the brand is (i.e. in the phone
  /// drawer). The wide layout already names both in its top bar, and saying
  /// it twice on one screen is clutter rather than clarity.
  final String? operatorName;
  final String? role;

  @override
  Widget build(BuildContext context) {
    final children = <Widget>[];
    if (showBrand) {
      children.add(Padding(
        padding: const EdgeInsets.fromLTRB(
          AdminSpacing.lg,
          AdminSpacing.xl,
          AdminSpacing.lg,
          AdminSpacing.lg,
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const _AdminBrand(onDarkGround: true),
            // WHICH HAT YOU ARE WEARING, beside the menu it decides the
            // shape of. "Why can I not see Merchants & Shops" is answered by
            // this line and by nothing else on a phone: the destinations are
            // filtered by the role's permissions, so a group that is missing
            // is missing BECAUSE of what this says.
            //
            // The role is rendered even when the name is absent - the name is
            // the decoration here and the role is the information.
            if (role != null || operatorName != null)
              Padding(
                padding: const EdgeInsets.only(top: AdminSpacing.md),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    if (operatorName?.trim().isNotEmpty == true)
                      Text(
                        operatorName!.trim(),
                        style: AdminText.caption.copyWith(
                            color: AdminColors.sidebarTextActive),
                      ),
                    Text(
                      AdminRoles.humanize(role),
                      style: AdminText.caption
                          .copyWith(color: AdminColors.sidebarText),
                    ),
                  ],
                ),
              ),
          ],
        ),
      ));
    } else {
      children.add(const SizedBox(height: AdminSpacing.lg));
    }

    for (final group in groups) {
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
