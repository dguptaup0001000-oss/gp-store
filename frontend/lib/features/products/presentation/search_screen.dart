import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/theme/app_theme.dart';
import '../../../shared/widgets/cart_summary_bar.dart';
import '../../../shared/widgets/product_card.dart';
import '../../auth/presentation/auth_providers.dart';
import '../../cart/presentation/cart_providers.dart';
import '../../wishlist/presentation/wishlist_providers.dart';
import '../domain/product_models.dart';
import 'product_detail_screen.dart';
import 'products_providers.dart';
import 'recent_searches.dart';
import '../../../shared/widgets/scroll_to_top.dart';

class SearchScreen extends ConsumerStatefulWidget {
  const SearchScreen({super.key});

  @override
  ConsumerState<SearchScreen> createState() => _SearchScreenState();
}

class _SearchScreenState extends ConsumerState<SearchScreen> {
  final _controller = TextEditingController();
  Timer? _debounce;

  List<Product> _results = [];
  bool _isLoading = false;
  String? _errorMessage;
  bool _hasSearched = false;

  /// What Smart Search understood. Non-null only when it differs from what
  /// the customer typed - see ProductsRepository.searchSmart for why the two
  /// are separate rather than one "correction" field.
  String? _interpretedAs;
  String? _didYouMean;

  final _recent = RecentSearches();
  List<String> _recentTerms = const [];

  // Debouncing alone only limits how often a request FIRES, not the order
  // responses come back in - a slow/flaky connection can still let an older
  // search's response arrive after a newer one's and stomp its results.
  // Each _search() call claims the next number and only applies its result
  // if it's still the latest call by the time the response arrives.
  int _searchSeq = 0;

  @override
  void initState() {
    super.initState();
    _loadRecent();
  }

  Future<void> _loadRecent() async {
    final terms = await _recent.load();
    if (!mounted) return;
    setState(() => _recentTerms = terms);
  }

  @override
  void dispose() {
    _debounce?.cancel();
    _controller.dispose();
    super.dispose();
  }

  void _onQueryChanged(String query) {
    _debounce?.cancel();

    if (query.trim().isEmpty) {
      setState(() {
        _results = [];
        _hasSearched = false;
        _errorMessage = null;
        _interpretedAs = null;
        _didYouMean = null;
      });
      return;
    }

    // Debounced - avoids firing a real backend search request on every single
    // keystroke, which would be wasteful and could visibly lag on a slow
    // connection (village/tier-3 network conditions matter here).
    _debounce = Timer(const Duration(milliseconds: 400), () => _search(query.trim()));
  }

  Future<void> _search(String query) async {
    final seq = ++_searchSeq;

    setState(() {
      _isLoading = true;
      _errorMessage = null;
      _hasSearched = true;
    });

    try {
      final result = await ref.read(productsRepositoryProvider).searchSmart(query);
      if (!mounted || seq != _searchSeq) return;
      setState(() {
        _results = result.products;
        _interpretedAs = result.interpretedAs;
        _didYouMean = result.didYouMean;
        _isLoading = false;
      });

      // Only remember searches that actually found something. A typo the
      // customer immediately retyped is not a search they want offered back
      // to them next time.
      if (result.products.isNotEmpty) {
        final terms = await _recent.remember(query);
        if (mounted) setState(() => _recentTerms = terms);
      }
    } catch (e) {
      if (!mounted || seq != _searchSeq) return;
      setState(() {
        _errorMessage = extractErrorMessage(e);
        _isLoading = false;
      });
    }
  }

  /// Runs a term the customer chose - a recent search, or a "did you mean"
  /// suggestion. Sets the field too, so what is in the box always matches
  /// what is on screen.
  void _runTerm(String term) {
    _debounce?.cancel();
    _controller.text = term;
    _controller.selection = TextSelection.collapsed(offset: term.length);
    _search(term);
  }

  @override
  Widget build(BuildContext context) {
    // Watching (not just reading .notifier) so this screen rebuilds when a
    // heart is toggled - same reasoning as HorizontalProductSection.
    ref.watch(wishlistControllerProvider);

    return Scaffold(
      appBar: AppBar(
        title: TextField(
          controller: _controller,
          autofocus: true,
          onChanged: _onQueryChanged,
          decoration: const InputDecoration(
            hintText: 'Search for atta, milk, snacks...',
            border: InputBorder.none,
          ),
        ),
      ),
      body: _buildBody(),
      bottomNavigationBar: const CartSummaryBar(),
    );
  }

  Widget _buildBody() {
    if (!_hasSearched) {
      return _RecentSearches(
        terms: _recentTerms,
        onTap: _runTerm,
        onClear: () async {
          await _recent.clear();
          if (mounted) setState(() => _recentTerms = const []);
        },
      );
    }

    if (_isLoading) {
      return const Center(child: CircularProgressIndicator(strokeWidth: 2));
    }

    if (_errorMessage != null) {
      return Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(_errorMessage!),
            TextButton(
              onPressed: () => _search(_controller.text.trim()),
              child: const Text('Retry'),
            ),
          ],
        ),
      );
    }

    if (_results.isEmpty) {
      return _NoResults(
        query: _controller.text.trim(),
        recentTerms: _recentTerms,
        onTap: _runTerm,
      );
    }

    return Column(
      children: [
        // What Smart Search understood, above the results it produced.
        _SearchInterpretation(
          query: _controller.text.trim(),
          interpretedAs: _interpretedAs,
          didYouMean: _didYouMean,
          onUseSuggestion: _runTerm,
        ),
        Expanded(child: _buildGrid()),
      ],
    );
  }

  Widget _buildGrid() {
    return ScrollToTop(
      builder: (context, scrollController) => GridView.builder(
      controller: scrollController,
      padding: const EdgeInsets.all(16),
      gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(
        crossAxisCount: 2,
        mainAxisSpacing: 12,
        crossAxisSpacing: 12,
        childAspectRatio: ProductGrid.aspectRatio(context),
      ),
      itemCount: _results.length,
      itemBuilder: (context, index) {
        final product = _results[index];
        final wishlistController = ref.read(wishlistControllerProvider.notifier);
        return ProductCard(
          product: product,
          onTap: () => Navigator.of(context).push(
            MaterialPageRoute(builder: (_) => ProductDetailScreen(product: product)),
          ),
          onAddPressed: () => _addToCart(product),
          isWishlisted: wishlistController.isWishlisted(product.id),
          onWishlistToggle: () => wishlistController.toggle(product.id),
        );
      },
      ),
    );
  }

  Future<void> _addToCart(Product product) async {
    final variant = product.primaryVariant;
    if (variant == null) return;

    try {
      await ref.read(cartControllerProvider.notifier).addToCart(variantId: variant.id, quantity: 1);
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('${product.name} added to cart')));
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text(extractErrorMessage(e))),
      );
    }
  }
}


/// Says what Smart Search understood, above the results.
///
/// Two different messages for two different confidence levels, because
/// conflating them is how a search starts feeling like it is arguing with the
/// customer:
///
///   - interpretedAs: the search WAS run on this. State it as fact, and offer
///     a way back to the literal words.
///   - didYouMean: the search was NOT changed. Offer it, do not apply it.
///
/// Both null - an ordinary search that worked as typed - renders nothing at
/// all. Silence is the correct output for a query that was already right.
class _SearchInterpretation extends StatelessWidget {
  const _SearchInterpretation({
    required this.query,
    required this.interpretedAs,
    required this.didYouMean,
    required this.onUseSuggestion,
  });

  final String query;
  final String? interpretedAs;
  final String? didYouMean;
  final ValueChanged<String> onUseSuggestion;

  @override
  Widget build(BuildContext context) {
    final corrected = interpretedAs;
    final suggestion = didYouMean;

    if (corrected == null && suggestion == null) return const SizedBox.shrink();

    return Container(
      width: double.infinity,
      padding: const EdgeInsets.fromLTRB(16, 10, 16, 12),
      decoration: const BoxDecoration(
        color: AppColors.ivory,
        border: Border(bottom: BorderSide(color: AppColors.divider)),
      ),
      child: corrected != null
          ? Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisSize: MainAxisSize.min,
              children: [
                RichText(
                  text: TextSpan(
                    style: const TextStyle(fontSize: 14, color: AppColors.textPrimary),
                    children: [
                      const TextSpan(text: 'Showing results for '),
                      TextSpan(
                        text: corrected,
                        style: const TextStyle(fontWeight: FontWeight.w800, color: AppColors.primary),
                      ),
                    ],
                  ),
                ),
                const SizedBox(height: 2),
                // The way back. A customer who really did mean the odd
                // spelling must not be stuck with the correction.
                GestureDetector(
                  onTap: () => onUseSuggestion(query),
                  child: Text(
                    'Search instead for "$query"',
                    style: const TextStyle(
                      fontSize: 12.5,
                      color: AppColors.textSecondary,
                      decoration: TextDecoration.underline,
                    ),
                  ),
                ),
              ],
            )
          : GestureDetector(
              onTap: () => onUseSuggestion(suggestion!),
              child: RichText(
                text: TextSpan(
                  style: const TextStyle(fontSize: 14, color: AppColors.textPrimary),
                  children: [
                    const TextSpan(text: 'Did you mean '),
                    TextSpan(
                      text: suggestion,
                      style: const TextStyle(fontWeight: FontWeight.w800, color: AppColors.primary),
                    ),
                    const TextSpan(text: '?'),
                  ],
                ),
              ),
            ),
    );
  }
}

/// The screen before anything has been typed.
class _RecentSearches extends StatelessWidget {
  const _RecentSearches({required this.terms, required this.onTap, required this.onClear});

  final List<String> terms;
  final ValueChanged<String> onTap;
  final VoidCallback onClear;

  @override
  Widget build(BuildContext context) {
    if (terms.isEmpty) {
      return const Center(
        child: Text('Search for products', style: TextStyle(color: AppColors.textSecondary)),
      );
    }

    return ListView(
      padding: const EdgeInsets.fromLTRB(16, 16, 16, 16),
      children: [
        Row(
          mainAxisAlignment: MainAxisAlignment.spaceBetween,
          children: [
            const Text('Recent searches', style: TextStyle(fontWeight: FontWeight.w700)),
            GestureDetector(
              onTap: onClear,
              child: const Text(
                'Clear',
                style: TextStyle(fontSize: 13, color: AppColors.textSecondary),
              ),
            ),
          ],
        ),
        const SizedBox(height: 8),
        ...terms.map(
          (term) => ListTile(
            contentPadding: EdgeInsets.zero,
            dense: true,
            leading: const Icon(Icons.history, size: 20, color: AppColors.textSecondary),
            title: Text(term, style: const TextStyle(fontSize: 14)),
            trailing: const Icon(Icons.north_west, size: 16, color: AppColors.textSecondary),
            onTap: () => onTap(term),
          ),
        ),
      ],
    );
  }
}

/// Nothing matched.
///
/// Offers the customer's own previous searches rather than a shelf of
/// unrelated products - a list of things they are known to have wanted is
/// genuinely useful, whereas "here is some rice because you typed something
/// we did not understand" is noise.
class _NoResults extends StatelessWidget {
  const _NoResults({required this.query, required this.recentTerms, required this.onTap});

  final String query;
  final List<String> recentTerms;
  final ValueChanged<String> onTap;

  @override
  Widget build(BuildContext context) {
    return ListView(
      padding: const EdgeInsets.fromLTRB(16, 40, 16, 16),
      children: [
        const Icon(Icons.search_off, size: 40, color: AppColors.textSecondary),
        const SizedBox(height: 12),
        const Center(
          child: Text(
            'No products found',
            style: TextStyle(fontWeight: FontWeight.w700, fontSize: 16),
          ),
        ),
        const SizedBox(height: 4),
        Center(
          child: Text(
            'Nothing matched "$query". Check the spelling, or try a shorter word.',
            textAlign: TextAlign.center,
            style: const TextStyle(fontSize: 13, color: AppColors.textSecondary),
          ),
        ),
        if (recentTerms.isNotEmpty) ...[
          const SizedBox(height: 28),
          const Text('Try one of your recent searches', style: TextStyle(fontWeight: FontWeight.w700)),
          const SizedBox(height: 10),
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: recentTerms
                .map(
                  (term) => ActionChip(
                    label: Text(term),
                    onPressed: () => onTap(term),
                    backgroundColor: AppColors.cardBackground,
                    side: const BorderSide(color: AppColors.divider),
                  ),
                )
                .toList(),
          ),
        ],
      ],
    );
  }
}
