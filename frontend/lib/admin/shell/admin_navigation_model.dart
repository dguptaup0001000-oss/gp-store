import 'package:flutter/material.dart';

import '../auth/admin_permissions.dart';

@immutable
class AdminDestination {
  const AdminDestination({
    required this.id,
    required this.label,
    required this.icon,
    required this.builder,
    this.description,
    this.requires,
  });

  final AdminPermission? requires;
  final String id;
  final String label;
  final IconData icon;
  final String? description;
  final WidgetBuilder builder;
}

@immutable
class AdminNavGroup {
  const AdminNavGroup({required this.title, required this.destinations});

  final String title;
  final List<AdminDestination> destinations;
}

/// Small navigation contract used by the common shell. Each APK supplies its
/// own catalog, so a platform build need not import merchant destinations.
@immutable
class AdminNavigationCatalog {
  const AdminNavigationCatalog({
    required this.dashboardId,
    required this.merchantGroups,
    required this.platformGroups,
    required this.fallback,
    required this.dashboardBuilder,
  });

  final String dashboardId;
  final List<AdminNavGroup> merchantGroups;
  final List<AdminNavGroup> platformGroups;
  final AdminDestination fallback;
  final WidgetBuilder dashboardBuilder;

  List<AdminNavGroup> groupsFor(Set<AdminPermission> permissions) {
    final source = permissions.contains(AdminPermission.platformAdmin)
        ? platformGroups
        : merchantGroups;
    final visible = <AdminNavGroup>[];
    for (final group in source) {
      final allowed = group.destinations
          .where((destination) => destination.requires == null ||
              permissions.contains(destination.requires))
          .toList(growable: false);
      if (allowed.isNotEmpty) {
        visible.add(AdminNavGroup(title: group.title, destinations: allowed));
      }
    }
    return visible;
  }

  bool isVisible(
    AdminDestination destination,
    Set<AdminPermission> permissions,
  ) =>
      destination.requires == null || permissions.contains(destination.requires);

  AdminDestination byId(String id) {
    for (final group in [...merchantGroups, ...platformGroups]) {
      for (final destination in group.destinations) {
        if (destination.id == id) return destination;
      }
    }
    return fallback;
  }
}
