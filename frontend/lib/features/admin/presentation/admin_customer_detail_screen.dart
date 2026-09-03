import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../admin/design/admin_components.dart';
import '../../../admin/design/admin_tokens.dart';
import '../../../core/util/haptic_widgets.dart';
import '../../auth/presentation/auth_providers.dart';
import '../domain/admin_customer_detail_model.dart';
import 'admin_customer_orders_screen.dart';
import 'admin_providers.dart';

/// Everything the shop knows about one customer, on one screen.
///
/// WHY THIS EXISTS. The customer list gave a shopkeeper a name and a phone
/// number, and tapping it jumped straight to order history. Answering "what
/// is this person's address", "what have they got in their basket right now"
/// or "are they actually using the app" meant three more screens, or the
/// database. This is the one screen those questions have.
///
/// IT IS A SENSITIVE SCREEN AND IT SAYS SO. A named person's phone number,
/// their home address and the directions to their front door are all here.
/// The provider is autoDispose so it does not linger in memory after the
/// screen closes, precise coordinates are never sent to it at all, and the
/// engagement figures carry a plain-language caveat rather than being
/// presented as measurement.
class AdminCustomerDetailScreen extends ConsumerWidget {
  const AdminCustomerDetailScreen({
    super.key,
    required this.customerId,
    required this.customerName,
  });

  final int customerId;
  final String customerName;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final detailAsync = ref.watch(adminCustomerDetailProvider(customerId));

    return Scaffold(
      backgroundColor: AdminColors.background,
      appBar: AppBar(title: Text(customerName)),
      body: detailAsync.when(
        loading: () => const AdminListSkeleton(),
        error: (error, stackTrace) => AdminErrorState(
          message: "Couldn't load this customer: ${extractErrorMessage(error)}",
          onRetry:
              hapticize(() => ref.invalidate(adminCustomerDetailProvider(customerId))),
        ),
        data: (detail) => RefreshIndicator(
          onRefresh: () async {
            ref.invalidate(adminCustomerDetailProvider(customerId));
          },
          child: ListView(
            padding: const EdgeInsets.all(AdminSpacing.lg),
            children: [
              _IdentityCard(detail: detail),
              const SizedBox(height: AdminSpacing.md),
              _OrdersCard(detail: detail, customerName: customerName),
              const SizedBox(height: AdminSpacing.md),
              _AddressesCard(addresses: detail.addresses),
              const SizedBox(height: AdminSpacing.md),
              _CartCard(cart: detail.cart),
              const SizedBox(height: AdminSpacing.md),
              _WishlistCard(wishlist: detail.wishlist),
              const SizedBox(height: AdminSpacing.md),
              _EngagementCard(engagement: detail.engagement),
              const SizedBox(height: AdminSpacing.xl),
            ],
          ),
        ),
      ),
    );
  }
}

// --- Identity -------------------------------------------------------------

class _IdentityCard extends StatelessWidget {
  const _IdentityCard({required this.detail});

  final AdminCustomerDetail detail;

  @override
  Widget build(BuildContext context) {
    return AdminSectionCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              _Avatar(url: detail.profileImageUrl, name: detail.fullName),
              const SizedBox(width: AdminSpacing.md),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(detail.fullName, style: AdminText.sectionTitle),
                    const SizedBox(height: AdminSpacing.xs),
                    Wrap(
                      spacing: AdminSpacing.sm,
                      runSpacing: AdminSpacing.xs,
                      children: [
                        if (detail.role != null && detail.role != 'CUSTOMER')
                          _Chip(text: detail.role!, tone: AdminColors.info),
                        _Chip(
                          text: detail.active ? 'Active' : 'Deactivated',
                          tone: detail.active
                              ? AdminColors.success
                              : AdminColors.danger,
                        ),
                        _Chip(
                          text: detail.verified ? 'Verified' : 'Not verified',
                          tone: detail.verified
                              ? AdminColors.success
                              : AdminColors.warning,
                        ),
                      ],
                    ),
                  ],
                ),
              ),
            ],
          ),
          const SizedBox(height: AdminSpacing.lg),
          // Both are tap-to-copy: the commonest thing a shopkeeper does with
          // this screen is ring the customer or paste the address into a
          // message, and retyping a phone number off a screen is how wrong
          // numbers get dialled.
          _CopyRow(
            icon: Icons.phone_outlined,
            label: 'Phone',
            value: detail.mobileNumber,
          ),
          _CopyRow(
            icon: Icons.mail_outline,
            label: 'Email',
            value: detail.email,
          ),
        ],
      ),
    );
  }
}

class _Avatar extends StatelessWidget {
  const _Avatar({required this.url, required this.name});

  final String? url;
  final String name;

  static const double _size = 52;

  @override
  Widget build(BuildContext context) {
    final initial = name.trim().isEmpty ? '?' : name.trim()[0].toUpperCase();
    final fallback = Container(
      width: _size,
      height: _size,
      alignment: Alignment.center,
      decoration: const BoxDecoration(
        color: AdminColors.primaryLight,
        shape: BoxShape.circle,
      ),
      child: Text(
        initial,
        style: const TextStyle(
          fontSize: 20,
          fontWeight: FontWeight.w700,
          color: AdminColors.primaryDeep,
        ),
      ),
    );

    final source = url;
    if (source == null || source.isEmpty) return fallback;

    return ClipOval(
      child: Image.network(
        source,
        width: _size,
        height: _size,
        fit: BoxFit.cover,
        loadingBuilder: (context, child, progress) =>
            progress == null ? child : fallback,
        errorBuilder: (context, error, stack) => fallback,
      ),
    );
  }
}

class _CopyRow extends StatelessWidget {
  const _CopyRow({required this.icon, required this.label, required this.value});

  final IconData icon;
  final String label;
  final String? value;

  @override
  Widget build(BuildContext context) {
    final text = value;
    final missing = text == null || text.trim().isEmpty;

    return Padding(
      padding: const EdgeInsets.only(bottom: AdminSpacing.sm),
      child: InkWell(
        borderRadius: AdminRadius.control,
        onTap: missing
            ? null
            : hapticize(() async {
                await Clipboard.setData(ClipboardData(text: text));
                if (!context.mounted) return;
                ScaffoldMessenger.of(context).showSnackBar(
                  SnackBar(content: Text('$label copied')),
                );
              }),
        child: Padding(
          padding: const EdgeInsets.symmetric(vertical: AdminSpacing.xs),
          child: Row(
            children: [
              Icon(icon, size: 18, color: AdminColors.textSecondary),
              const SizedBox(width: AdminSpacing.sm),
              Expanded(
                child: Text(
                  missing ? 'Not on file' : text,
                  style: missing ? AdminText.bodyMuted : AdminText.body,
                ),
              ),
              if (!missing)
                const Icon(Icons.copy_rounded,
                    size: 16, color: AdminColors.textMuted),
            ],
          ),
        ),
      ),
    );
  }
}

class _Chip extends StatelessWidget {
  const _Chip({required this.text, required this.tone});

  final String text;
  final Color tone;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
      decoration: BoxDecoration(
        color: tone.withValues(alpha: 0.12),
        borderRadius: AdminRadius.badge,
      ),
      child: Text(
        text,
        style: TextStyle(fontSize: 10, fontWeight: FontWeight.w700, color: tone),
      ),
    );
  }
}

// --- Orders ---------------------------------------------------------------

class _OrdersCard extends StatelessWidget {
  const _OrdersCard({required this.detail, required this.customerName});

  final AdminCustomerDetail detail;
  final String customerName;

  @override
  Widget build(BuildContext context) {
    final orders = detail.orders;

    return AdminSectionCard(
      title: 'Orders',
      trailing: TextButton(
        onPressed: hapticize(() => Navigator.of(context).push(
              MaterialPageRoute(
                builder: (_) => AdminCustomerOrdersScreen(
                  customerId: detail.id,
                  customerName: customerName,
                ),
              ),
            )),
        child: const Text('History'),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              _Stat(label: 'Placed', value: '${orders.count}'),
              _Stat(
                label: 'Lifetime spend',
                // Cancelled orders are excluded server-side, so this is money
                // the shop actually took, not money it was once promised.
                value: '₹${orders.lifetimeSpend.toStringAsFixed(0)}',
              ),
              _Stat(label: 'Cancelled', value: '${orders.cancelledCount}'),
            ],
          ),
          const SizedBox(height: AdminSpacing.md),
          _Line(label: 'First order', value: _date(orders.firstOrderDate)),
          _Line(label: 'Last order', value: _date(orders.lastOrderDate)),
        ],
      ),
    );
  }
}

class _Stat extends StatelessWidget {
  const _Stat({required this.label, required this.value});

  final String label;
  final String value;

  @override
  Widget build(BuildContext context) {
    return Expanded(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(value, style: AdminText.metric),
          const SizedBox(height: 2),
          Text(label, style: AdminText.caption),
        ],
      ),
    );
  }
}

class _Line extends StatelessWidget {
  const _Line({required this.label, required this.value});

  final String label;
  final String value;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(bottom: AdminSpacing.xs),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.spaceBetween,
        children: [
          Text(label, style: AdminText.bodyMuted),
          Text(value, style: AdminText.body),
        ],
      ),
    );
  }
}

// --- Addresses ------------------------------------------------------------

class _AddressesCard extends StatelessWidget {
  const _AddressesCard({required this.addresses});

  final List<CustomerAddressLine> addresses;

  @override
  Widget build(BuildContext context) {
    return AdminSectionCard(
      title: 'Addresses',
      subtitle: addresses.isEmpty ? null : '${addresses.length} saved',
      child: addresses.isEmpty
          ? const Text('No address saved yet.', style: AdminText.bodyMuted)
          : Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                for (final address in addresses) _AddressBlock(address: address),
              ],
            ),
    );
  }
}

class _AddressBlock extends StatelessWidget {
  const _AddressBlock({required this.address});

  final CustomerAddressLine address;

  @override
  Widget build(BuildContext context) {
    final directions = address.directions;
    final landmark = address.landmark;

    return Container(
      margin: const EdgeInsets.only(bottom: AdminSpacing.sm),
      padding: const EdgeInsets.all(AdminSpacing.md),
      decoration: BoxDecoration(
        color: AdminColors.neutralBg,
        borderRadius: AdminRadius.control,
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              if (address.label != null && address.label!.isNotEmpty)
                Padding(
                  padding: const EdgeInsets.only(right: AdminSpacing.sm),
                  child: _Chip(text: address.label!, tone: AdminColors.primary),
                ),
              if (address.isDefault)
                const _Chip(text: 'DEFAULT', tone: AdminColors.info),
              const Spacer(),
              // A saved address with no pin is a delivery a rider will have to
              // find by asking. Worth showing, so it can be fixed on a call.
              Icon(
                address.hasLocation
                    ? Icons.location_on_outlined
                    : Icons.location_off_outlined,
                size: 16,
                color: address.hasLocation
                    ? AdminColors.success
                    : AdminColors.textMuted,
              ),
            ],
          ),
          const SizedBox(height: AdminSpacing.sm),
          Text(address.address, style: AdminText.body),
          if (address.pincode != null && address.pincode!.isNotEmpty)
            Text('PIN ${address.pincode}', style: AdminText.caption),
          if (landmark != null && landmark.trim().isNotEmpty) ...[
            const SizedBox(height: AdminSpacing.xs),
            Text('Landmark: $landmark', style: AdminText.bodyMuted),
          ],
          if (directions != null && directions.trim().isNotEmpty) ...[
            const SizedBox(height: AdminSpacing.sm),
            Container(
              width: double.infinity,
              padding: const EdgeInsets.all(AdminSpacing.sm),
              decoration: BoxDecoration(
                color: AdminColors.surface,
                borderRadius: AdminRadius.control,
                border: Border.all(color: AdminColors.border),
              ),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text('In their words', style: AdminText.overline),
                  const SizedBox(height: 2),
                  // Shown verbatim. These are directions somebody typed for a
                  // rider - "hanuman mandir ke piche, green colour ki house" -
                  // and tidying them up would destroy the thing that makes
                  // them useful.
                  Text(directions, style: AdminText.body),
                ],
              ),
            ),
          ],
          if (address.fullName != null || address.mobileNumber != null) ...[
            const SizedBox(height: AdminSpacing.xs),
            Text(
              [address.fullName, address.mobileNumber]
                  .whereType<String>()
                  .where((s) => s.trim().isNotEmpty)
                  .join(' · '),
              style: AdminText.caption,
            ),
          ],
        ],
      ),
    );
  }
}

// --- Cart -----------------------------------------------------------------

class _CartCard extends StatelessWidget {
  const _CartCard({required this.cart});

  final CustomerCartSummary cart;

  @override
  Widget build(BuildContext context) {
    return AdminSectionCard(
      title: 'Basket right now',
      subtitle: cart.items.isEmpty
          ? null
          : '${cart.totalItems} item${cart.totalItems == 1 ? '' : 's'} · ₹${cart.totalAmount.toStringAsFixed(0)}',
      child: cart.items.isEmpty
          ? const Text('Their basket is empty.', style: AdminText.bodyMuted)
          : Column(
              children: [
                for (final line in cart.items)
                  Padding(
                    padding: const EdgeInsets.only(bottom: AdminSpacing.sm),
                    child: Row(
                      children: [
                        _Thumb(url: line.imageUrl),
                        const SizedBox(width: AdminSpacing.md),
                        Expanded(
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Text(line.productName, style: AdminText.body),
                              if (line.pack != null && line.pack!.isNotEmpty)
                                Text(line.pack!, style: AdminText.caption),
                            ],
                          ),
                        ),
                        Text('× ${line.quantity}', style: AdminText.numeric),
                        const SizedBox(width: AdminSpacing.md),
                        Text('₹${line.totalPrice.toStringAsFixed(0)}',
                            style: AdminText.numeric),
                      ],
                    ),
                  ),
              ],
            ),
    );
  }
}

// --- Wishlist -------------------------------------------------------------

class _WishlistCard extends StatelessWidget {
  const _WishlistCard({required this.wishlist});

  final List<CustomerWishlistLine> wishlist;

  @override
  Widget build(BuildContext context) {
    return AdminSectionCard(
      title: 'Wishlist',
      subtitle: wishlist.isEmpty ? null : '${wishlist.length} saved',
      child: wishlist.isEmpty
          ? const Text('Nothing saved for later.', style: AdminText.bodyMuted)
          : Column(
              children: [
                for (final item in wishlist)
                  Padding(
                    padding: const EdgeInsets.only(bottom: AdminSpacing.sm),
                    child: Row(
                      children: [
                        _Thumb(url: item.imageUrl),
                        const SizedBox(width: AdminSpacing.md),
                        Expanded(
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Text(item.productName, style: AdminText.body),
                              if (item.brand != null && item.brand!.isNotEmpty)
                                Text(item.brand!, style: AdminText.caption),
                            ],
                          ),
                        ),
                      ],
                    ),
                  ),
              ],
            ),
    );
  }
}

// --- Engagement -----------------------------------------------------------

class _EngagementCard extends StatelessWidget {
  const _EngagementCard({required this.engagement});

  final CustomerEngagement engagement;

  @override
  Widget build(BuildContext context) {
    final never = engagement.sessionCount == 0;

    return AdminSectionCard(
      title: 'App usage',
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          if (never)
            const Text(
              'No app sessions recorded yet.',
              style: AdminText.bodyMuted,
            )
          else
            Row(
              children: [
                _Stat(
                  label: 'Total time in app',
                  value: _duration(engagement.totalSeconds),
                ),
                _Stat(label: 'Sessions', value: '${engagement.sessionCount}'),
                _Stat(label: 'Last seen', value: _date(engagement.lastSeen)),
              ],
            ),
          const SizedBox(height: AdminSpacing.md),
          // Says out loud what this number is. It comes from the customer's
          // own phone, is capped per session and per hour, and is here to
          // tell a regular from somebody who installed the app once. It is
          // not evidence and nothing should be decided against a person on it.
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Icon(Icons.info_outline,
                  size: 14, color: AdminColors.textMuted),
              const SizedBox(width: AdminSpacing.sm),
              const Expanded(
                child: Text(
                  "Reported by the customer's own app and capped, so treat "
                  'it as a rough impression rather than a measurement.',
                  style: AdminText.caption,
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }
}

// --- Shared bits ----------------------------------------------------------

/// A product thumbnail and its stand-in.
///
/// Every failure lands on the same grey box: a signed URL that expired, a
/// dropped connection, a product with no picture. None of those deserve a
/// broken-image glyph on a staff screen.
class _Thumb extends StatelessWidget {
  const _Thumb({required this.url});

  final String? url;

  static const double _size = 40;

  @override
  Widget build(BuildContext context) {
    final placeholder = Container(
      width: _size,
      height: _size,
      decoration: BoxDecoration(
        color: AdminColors.neutralBg,
        borderRadius: BorderRadius.circular(AdminRadius.sm),
      ),
      child: const Icon(Icons.inventory_2_outlined,
          size: 18, color: AdminColors.textMuted),
    );

    final source = url;
    if (source == null || source.isEmpty) return placeholder;

    return ClipRRect(
      borderRadius: BorderRadius.circular(AdminRadius.sm),
      child: Image.network(
        source,
        width: _size,
        height: _size,
        fit: BoxFit.cover,
        loadingBuilder: (context, child, progress) =>
            progress == null ? child : placeholder,
        errorBuilder: (context, error, stack) => placeholder,
      ),
    );
  }
}

String _date(DateTime? value) {
  if (value == null) return '—';
  final local = value.toLocal();
  final d = local.day.toString().padLeft(2, '0');
  final m = local.month.toString().padLeft(2, '0');
  return '$d/$m/${local.year}';
}

/// Hours and minutes, never a bare seconds count. "7h 20m" is a fact a
/// shopkeeper can use; "26400" is a number they have to do arithmetic on.
String _duration(int seconds) {
  if (seconds < 60) return '${seconds}s';
  final minutes = seconds ~/ 60;
  if (minutes < 60) return '${minutes}m';
  final hours = minutes ~/ 60;
  final remainder = minutes % 60;
  return remainder == 0 ? '${hours}h' : '${hours}h ${remainder}m';
}
