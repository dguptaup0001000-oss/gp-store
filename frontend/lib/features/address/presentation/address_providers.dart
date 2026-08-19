import 'dart:async';

import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../auth/presentation/auth_providers.dart';
import '../data/address_repository.dart';
import '../domain/address_models.dart';

final addressRepositoryProvider = Provider<AddressRepository>((ref) {
  return AddressRepository(apiClient: ref.watch(apiClientProvider));
});

/// How long a fetched address list stays usable after the last screen
/// watching it goes away.
///
/// Sized for the actual journey this exists to fix - cart -> checkout, and
/// checkout -> change address -> back - which happen within seconds of each
/// other. Long enough that those transitions never re-fetch, short enough
/// that a list edited on another device is not shown indefinitely.
const _addressCacheDuration = Duration(minutes: 3);

/// A customer's saved addresses.
///
/// Still autoDispose, but with a short keep-alive window rather than being
/// discarded the instant the last listener detaches.
///
/// The problem this solves: checkout opened, requested the address list,
/// waited, auto-selected an address, THEN requested the checkout preview,
/// and only then had anything real to render. Two sequential round trips
/// before the screen was useful - and because the provider was plain
/// autoDispose, this happened again on every single visit, including
/// bouncing to "Change address" and straight back.
///
/// With the window, the cart -> checkout transition reuses the list already
/// fetched moments earlier and the preview request starts immediately,
/// removing one full round trip from the critical path.
///
/// Deliberately NOT a permanent cache, and deliberately not applied to every
/// provider in the app: addresses are edited rarely and re-read often, which
/// is what makes them worth holding. Every mutation path (add, edit, delete,
/// set-default) already calls ref.invalidate(myAddressesProvider), so an
/// edit is still reflected immediately - the window only affects how long an
/// UNCHANGED list survives.
final myAddressesProvider = FutureProvider.autoDispose<List<AddressModel>>((ref) {
  final link = ref.keepAlive();
  final timer = Timer(_addressCacheDuration, link.close);
  ref.onDispose(timer.cancel);

  return ref.watch(addressRepositoryProvider).getMyAddresses();
});

/// checkDeliverable existed on the repository already but was never
/// actually called from anywhere - a customer could only discover whether
/// an address was deliverable after reaching checkout. This surfaces it
/// directly on the address list instead.
///
/// Left as plain autoDispose: deliverability depends on store configuration
/// and the address's coordinates, it is cheap to recompute, and caching a
/// stale "deliverable" answer is worse than re-asking.
final addressDeliverableProvider =
    FutureProvider.autoDispose.family<({bool deliverable, double? distanceKm}), int>((ref, addressId) {
  return ref.watch(addressRepositoryProvider).checkDeliverable(addressId);
});
