import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../admin/design/admin_components.dart';
import '../../../admin/design/admin_tokens.dart';
import '../../../core/images/gp_network_image.dart';
import '../../../core/search/search_debouncer.dart';
import '../../../core/util/haptic_widgets.dart';
import '../../auth/presentation/auth_providers.dart' show extractErrorMessage;
import '../domain/catalogue_item.dart';
import '../domain/selling_mode.dart';
import 'admin_providers.dart';
import '../../products/domain/product_models.dart';
import 'admin_product_form_screen.dart';
import 'admin_variant_form_dialog.dart';

/// Everything this shop sells in one commerce mode.
///
/// <h2>Why this screen exists at all</h2>
///
/// The commerce modes shipped with a backend, a migration, an API and tests -
/// and no way for a merchant to reach them. The only door was Products → open
/// a variant → scroll to a section called "How you sell this", which a
/// shopkeeper has no reason to go looking for. A feature nobody can find is
/// not a feature, and the drawer is where merchants look.
///
/// <h2>One screen, both modes</h2>
///
/// Visit to Buy and Services at Shop are the same interaction - list what you
/// have, search it, add another - differing in wording and in which fields
/// matter. Two copies would drift in the details that count: the empty state
/// that explains what the mode is FOR, and the fact that neither shows stock,
/// because counted stock is a question about things on a shelf and a haircut
/// has none.
class MerchantModeCatalogueScreen extends ConsumerStatefulWidget {
  const MerchantModeCatalogueScreen({super.key, required this.mode});

  final SellingMode mode;

  @override
  ConsumerState<MerchantModeCatalogueScreen> createState() =>
      _MerchantModeCatalogueScreenState();
}

class _MerchantModeCatalogueScreenState
    extends ConsumerState<MerchantModeCatalogueScreen> {
  final _controller = TextEditingController();
  final _debouncer = SearchDebouncer();

  int _seq = 0;
  bool _loading = true;
  String? _error;
  CataloguePage _page = CataloguePage.empty;

  /// EVERY PAGE FETCHED SO FAR, not just the last one.
  ///
  /// This screen used to draw _page.content and nothing else, which meant a
  /// merchant with 31 Visit-to-Buy items could see thirty of them and had no
  /// way to reach the thirty-first. Silently dropping a merchant's stock off
  /// the end of their own list is the same class of bug as the drawer entry
  /// that was never added: the data is there and the screen does not show it.
  final List<CatalogueItem> _items = [];

  bool get _isService => widget.mode == SellingMode.serviceAtShop;

  @override
  void initState() {
    super.initState();
    _load('');
  }

  @override
  void dispose() {
    _controller.dispose();
    _debouncer.dispose();
    super.dispose();
  }

  Future<void> _load(String query, [CancelToken? _]) => _fetch(query, 0);

  /// Page 0 replaces what is on screen; any later page is appended. A search
  /// always starts at 0, because the results of "atta" are not a continuation
  /// of the results of nothing.
  Future<void> _fetch(String query, int page) async {
    final seq = ++_seq;
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final result = await ref
          .read(adminProductsRepositoryProvider)
          .catalogue(mode: widget.mode, query: query, page: page);
      if (!mounted || seq != _seq) return;
      setState(() {
        if (page == 0) _items.clear();
        _items.addAll(result.content);
        _page = result;
        _loading = false;
      });
    } on DioException catch (e) {
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

  Future<void> _add() async {
    // REUSES THE EXISTING PRODUCT FORM, which already knows how to create a
    // product and its first variant, validate prices, upload images and pick
    // a category. It carries the mode in so the form opens on the right
    // setting rather than defaulting to Buy Online and making the merchant
    // change it every time.
    final created = await Navigator.of(context).push<bool>(MaterialPageRoute(
      builder: (_) => AdminProductFormScreen(initialSellingMode: widget.mode),
    ));
    if (created == true && mounted) {
      _load(_controller.text.trim());
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Text(_isService ? 'Services at Shop' : 'Visit to Buy'),
      ),
      floatingActionButton: FloatingActionButton.extended(
        onPressed: hapticize(_add),
        backgroundColor: AdminColors.primary,
        foregroundColor: Colors.white,
        icon: const Icon(Icons.add),
        label: Text(_isService ? 'Add service' : 'Add item'),
      ),
      body: Column(
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(
                AdminSpacing.lg, AdminSpacing.lg, AdminSpacing.lg, AdminSpacing.sm),
            child: TextField(
              controller: _controller,
              decoration: InputDecoration(
                hintText: _isService ? 'Search your services…' : 'Search your items…',
                prefixIcon: const Icon(Icons.search),
                suffixIcon: _controller.text.isEmpty
                    ? null
                    : IconButton(
                        icon: const Icon(Icons.close),
                        onPressed: () {
                          _controller.clear();
                          setState(() {});
                          _load('');
                        },
                      ),
              ),
              onChanged: (value) {
                setState(() {});
                _debouncer.onQueryChanged(
                  value,
                  onSearch: (query, token) => _load(query, token),
                  onCleared: () => _load(''),
                );
              },
            ),
          ),
          Expanded(child: _body()),
        ],
      ),
    );
  }

  Widget _body() {
    // THE SPINNER ONLY TAKES THE WHOLE SCREEN WHEN THERE IS NOTHING TO KEEP.
    // Fetching the next page must not blank out the thirty items the
    // merchant is already looking at; the footer button shows the wait.
    if (_loading && _items.isEmpty) {
      return const Center(child: CircularProgressIndicator(color: AdminColors.primary));
    }
    if (_error != null) {
      return AdminErrorState(
        message: _error!,
        onRetry: hapticize(() => _load(_controller.text.trim())),
      );
    }
    if (_items.isEmpty) {
      return _Empty(mode: widget.mode, searching: _controller.text.trim().isNotEmpty);
    }

    final more = _items.length < _page.totalElements;
    return RefreshIndicator(
      color: AdminColors.primary,
      onRefresh: () => _load(_controller.text.trim()),
      child: ListView.separated(
        padding: const EdgeInsets.fromLTRB(
            AdminSpacing.lg, 0, AdminSpacing.lg, 96),
        itemCount: _items.length + (more ? 1 : 0),
        separatorBuilder: (_, __) => const SizedBox(height: AdminSpacing.sm),
        itemBuilder: (context, index) {
          if (index == _items.length) {
            return Padding(
              padding: const EdgeInsets.symmetric(vertical: AdminSpacing.sm),
              child: Center(
                child: TextButton.icon(
                  onPressed: _loading
                      ? null
                      : hapticize(
                          () => _fetch(_controller.text.trim(), _page.page + 1)),
                  icon: _loading
                      ? const SizedBox(
                          width: 16,
                          height: 16,
                          child: CircularProgressIndicator(
                              strokeWidth: 2, color: AdminColors.primary))
                      : const Icon(Icons.expand_more, size: 18),
                  label: Text(_loading
                      ? 'Loading…'
                      : 'Show more '
                          '(${_items.length} of ${_page.totalElements})'),
                  style: TextButton.styleFrom(foregroundColor: AdminColors.primary),
                ),
              ),
            );
          }
          return _ItemCard(
            item: _items[index],
            onTap: () => _edit(_items[index]),
          );
        },
      ),
    );
  }

  /// Editing opens the VARIANT form, not the product form, because what a
  /// merchant changes about a Visit-to-Buy item or a service is its price,
  /// how that price is stated, whether it is available and how long it takes -
  /// all of which live on the variant. The product's name and category are
  /// edited from Products, where they already were.
  ///
  /// The variant is rebuilt from the row rather than re-fetched: the list
  /// already carries everything the dialog opens with, and a second request
  /// per tap is the round trip this screen exists to avoid.
  Future<void> _edit(CatalogueItem item) async {
    final changed = await showDialog<bool>(
      context: context,
      builder: (_) => AdminVariantFormDialog(
        productId: item.productId,
        categoryName: item.categoryName,
        variant: ProductVariant(
          id: item.productVariantId,
          available: item.available,
          unit: item.variantLabel,
          imageUrl: item.imageUrl,
          mrp: item.mrp,
          sellingPrice: item.sellingPrice,
        ),
      ),
    );
    if (changed == true && mounted) {
      _load(_controller.text.trim());
    }
  }
}

class _ItemCard extends StatelessWidget {
  const _ItemCard({required this.item, required this.onTap});

  final CatalogueItem item;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return InkWell(
      onTap: hapticize(onTap),
      borderRadius: AdminRadius.card,
      child: AdminSectionCard(
        padding: const EdgeInsets.all(AdminSpacing.md),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            ClipRRect(
              borderRadius: BorderRadius.circular(8),
              child: SizedBox(
                width: 52,
                height: 52,
                child: GpNetworkImage(url: item.imageUrl, renderWidth: 52),
              ),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(item.name,
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(
                          fontWeight: FontWeight.w700, fontSize: 13.5)),
                  if (item.categoryName != null)
                    Text(item.categoryName!,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: const TextStyle(
                            fontSize: 11.5, color: AdminColors.textSecondary)),
                  const SizedBox(height: 6),
                  Wrap(spacing: 6, runSpacing: 4, children: [
                    _Chip(item.priceLabel(), strong: true),
                    if (item.durationLabel != null) _Chip(item.durationLabel!),
                    if (item.availabilityLabel != null) _Chip(item.availabilityLabel!),
                    if (!item.active) const _Chip('Hidden'),
                  ]),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _Chip extends StatelessWidget {
  const _Chip(this.text, {this.strong = false});

  final String text;
  final bool strong;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
      decoration: BoxDecoration(
        color: strong
            ? AdminColors.primary.withValues(alpha: 0.10)
            : AdminColors.background,
        borderRadius: BorderRadius.circular(20),
      ),
      child: Text(text,
          style: TextStyle(
              fontSize: 11.5,
              fontWeight: strong ? FontWeight.w700 : FontWeight.w500,
              color: strong ? AdminColors.primary : AdminColors.textSecondary)),
    );
  }
}

/// The empty state EXPLAINS THE MODE, because a merchant arriving here for the
/// first time has never used it and "Nothing here yet" teaches them nothing.
class _Empty extends StatelessWidget {
  const _Empty({required this.mode, required this.searching});

  final SellingMode mode;
  final bool searching;

  @override
  Widget build(BuildContext context) {
    if (searching) {
      return const Center(
        child: Padding(
          padding: EdgeInsets.all(28),
          child: Text('Nothing matched that search.',
              style: TextStyle(fontSize: 13, color: AdminColors.textSecondary)),
        ),
      );
    }
    final service = mode == SellingMode.serviceAtShop;
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(28),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(service ? Icons.handyman_outlined : Icons.storefront_outlined,
                size: 42, color: AdminColors.textSecondary),
            const SizedBox(height: 12),
            Text(service ? 'No services yet' : 'Nothing listed to visit for yet',
                style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 15)),
            const SizedBox(height: 8),
            Text(
              service
                  ? 'Add the jobs you do at the shop - a haircut, a car wash, a '
                      'repair. Customers find them in GP-STORE, see your price and '
                      'how long it takes, and come to you.'
                  : 'Add things customers should see online but buy in person - '
                      'gold, furniture, a phone they want to hold first. They get '
                      'your price and directions; there is no cart and no online '
                      'payment.',
              textAlign: TextAlign.center,
              style: const TextStyle(
                  fontSize: 13, height: 1.4, color: AdminColors.textSecondary),
            ),
          ],
        ),
      ),
    );
  }
}
