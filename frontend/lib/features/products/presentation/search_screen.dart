
import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/search/search_debouncer.dart';
import '../../../core/theme/app_theme.dart';
import '../../../core/util/app_haptics.dart';
import '../../../core/voice/voice_query_parser.dart';
import '../../../shared/widgets/cart_summary_bar.dart';
import '../../../shared/widgets/product_card.dart';
import '../../auth/presentation/auth_providers.dart';
import '../../cart/presentation/cart_providers.dart';
import '../../wishlist/presentation/wishlist_providers.dart';
import '../domain/product_models.dart';
import 'product_detail_screen.dart';
import 'products_providers.dart';
import 'recent_searches.dart';
import 'voice_search_sheet.dart';
import '../../../shared/widgets/scroll_to_top.dart';

class SearchScreen extends ConsumerStatefulWidget {
  const SearchScreen({super.key, this.openVoice = false});

  /// Opens straight into the listening sheet.
  ///
  /// Set by the microphone on the home screen's search pill, so tapping it
  /// there is one gesture rather than two - the alternative was landing on
  /// this screen with a keyboard up and having to find the microphone again.
  final bool openVoice;

  @override
  ConsumerState<SearchScreen> createState() => _SearchScreenState();
}

class _SearchScreenState extends ConsumerState<SearchScreen> {
  final _controller = TextEditingController();

  /// Shared with brand and category search - one rule about when a query
  /// reaches the network, in one place.
  final _debouncer = SearchDebouncer();

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

  /// Paging for the results list.
  ///
  /// A BUTTON, NOT INFINITE SCROLL, and that is deliberate. Search results
  /// are already the most request-heavy screen in the app - every keystroke
  /// past the debounce is a query - and hanging a scroll listener off them
  /// would fetch pages the customer never asked for. A Load more they tap
  /// costs exactly one request when they want one, and it does not have to
  /// interact with the stale-response guard on every scroll frame.
  String _lastQuery = '';
  int _page = 0;
  bool _hasMore = false;
  bool _isLoadingMore = false;

  /// What the customer said, and the extra shopping intents in it.
  ///
  /// Only ever set by voice. A typed search leaves both null and this screen
  /// behaves exactly as it did before voice existed.
  String? _spokenAs;
  List<VoiceIntent> _otherIntents = const [];

  @override
  void initState() {
    super.initState();
    _loadRecent();

    if (widget.openVoice) {
      // After the first frame: the sheet needs a mounted Navigator, and the
      // screen should be visibly on screen before the microphone opens over
      // it.
      WidgetsBinding.instance.addPostFrameCallback((_) => _startVoice());
    }
  }

  /// Listen, then search - the whole of the voice path from this screen's
  /// point of view.
  ///
  /// Everything downstream is the ORDINARY search. The transcript becomes a
  /// query string and goes through _runTerm like a tapped recent search, so
  /// voice results are the same products, ranked the same way, in the same
  /// cards, with the same paging. There is no second search system here and
  /// deliberately no way for one to grow.
  Future<void> _startVoice() async {
    final query = await VoiceSearchSheet.show(context);
    if (!mounted || query == null) return;

    final primary = query.primary;
    if (primary == null) return;

    setState(() {
      _spokenAs = query.transcript;
      // Everything after the first - "do kilo chini AUR ek litre tel" is two
      // shopping trips, and the second must not be silently dropped just
      // because only one can be on screen.
      _otherIntents = query.intents.skip(1).toList();
    });

    _runTerm(primary.searchPhrase);
  }

  /// Runs one of the other things the customer asked for in the same breath.
  void _runOtherIntent(VoiceIntent intent) {
    AppHaptics.selection();
    setState(() {
      _otherIntents = [
        for (final other in _otherIntents)
          if (other != intent) other,
      ];
    });
    _runTerm(intent.searchPhrase);
  }

  Future<void> _loadRecent() async {
    final terms = await _recent.load();
    if (!mounted) return;
    setState(() => _recentTerms = terms);
  }

  @override
  void dispose() {
    _debouncer.dispose();
    _controller.dispose();
    super.dispose();
  }

  void _onQueryChanged(String query) {
    _debouncer.onQueryChanged(
      query,
      onSearch: _search,
      onCleared: () => setState(() {
        _results = [];
        _hasSearched = false;
        // Typing is a new question - the previous utterance stops being the
        // explanation for what is on screen.
        _spokenAs = null;
        _otherIntents = const [];
        _errorMessage = null;
        _interpretedAs = null;
        _didYouMean = null;
        _hasMore = false;
        _page = 0;
        _lastQuery = '';
      }),
    );
  }

  Future<void> _search(String query, CancelToken cancelToken) async {
    final seq = ++_searchSeq;

    setState(() {
      _isLoading = true;
      _errorMessage = null;
      _hasSearched = true;
      // A new term is a new result set - never page 2 of the old one.
      _lastQuery = query;
      _page = 0;
      _hasMore = false;
      _isLoadingMore = false;
    });

    try {
      final result = await ref.read(productsRepositoryProvider).searchSmart(
            query,
            cancelToken: cancelToken,
          );
      if (!mounted || seq != _searchSeq) return;
      setState(() {
        _results = result.products;
        _interpretedAs = result.interpretedAs;
        _didYouMean = result.didYouMean;
        _hasMore = result.hasMore;
        _isLoading = false;
      });

      // Only remember searches that actually found something. A typo the
      // customer immediately retyped is not a search they want offered back
      // to them next time.
      if (result.products.isNotEmpty) {
        final terms = await _recent.remember(query);
        if (mounted) setState(() => _recentTerms = terms);
      }
    } on DioException catch (e) {
      // A cancelled request is this screen's own doing - the customer typed
      // another character. Showing an error for it would turn ordinary typing
      // into a screen full of failures.
      if (e.type == DioExceptionType.cancel) return;
      if (!mounted || seq != _searchSeq) return;
      setState(() {
        _errorMessage = extractErrorMessage(e);
        _isLoading = false;
      });
    } catch (e) {
      if (!mounted || seq != _searchSeq) return;
      setState(() {
        _errorMessage = extractErrorMessage(e);
        _isLoading = false;
      });
    }
  }

  /// Fetches the next page and APPENDS it.
  ///
  /// Guarded by the same sequence number as _search: if the customer types
  /// while this is in flight, the page that arrives belongs to a query they
  /// have already replaced, and appending it would splice results for one
  /// term into the list for another.
  Future<void> _loadMore() async {
    if (_isLoadingMore || !_hasMore || _lastQuery.isEmpty) return;

    final seq = _searchSeq;
    final nextPage = _page + 1;
    setState(() => _isLoadingMore = true);

    try {
      final result = await ref.read(productsRepositoryProvider).searchSmart(
            _lastQuery,
            page: nextPage,
          );
      if (!mounted || seq != _searchSeq) return;
      setState(() {
        _results = [..._results, ...result.products];
        _page = nextPage;
        _hasMore = result.hasMore;
        _isLoadingMore = false;
      });
    } catch (e) {
      if (!mounted || seq != _searchSeq) return;
      // Deliberately does NOT replace the results with an error screen: the
      // customer still has the matches they were reading, and losing those
      // because page 2 failed would be a worse outcome than the failure.
      setState(() => _isLoadingMore = false);
      if (mounted) {
        final message = extractErrorMessage(e);
        if (message.isNotEmpty) {
          ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(message)));
        }
      }
    }
  }

  /// Runs a term the customer chose - a recent search, or a "did you mean"
  /// suggestion. Sets the field too, so what is in the box always matches
  /// what is on screen.
  void _runTerm(String term) {
    _controller.text = term;
    _controller.selection = TextSelection.collapsed(offset: term.length);
    // Immediate: the customer chose this, so there is nothing to wait for.
    _debouncer.searchNow(term, onSearch: _search);
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
          // Not when arriving by voice: a keyboard springing up behind the
          // listening sheet is both wrong and, on a small phone, in the way.
          autofocus: !widget.openVoice,
          onChanged: _onQueryChanged,
          decoration: InputDecoration(
            hintText: 'Search for atta, dal, coke and more',
            border: InputBorder.none,
            // In the field itself rather than an AppBar action, so it reads
            // as part of the search box - and so its 48dp tap target sits
            // where a thumb already is.
            suffixIcon: IconButton(
              icon: const Icon(Icons.mic_none_rounded),
              color: AppColors.primary,
              tooltip: 'Search by voice',
              onPressed: () {
                AppHaptics.selection();
                _startVoice();
              },
            ),
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
              onPressed: () => _debouncer.searchNow(_controller.text, onSearch: _search),
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
          spokenAs: _spokenAs,
          onUseSuggestion: _runTerm,
        ),
        if (_otherIntents.isNotEmpty)
          _AlsoHeard(intents: _otherIntents, onTap: _runOtherIntent),
        Expanded(child: _buildGrid()),
        _buildLoadMore(),
      ],
    );
  }

  /// A footer rather than a widget at the end of the grid: adding it to the
  /// grid would mean rebuilding it as slivers, and a footer is reachable
  /// without scrolling to the bottom of forty results first.
  Widget _buildLoadMore() {
    if (!_hasMore) return const SizedBox.shrink();

    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 0, 16, 12),
      child: SizedBox(
        width: double.infinity,
        child: _isLoadingMore
            ? const Center(
                child: Padding(
                  padding: EdgeInsets.all(8),
                  child: SizedBox(
                    height: 20,
                    width: 20,
                    child: CircularProgressIndicator(strokeWidth: 2),
                  ),
                ),
              )
            : OutlinedButton(
                onPressed: _loadMore,
                child: const Text('Show more results'),
              ),
      ),
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
        // Nothing scroll-driven here: paging is the explicit button below.
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
    required this.spokenAs,
    required this.onUseSuggestion,
  });

  final String query;
  final String? interpretedAs;
  final String? didYouMean;

  /// The raw transcript, when this search came from the microphone.
  ///
  /// Shown because a customer cannot correct a mishearing they never see. If
  /// the recogniser turns "Aashirvaad" into "ashirbad", the results are
  /// baffling, and the transcript is the only thing on screen that explains
  /// them.
  final String? spokenAs;

  final ValueChanged<String> onUseSuggestion;

  @override
  Widget build(BuildContext context) {
    final corrected = interpretedAs;
    final suggestion = didYouMean;
    final heard = spokenAs;

    if (corrected == null && suggestion == null && heard == null) {
      return const SizedBox.shrink();
    }

    return Container(
      width: double.infinity,
      padding: const EdgeInsets.fromLTRB(16, 10, 16, 12),
      decoration: const BoxDecoration(
        color: AppColors.ivory,
        border: Border(bottom: BorderSide(color: AppColors.divider)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        mainAxisSize: MainAxisSize.min,
        children: [
          if (heard != null) ...[
            Row(
              children: [
                const Icon(Icons.mic_none_rounded, size: 14, color: AppColors.textSecondary),
                const SizedBox(width: 5),
                Expanded(
                  child: Text(
                    'You said "$heard"',
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(fontSize: 12.5, color: AppColors.textSecondary),
                  ),
                ),
              ],
            ),
            if (corrected != null || suggestion != null) const SizedBox(height: 6),
          ],
          _understanding(corrected, suggestion),
        ],
      ),
    );
  }

  /// What the SEARCH understood, as opposed to what the microphone heard.
  /// Unchanged from before voice existed - a typed search still renders
  /// exactly this and nothing above it.
  Widget _understanding(String? corrected, String? suggestion) {
    if (corrected == null && suggestion == null) return const SizedBox.shrink();

    return corrected != null
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
            );
  }
}

/// The other things the customer asked for in the same breath.
///
/// "do kilo chini aur ek litre tel" is two shopping trips and only one can be
/// on screen, so the rest become chips rather than being silently dropped.
///
/// CHIPS, NOT AN AUTOMATIC ADD-TO-CART. The brief allows adding them
/// automatically "if the existing cart architecture supports it safely", and
/// the honest answer is that a speech recogniser's second-best guess is not
/// evidence enough to put something in somebody's basket. A tap costs the
/// customer nothing and cannot be wrong.
class _AlsoHeard extends StatelessWidget {
  const _AlsoHeard({required this.intents, required this.onTap});

  final List<VoiceIntent> intents;
  final ValueChanged<VoiceIntent> onTap;

  @override
  Widget build(BuildContext context) {
    if (intents.isEmpty) return const SizedBox.shrink();

    return Container(
      width: double.infinity,
      padding: const EdgeInsets.fromLTRB(16, 8, 16, 10),
      decoration: const BoxDecoration(
        border: Border(bottom: BorderSide(color: AppColors.divider)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        mainAxisSize: MainAxisSize.min,
        children: [
          const Text(
            'You also asked for',
            style: TextStyle(fontSize: 12, fontWeight: FontWeight.w700, color: AppColors.textSecondary),
          ),
          const SizedBox(height: 6),
          Wrap(
            spacing: 8,
            runSpacing: 6,
            children: [
              for (final intent in intents)
                ActionChip(
                  label: Text(_label(intent)),
                  labelStyle: const TextStyle(fontSize: 12.5, fontWeight: FontWeight.w600),
                  backgroundColor: AppColors.tint(AppColors.primary),
                  side: BorderSide.none,
                  onPressed: () => onTap(intent),
                ),
            ],
          ),
        ],
      ),
    );
  }

  /// The quantity is shown but never searched for - see VoiceIntent.quantity.
  static String _label(VoiceIntent intent) {
    final count = intent.quantity;
    return count == null ? intent.searchPhrase : '$count x ${intent.searchPhrase}';
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
