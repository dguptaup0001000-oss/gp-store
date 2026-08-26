import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:geolocator/geolocator.dart';
import 'package:url_launcher/url_launcher.dart';

import '../../../core/theme/app_theme.dart';
import '../../auth/presentation/auth_providers.dart';
import '../domain/delivery_assignment_model.dart';
import 'delivery_partner_providers.dart';
import '../../../core/util/haptic_widgets.dart';

class DeliveryDashboardScreen extends ConsumerStatefulWidget {
  const DeliveryDashboardScreen({super.key});

  @override
  ConsumerState<DeliveryDashboardScreen> createState() =>
      _DeliveryDashboardScreenState();
}

class _DeliveryDashboardScreenState
    extends ConsumerState<DeliveryDashboardScreen> {
  StreamSubscription<Position>? _positionSub;
  bool _isTracking = false;

  @override
  void initState() {
    super.initState();
    // If the partner is re-opening the app while still marked available
    // from an earlier session (e.g. app was killed mid-shift), resume
    // sharing location automatically instead of leaving them silently
    // "on duty" with a stale/no location on record.
    _resumeTrackingIfAlreadyOnDuty();
  }

  Future<void> _resumeTrackingIfAlreadyOnDuty() async {
    try {
      final profile =
          await ref.read(deliveryPartnerRepositoryProvider).getMyProfile();
      if (profile.available && mounted) {
        _startTracking();
      }
    } catch (_) {
      // Profile fetch failing here isn't fatal - myDeliveryProfileProvider
      // below will surface the real error in the UI; this is just the
      // opportunistic auto-resume check.
    }
  }

  @override
  void dispose() {
    _positionSub?.cancel();
    super.dispose();
  }

  /// Starts pushing this device's GPS position to the backend every time it
  /// moves a meaningful distance, for as long as the partner stays on duty.
  /// Same permission-request pattern as AddAddressScreen's "use my
  /// location" - see that file for why each check exists.
  Future<void> _startTracking() async {
    if (_isTracking) return;

    try {
      final serviceEnabled = await Geolocator.isLocationServiceEnabled();
      if (!serviceEnabled) {
        throw Exception(
            'Please turn on location services to share your position while this screen is open');
      }

      LocationPermission permission = await Geolocator.checkPermission();
      if (permission == LocationPermission.denied) {
        permission = await Geolocator.requestPermission();
        if (permission == LocationPermission.denied) {
          throw Exception(
              'Location permission denied - your position is not shared while this screen is open');
        }
      }
      if (permission == LocationPermission.deniedForever) {
        throw Exception(
            'Location permission permanently denied - enable it in app settings');
      }

      _positionSub?.cancel();
      // distanceFilter: only pushes an update once the device has actually
      // moved ~20m, not on a fixed timer - avoids hammering the API (and
      // the partner's battery/data) while stopped at a light or a stop.
      // NOTE: this only tracks while the app is open and in the
      // foreground - if the partner backgrounds the app, updates stop.
      // A true background service is a real feature on its own (needs
      // ACCESS_BACKGROUND_LOCATION + a foreground service on Android) -
      // deliberately out of scope for this first version.
      _positionSub = Geolocator.getPositionStream(
        locationSettings: const LocationSettings(
            accuracy: LocationAccuracy.high, distanceFilter: 20),
      ).listen(
        (position) {
          ref.read(deliveryPartnerRepositoryProvider).updateMyLocation(
                latitude: position.latitude,
                longitude: position.longitude,
              );
        },
        onError: (_) {
          if (mounted) setState(() => _isTracking = false);
        },
      );

      if (mounted) setState(() => _isTracking = true);
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text(e.toString().replaceFirst('Exception: ', ''))),
      );
    }
  }

  void _stopTracking() {
    _positionSub?.cancel();
    _positionSub = null;
    if (mounted) setState(() => _isTracking = false);
  }

  Future<void> _toggleAvailability(bool newValue) async {
    try {
      await ref
          .read(deliveryPartnerRepositoryProvider)
          .setMyAvailability(newValue);
      ref.invalidate(myDeliveryProfileProvider);

      if (newValue) {
        await _startTracking();
      } else {
        _stopTracking();
      }
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text(extractErrorMessage(e))),
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    final assignmentsAsync = ref.watch(myAssignmentsProvider);
    final profileAsync = ref.watch(myDeliveryProfileProvider);

    return Scaffold(
      appBar: AppBar(
        title: const Text('My Deliveries'),
        actions: [
          if (_isTracking)
            const Padding(
              padding: EdgeInsets.only(right: 4),
              child: Tooltip(
                message: 'Sharing location while this screen is open',
                child:
                    Icon(Icons.gps_fixed, size: 18, color: AppColors.primary),
              ),
            ),
          profileAsync.when(
            loading: () => const SizedBox(
              width: 24,
              height: 24,
              child: Padding(
                padding: EdgeInsets.all(8),
                child: CircularProgressIndicator(strokeWidth: 2),
              ),
            ),
            error: (error, stackTrace) => IconButton(
              icon: const Icon(Icons.refresh),
              tooltip: "Couldn't load duty status — retry",
              onPressed:
                  hapticize(() => ref.invalidate(myDeliveryProfileProvider)),
            ),
            data: (profile) => Padding(
              padding: const EdgeInsets.symmetric(horizontal: 4),
              child: Row(
                children: [
                  Text(profile.available ? 'On duty' : 'Off duty',
                      style: const TextStyle(fontSize: 12)),
                  Switch(
                    value: profile.available,
                    onChanged: hapticizeValue(_toggleAvailability),
                  ),
                ],
              ),
            ),
          ),
          IconButton(
            icon: const Icon(Icons.logout),
            tooltip: 'Log out',
            onPressed: hapticize(
                () => ref.read(authControllerProvider.notifier).logout()),
          ),
        ],
      ),
      body: Column(
        children: [
          if (_isTracking)
            Material(
              color: AppColors.primary.withValues(alpha: 0.08),
              child: const Padding(
                padding: EdgeInsets.symmetric(horizontal: 16, vertical: 10),
                child: Text(
                  'Location is shared only while this screen is open. '
                  'It stops if you leave the app. We do not track in the background.',
                ),
              ),
            ),
          Expanded(
            child: assignmentsAsync.when(
        loading: () =>
            const Center(child: CircularProgressIndicator(strokeWidth: 2)),
        error: (error, stackTrace) => Center(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              // TEMPORARY, for active debugging - see RootScreen's identical
              // comment for why this shows the real failure reason instead
              // of one static string.
              Text(
                  "Couldn't load your deliveries: ${extractErrorMessage(error)}"),
              TextButton(
                  onPressed:
                      hapticize(() => ref.invalidate(myAssignmentsProvider)),
                  child: const Text('Retry')),
            ],
          ),
        ),
        data: (assignments) {
          if (assignments.isEmpty) {
            return const Center(
              child: Text('No deliveries assigned right now',
                  style: TextStyle(color: AppColors.textSecondary)),
            );
          }

          return RefreshIndicator(
            onRefresh: () async => ref.invalidate(myAssignmentsProvider),
            child: ListView.separated(
              padding: const EdgeInsets.all(16),
              itemCount: assignments.length,
              separatorBuilder: (_, __) => const SizedBox(height: 12),
              itemBuilder: (context, index) =>
                  _AssignmentCard(assignment: assignments[index]),
            ),
          );
        },
            ),
          ),
        ],
      ),
    );
  }
}

class _AssignmentCard extends ConsumerStatefulWidget {
  const _AssignmentCard({required this.assignment});

  final DeliveryAssignment assignment;

  @override
  ConsumerState<_AssignmentCard> createState() => _AssignmentCardState();
}

class _AssignmentCardState extends ConsumerState<_AssignmentCard> {
  bool _isUpdating = false;

  Future<void> _callCustomer() async {
    final phone = widget.assignment.customerPhone;
    if (phone == null) return;
    await launchUrl(Uri(scheme: 'tel', path: phone));
  }

  Future<void> _navigate() async {
    final lat = widget.assignment.latitude;
    final lng = widget.assignment.longitude;
    if (lat == null || lng == null) return;
    // Universal Google Maps link - opens the Maps app if installed (mobile),
    // or the Maps website otherwise (including on web builds of this app) -
    // more reliable across platforms than the Android-only geo: URI scheme.
    await launchUrl(
      Uri.parse('https://www.google.com/maps/dir/?api=1&destination=$lat,$lng'),
      mode: LaunchMode.externalApplication,
    );
  }

  Future<void> _advanceStatus(String newStatus) async {
    if (_isUpdating) return;
    setState(() => _isUpdating = true);

    try {
      await ref.read(deliveryPartnerRepositoryProvider).updateStatus(
            deliveryId: widget.assignment.deliveryId,
            status: newStatus,
          );
      ref.invalidate(myAssignmentsProvider);
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text(extractErrorMessage(e))),
      );
    } finally {
      if (mounted) setState(() => _isUpdating = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final assignment = widget.assignment;
    final status = assignment.deliveryStatus ?? 'ASSIGNED';

    return Container(
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
          color: AppColors.cardBackground,
          borderRadius: BorderRadius.circular(12)),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              Text('#${assignment.orderNumber ?? assignment.deliveryId}',
                  style: const TextStyle(fontWeight: FontWeight.w700)),
              Container(
                padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
                decoration: BoxDecoration(
                    color: AppColors.primary.withValues(alpha: 0.12),
                    borderRadius: BorderRadius.circular(6)),
                child: Text(
                  status.replaceAll('_', ' '),
                  style: const TextStyle(
                      fontSize: 10,
                      fontWeight: FontWeight.w700,
                      color: AppColors.primary),
                ),
              ),
            ],
          ),
          const SizedBox(height: 8),
          if (assignment.customerName != null)
            Text(assignment.customerName!,
                style: const TextStyle(fontWeight: FontWeight.w600)),
          if (assignment.deliveryAddress != null)
            Text(assignment.deliveryAddress!,
                style: Theme.of(context).textTheme.bodyMedium),
          const SizedBox(height: 12),
          Row(
            children: [
              if (assignment.latitude != null && assignment.longitude != null)
                Expanded(
                  child: OutlinedButton.icon(
                    onPressed: hapticize(_navigate),
                    icon: const Icon(Icons.directions, size: 16),
                    label: const Text('Navigate'),
                  ),
                ),
              if (assignment.latitude != null &&
                  assignment.longitude != null &&
                  assignment.customerPhone != null)
                const SizedBox(width: 8),
              if (assignment.customerPhone != null)
                Expanded(
                  child: OutlinedButton.icon(
                    onPressed: hapticize(_callCustomer),
                    icon: const Icon(Icons.call, size: 16),
                    label: const Text('Call'),
                  ),
                ),
            ],
          ),
          const SizedBox(height: 8),
          SizedBox(width: double.infinity, child: _statusActionButton(status)),
        ],
      ),
    );
  }

  Widget _statusActionButton(String status) {
    // Matches exactly the two transitions the backend gives real side
    // effects to (order status sync, notifications) - see
    // DeliveryService.updateDeliveryStatus.
    if (status == 'ASSIGNED') {
      return FilledButton(
        onPressed:
            _isUpdating ? null : () => _advanceStatus('OUT_FOR_DELIVERY'),
        child: _isUpdating
            ? const SizedBox(
                height: 16,
                width: 16,
                child: CircularProgressIndicator(
                    strokeWidth: 2, color: Colors.white))
            : const Text('Start Delivery'),
      );
    }

    if (status == 'OUT_FOR_DELIVERY') {
      return FilledButton(
        onPressed: _isUpdating ? null : () => _advanceStatus('DELIVERED'),
        child: _isUpdating
            ? const SizedBox(
                height: 16,
                width: 16,
                child: CircularProgressIndicator(
                    strokeWidth: 2, color: Colors.white))
            : const Text('Mark Delivered'),
      );
    }

    return const SizedBox.shrink();
  }
}
