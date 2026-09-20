import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../admin/design/admin_components.dart';
import '../../../admin/design/admin_tokens.dart';
import '../../../core/search/search_debouncer.dart';
import '../../../core/util/haptic_widgets.dart';
import '../../auth/presentation/auth_providers.dart' show extractErrorMessage;
import '../domain/directory_models.dart';
import 'platform_providers.dart';
import 'platform_customer_profile_screen.dart';
import 'platform_merchant_profile_screen.dart';

/// Which directory this screen is showing.
enum DirectoryKind { merchants, customers }

/// The Merchants and Customers sections of the control tower.
///
/// ONE SCREEN FOR BOTH, because they are the same interaction - type, wait,
/// read a list, tap one - and two copies would drift in the details that
/// matter here: the debounce, the empty state, the error state, and the fact
/// that the server does the searching.
///
/// SERVER-SIDE, ALWAYS. Nothing downloads the customer table and filters it
/// on the phone. That would be slow on a real marketplace and it would also
/// mean shipping everybody's contact details to a device in order to show
/// one of them.
class PlatformDirectoryScreen extends ConsumerStatefulWidget {
  const PlatformDirectoryScreen({super.key, required this.kind});

  final DirectoryKind kind;

  @override
  ConsumerState<PlatformDirectoryScreen> createState() =>
      _PlatformDirectoryScreenState();
}

class _PlatformDirectoryScreenState
    extends ConsumerState<PlatformDirectoryScreen> {
  final _controller = TextEditingController();
  final _debouncer = SearchDebouncer();

  /// Superseded answers must not overwrite newer ones. The debouncer cancels
  /// the request, but a response already decoded can still arrive, so each
  /// search claims a number and only the current one is allowed to land.
  int _seq = 0;

  bool _loading = false;
  String? _error;
  bool _searched = false;
  DirectoryPage<MerchantHit> _merchants = DirectoryPage.empty();
  DirectoryPage<CustomerHit> _customers = DirectoryPage.empty();

  bool get _isMerchants => widget.kind == DirectoryKind.merchants;

  @override
  void dispose() {
    _controller.dispose();
    _debouncer.dispose();
    super.dispose();
  }

  Future<void> _search(String query, CancelToken _) async {
    final seq = ++_seq;
    setState(() {
      _loading = true;
      _error = null;
      _searched = true;
    });
    try {
      final repository = ref.read(platformRepositoryProvider);
      if (_isMerchants) {
        final page = await repository.searchMerchants(query: query);
        if (!mounted || seq != _seq) return;
        setState(() {
          _merchants = page;
          _loading = false;
        });
      } else {
        final page = await repository.searchCustomers(query: query);
        if (!mounted || seq != _seq) return;
        setState(() {
          _customers = page;
          _loading = false;
        });
      }
    } on DioException catch (e) {
      // A cancelled request is this screen's own doing - the operator typed
      // another character. Showing an error for it would turn ordinary typing
      // into a screen full of failures.
      if (e.type == DioExceptionType.cancel) return;
      if (!mounted || seq != _seq) return;
      setState(() {
        _error = extractErrorMessage(e);
        _loading = false;
      });
    } catch (e) {
      if (!mounted || seq != _seq) return;
      setState(() {
        _error = extractErrorMessage(e);
        _loading = false;
      });
    }
  }

  void _clear() {
    _seq++;
    setState(() {
      _searched = false;
      _error = null;
      _loading = false;
      _merchants = DirectoryPage.empty();
      _customers = DirectoryPage.empty();
    });
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: Text(_isMerchants ? 'Merchants' : 'Customers')),
      body: Column(
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(
                AdminSpacing.lg, AdminSpacing.lg, AdminSpacing.lg, AdminSpacing.sm),
            child: TextField(
              controller: _controller,
              autofocus: true,
              textInputAction: TextInputAction.search,
              decoration: InputDecoration(
                hintText: _isMerchants
                    ? 'Name, owner, email, phone, shop or M-123'
                    : 'Name, email, phone, order number or C-123',
                prefixIcon: const Icon(Icons.search),
                suffixIcon: _controller.text.isEmpty
                    ? null
                    : IconButton(
                        icon: const Icon(Icons.close),
                        onPressed: () {
                          _controller.clear();
                          _clear();
                        },
                      ),
              ),
              onChanged: (value) {
                setState(() {}); // keeps the clear button in step
                _debouncer.onQueryChanged(value,
                    onSearch: _search, onCleared: _clear);
              },
            ),
          ),
          if (_searched && !_loading && _error == null) _ResultCount(count: _total),
          Expanded(child: _body()),
        ],
      ),
    );
  }

  int get _total =>
      _isMerchants ? _merchants.totalElements : _customers.totalElements;

  Widget _body() {
    if (_loading) {
      return const Center(child: CircularProgressIndicator(color: AdminColors.primary));
    }
    if (_error != null) {
      return AdminErrorState(
        message: _error!,
        onRetry: hapticize(() => _search(_controller.text.trim(), CancelToken())),
      );
    }
    if (!_searched) {
      return _Hint(
        icon: _isMerchants ? Icons.business_outlined : Icons.people_outline,
        title: _isMerchants ? 'Find a merchant' : 'Find a customer',
        body: _isMerchants
            ? 'Search by business or owner name, email, phone, a shop name or code, '
                'or a merchant reference like M-123.'
            : 'Search by name, email, phone in any format, an order number, '
                'or a customer reference like C-123.',
      );
    }

    final rows = _isMerchants ? _merchants.content : _customers.content;
    if (rows.isEmpty) {
      return const _Hint(
        icon: Icons.search_off,
        title: 'Nothing matched',
        body: 'Check the spelling, or try a phone number or reference instead.',
      );
    }

    return ListView.separated(
      padding: const EdgeInsets.fromLTRB(
          AdminSpacing.lg, 0, AdminSpacing.lg, AdminSpacing.xxl),
      itemCount: rows.length,
      separatorBuilder: (_, __) => const SizedBox(height: AdminSpacing.sm),
      itemBuilder: (context, index) => _isMerchants
          ? _MerchantRow(
              merchant: _merchants.content[index],
              onTap: () => _openMerchant(_merchants.content[index]))
          : _CustomerRow(
              customer: _customers.content[index],
              onTap: () => _openCustomer(_customers.content[index])),
    );
  }

  void _openMerchant(MerchantHit merchant) {
    Navigator.of(context).push(MaterialPageRoute(
      builder: (_) => PlatformMerchantProfileScreen(
          merchantId: merchant.id, title: merchant.title),
    ));
  }

  void _openCustomer(CustomerHit customer) {
    Navigator.of(context).push(MaterialPageRoute(
      builder: (_) => PlatformCustomerProfileScreen(
          customerId: customer.id, title: customer.title),
    ));
  }
}

/// The server's total, not the page length.
class _ResultCount extends StatelessWidget {
  const _ResultCount({required this.count});

  final int count;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(AdminSpacing.lg, 0, AdminSpacing.lg, AdminSpacing.sm),
      child: Align(
        alignment: Alignment.centerLeft,
        child: Text(count == 1 ? '1 match' : '$count matches',
            style: const TextStyle(fontSize: 12, color: AdminColors.textSecondary)),
      ),
    );
  }
}

class _MerchantRow extends StatelessWidget {
  const _MerchantRow({required this.merchant, required this.onTap});

  final MerchantHit merchant;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return _Tappable(
      onTap: onTap,
      child: AdminSectionCard(
      title: merchant.title,
      subtitle: merchant.merchantRef,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          if (merchant.ownerName != null)
            _Line(icon: Icons.person_outline, text: merchant.ownerName!),
          if (merchant.email != null && merchant.email!.isNotEmpty)
            _Line(icon: Icons.mail_outline, text: merchant.email!),
          if (merchant.phone != null && merchant.phone!.isNotEmpty)
            _Line(icon: Icons.call_outlined, text: merchant.phone!),
          const SizedBox(height: 6),
          Wrap(spacing: 6, runSpacing: 4, children: [
            _Chip(merchant.shopsLabel),
            if (merchant.status != null) _Chip(merchant.status!),
            if (!merchant.active) const _Chip('Inactive'),
          ]),
        ],
      ),
      ),
    );
  }
}

class _CustomerRow extends StatelessWidget {
  const _CustomerRow({required this.customer, required this.onTap});

  final CustomerHit customer;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return _Tappable(
      onTap: onTap,
      child: AdminSectionCard(
      title: customer.title,
      subtitle: customer.customerRef,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          if (customer.email != null && customer.email!.isNotEmpty)
            _Line(icon: Icons.mail_outline, text: customer.email!),
          if (customer.phone != null && customer.phone!.isNotEmpty)
            _Line(icon: Icons.call_outlined, text: customer.phone!),
          const SizedBox(height: 6),
          Wrap(spacing: 6, runSpacing: 4, children: [
            _Chip(customer.ordersLabel),
            if (!customer.active) const _Chip('Inactive'),
          ]),
        ],
      ),
      ),
    );
  }
}

/// Makes a result card tappable without widening AdminSectionCard, which is
/// used in a dozen places that are not lists of things to open.
class _Tappable extends StatelessWidget {
  const _Tappable({required this.child, required this.onTap});

  final Widget child;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return InkWell(
      onTap: hapticize(onTap),
      borderRadius: AdminRadius.card,
      child: child,
    );
  }
}

class _Line extends StatelessWidget {
  const _Line({required this.icon, required this.text});

  final IconData icon;
  final String text;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(top: 3),
      child: Row(children: [
        Icon(icon, size: 14, color: AdminColors.textSecondary),
        const SizedBox(width: 6),
        Expanded(
          child: Text(text,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: const TextStyle(fontSize: 12.5, color: AdminColors.textSecondary)),
        ),
      ]),
    );
  }
}

class _Chip extends StatelessWidget {
  const _Chip(this.text);

  final String text;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
      decoration: BoxDecoration(
        color: AdminColors.background,
        borderRadius: BorderRadius.circular(20),
      ),
      child: Text(text,
          style: const TextStyle(fontSize: 11.5, color: AdminColors.textSecondary)),
    );
  }
}

class _Hint extends StatelessWidget {
  const _Hint({required this.icon, required this.title, required this.body});

  final IconData icon;
  final String title;
  final String body;

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(28),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(icon, size: 40, color: AdminColors.textSecondary),
            const SizedBox(height: 12),
            Text(title,
                textAlign: TextAlign.center,
                style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 15)),
            const SizedBox(height: 6),
            Text(body,
                textAlign: TextAlign.center,
                style: const TextStyle(fontSize: 13, color: AdminColors.textSecondary)),
          ],
        ),
      ),
    );
  }
}
