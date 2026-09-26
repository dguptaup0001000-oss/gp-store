import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/marketplace/marketplace_models.dart';
import '../../../core/marketplace/marketplace_providers.dart';
import '../../../core/theme/app_theme.dart';
import 'marketplace_feed_provider.dart';
import 'market_shop_card.dart';

/// Paginated See all view for the nearby shop preview on Customer Home.
class NearbyShopsScreen extends ConsumerStatefulWidget {
  const NearbyShopsScreen({
    super.key,
    required this.latitude,
    required this.longitude,
  });

  final double latitude;
  final double longitude;

  @override
  ConsumerState<NearbyShopsScreen> createState() => _NearbyShopsScreenState();
}

class _NearbyShopsScreenState extends ConsumerState<NearbyShopsScreen> {
  static const _pageSize = 20;
  final _scrollController = ScrollController();
  final List<Storefront> _shops = [];
  int _page = 0;
  int _total = 0;
  bool _hasNext = true;
  bool _loading = true;
  Object? _error;
  bool _inFlight = false;

  @override
  void initState() {
    super.initState();
    _scrollController.addListener(_onScroll);
    _loadNext();
  }

  @override
  void dispose() {
    _scrollController.removeListener(_onScroll);
    _scrollController.dispose();
    super.dispose();
  }

  void _onScroll() {
    if (_scrollController.hasClients &&
        _scrollController.position.extentAfter < 500) {
      _loadNext();
    }
  }

  Future<void> _loadNext({bool refresh = false}) async {
    if (_inFlight || (!refresh && !_hasNext)) return;
    _inFlight = true;
    if (mounted) {
      setState(() {
        _loading = true;
        _error = null;
        if (refresh) {
          _shops.clear();
          _page = 0;
          _hasNext = true;
        }
      });
    }
    try {
      final page = await ref.read(marketplaceRepositoryProvider).nearbyShopsPage(
            latitude: widget.latitude,
            longitude: widget.longitude,
            page: refresh ? 0 : _page,
            size: _pageSize,
          );
      if (!mounted) return;
      setState(() {
        final existing = _shops.map((shop) => shop.shopId).toSet();
        _shops.addAll(page.shops.where((shop) => existing.add(shop.shopId)));
        _page = page.page + 1;
        _total = page.totalElements;
        _hasNext = page.hasNext;
        _loading = false;
      });
    } catch (error) {
      if (mounted) {
        setState(() {
          _error = error;
          _loading = false;
        });
      }
    } finally {
      _inFlight = false;
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
        backgroundColor: AppColors.marketplaceGround,
        appBar: AppBar(
          backgroundColor: AppColors.marketplaceGround,
          title: const Text('All nearby shops'),
          actions: [
            TextButton(
              onPressed: () {
                ref.read(marketplaceShopFilterProvider.notifier).state = null;
                Navigator.of(context).pop();
              },
              child: const Text('All'),
            ),
          ],
        ),
        body: _shops.isEmpty && _loading
            ? const Center(child: CircularProgressIndicator(strokeWidth: 2))
            : _shops.isEmpty && _error != null
                ? Center(
                    child: TextButton.icon(
                      onPressed: () => _loadNext(refresh: true),
                      icon: const Icon(Icons.refresh_rounded),
                      label: const Text("Couldn't load nearby shops. Retry"),
                    ),
                  )
                : _shops.isEmpty
                    ? const Center(child: Text('No shops deliver to this address yet.'))
                    : RefreshIndicator(
                        onRefresh: () => _loadNext(refresh: true),
                        child: ListView.separated(
                          controller: _scrollController,
                          padding: const EdgeInsets.all(16),
                          itemCount: _shops.length + (_loading || _error != null || _hasNext ? 1 : 0),
                          separatorBuilder: (_, __) => const SizedBox(height: 12),
                          itemBuilder: (context, index) {
                            if (index < _shops.length) {
                              return SizedBox(
                                height: 104,
                                child: MarketShopCard(
                                  shop: _shops[index],
                                  nearest: index == 0,
                                  selected: _shops[index].shopId == ref.watch(marketplaceShopFilterProvider),
                                  onTap: () {
                                    ref.read(marketplaceShopFilterProvider.notifier).state =
                                        _shops[index].shopId;
                                    Navigator.of(context).pop();
                                  },
                                ),
                              );
                            }
                            if (_error != null) {
                              return Center(
                                child: TextButton.icon(
                                  onPressed: _loadNext,
                                  icon: const Icon(Icons.refresh_rounded),
                                  label: const Text('Retry'),
                                ),
                              );
                            }
                            if (_loading) {
                              return const Padding(
                                padding: EdgeInsets.all(16),
                                child: Center(child: CircularProgressIndicator(strokeWidth: 2)),
                              );
                            }
                            return Padding(
                              padding: const EdgeInsets.all(12),
                              child: Center(
                                child: Text('$_total nearby shops',
                                    style: const TextStyle(color: AppColors.textSecondary)),
                              ),
                            );
                          },
                        ),
                      ),
      );
}
