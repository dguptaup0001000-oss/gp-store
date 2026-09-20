import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../admin/design/admin_components.dart';
import '../../../admin/design/admin_tokens.dart';
import '../../../core/search/search_debouncer.dart';
import '../../../core/util/haptic_widgets.dart';
import '../../auth/presentation/auth_providers.dart' show extractErrorMessage;
import '../domain/catalogue_item.dart';
import 'admin_providers.dart';

/// Choosing a category out of thousands, without scrolling through somebody
/// else's shop.
///
/// <h2>What this replaces</h2>
///
/// A dropdown of the entire catalogue in id order. On a kirana that was thirty
/// rows. On a marketplace it is several thousand, and a phone merchant adding
/// a handset scrolled past Atta, Baby Care, Beverages and Biscuits to reach
/// Mobile Phones. That is the difference between a merchant listing their
/// stock and giving up.
///
/// <h2>Two things make it usable</h2>
///
/// A search box at the top, debounced and server-side, and an ordering that
/// puts the categories this shop ALREADY sells in first - because a merchant's
/// next item is nearly always like the ones they already have. Both decisions
/// live on the server: the ordering needs to know the shop's shelf, and
/// computing it here would mean downloading the shelf to sort a dropdown.
///
/// Everything stays reachable. A business that expands - a phone shop that
/// starts selling chargers, then power banks - finds the new category by
/// typing it.
class CategoryPickerSheet extends ConsumerStatefulWidget {
  const CategoryPickerSheet({super.key, this.selectedId});

  final int? selectedId;

  /// Opens the picker and returns the chosen category, or null if dismissed.
  static Future<CategoryOption?> show(BuildContext context, {int? selectedId}) {
    return showModalBottomSheet<CategoryOption>(
      context: context,
      isScrollControlled: true,
      backgroundColor: AdminColors.surface,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(18)),
      ),
      builder: (_) => CategoryPickerSheet(selectedId: selectedId),
    );
  }

  @override
  ConsumerState<CategoryPickerSheet> createState() => _CategoryPickerSheetState();
}

class _CategoryPickerSheetState extends ConsumerState<CategoryPickerSheet> {
  final _controller = TextEditingController();
  final _debouncer = SearchDebouncer();

  /// A superseded answer must not overwrite a newer one.
  int _seq = 0;
  bool _loading = true;
  String? _error;
  List<CategoryOption> _results = const [];

  /// How far into the tree the merchant has walked, outermost first.
  ///
  /// EMPTY IS THE ROOT, which is also the search view. The trail exists so
  /// "back" goes up one level rather than closing the sheet, and so the
  /// merchant can see where they are - a list of children with no context is
  /// just another flat list.
  final List<CategoryOption> _trail = [];

  bool get _browsing => _trail.isNotEmpty;

  @override
  void initState() {
    super.initState();
    // Opens showing the shop's own categories first, so the common case
    // needs no typing at all.
    _load('');
  }

  @override
  void dispose() {
    _controller.dispose();
    _debouncer.dispose();
    super.dispose();
  }

  Future<void> _load(String query, [CancelToken? _]) async {
    final seq = ++_seq;
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final results = await ref
          .read(adminProductsRepositoryProvider)
          .searchCategories(query: query);
      if (!mounted || seq != _seq) return;
      setState(() {
        _results = results;
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

  /// Walk into a parent and list what is under it.
  ///
  /// The search box is cleared on the way in: a query and a position in the
  /// tree are two different ways of narrowing, and showing both at once
  /// leaves the merchant unable to tell which one produced the list.
  Future<void> _openChildren(CategoryOption parent) async {
    _controller.clear();
    _trail.add(parent);
    await _loadChildren(parent.id);
  }

  /// Back up one level - to the parent above, or out to search.
  Future<void> _up() async {
    if (_trail.isEmpty) return;
    _trail.removeLast();
    if (_trail.isEmpty) {
      await _load('');
    } else {
      await _loadChildren(_trail.last.id);
    }
  }

  Future<void> _loadChildren(int parentId) async {
    final seq = ++_seq;
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final results = await ref
          .read(adminProductsRepositoryProvider)
          .categoryChildren(parentId);
      if (!mounted || seq != _seq) return;
      setState(() {
        _results = results;
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

  @override
  Widget build(BuildContext context) {
    final mine = _results.where((c) => c.usedByThisShop).toList();
    final rest = _results.where((c) => !c.usedByThisShop).toList();

    return Padding(
      padding: EdgeInsets.only(bottom: MediaQuery.of(context).viewInsets.bottom),
      child: SizedBox(
        height: MediaQuery.of(context).size.height * 0.78,
        child: Column(
          children: [
            const SizedBox(height: 10),
            Container(
              width: 38,
              height: 4,
              decoration: BoxDecoration(
                color: AdminColors.border,
                borderRadius: BorderRadius.circular(4),
              ),
            ),
            Padding(
              padding: const EdgeInsets.fromLTRB(
                  AdminSpacing.lg, AdminSpacing.lg, AdminSpacing.lg, AdminSpacing.sm),
              child: TextField(
                controller: _controller,
                autofocus: false,
                textInputAction: TextInputAction.search,
                decoration: InputDecoration(
                  hintText: 'Search categories…',
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
                  // TYPING LEAVES THE TREE. A merchant three levels into
                  // Electronics who types "atta" means the whole catalogue,
                  // not "atta under Electronics" - which would find nothing
                  // and read as a broken search.
                  _trail.clear();
                  setState(() {}); // keeps the clear button in step
                  _debouncer.onQueryChanged(
                    value,
                    onSearch: (query, token) => _load(query, token),
                    onCleared: () => _load(''),
                  );
                },
              ),
            ),
            Expanded(child: _body(mine, rest)),
          ],
        ),
      ),
    );
  }

  Widget _body(List<CategoryOption> mine, List<CategoryOption> rest) {
    if (_loading) {
      return const Center(child: CircularProgressIndicator(color: AdminColors.primary));
    }
    if (_error != null) {
      return AdminErrorState(
        message: _error!,
        onRetry: hapticize(() => _load(_controller.text.trim())),
      );
    }
    if (_results.isEmpty) {
      return const _Empty();
    }

    if (_browsing) {
      final here = _trail.last;
      return ListView(
        padding: const EdgeInsets.fromLTRB(
            AdminSpacing.lg, 0, AdminSpacing.lg, AdminSpacing.xxl),
        children: [
          _Breadcrumb(trail: _trail, onUp: _up),
          // THE PARENT IS ITSELF A CHOICE. A shop that sells phones of every
          // kind wants "Mobile Phones", not one of its children, and without
          // this the only way to pick a category with children would be to
          // search for it.
          _UseThisOne(
            category: here,
            selected: widget.selectedId == here.id,
            onTap: () => Navigator.of(context).pop(here),
          ),
          const SizedBox(height: AdminSpacing.md),
          _Heading('Inside ${here.name}'),
          for (final category in _results)
            _Row(
              category: category,
              selectedId: widget.selectedId,
              onDrillDown: category.hasChildren ? () => _openChildren(category) : null,
            ),
        ],
      );
    }

    return ListView(
      padding: const EdgeInsets.fromLTRB(
          AdminSpacing.lg, 0, AdminSpacing.lg, AdminSpacing.xxl),
      children: [
        // YOUR CATEGORIES FIRST, and labelled, so a merchant understands why
        // these are at the top rather than wondering what the order means.
        if (mine.isNotEmpty) ...[
          const _Heading('Your categories'),
          for (final category in mine)
            _Row(
              category: category,
              selectedId: widget.selectedId,
              onDrillDown: category.hasChildren ? () => _openChildren(category) : null,
            ),
          const SizedBox(height: AdminSpacing.md),
        ],
        if (rest.isNotEmpty) ...[
          _Heading(mine.isEmpty ? 'Categories' : 'All categories'),
          for (final category in rest)
            _Row(
              category: category,
              selectedId: widget.selectedId,
              onDrillDown: category.hasChildren ? () => _openChildren(category) : null,
            ),
        ],
      ],
    );
  }
}

/// Where the merchant is in the tree, and the way back out.
class _Breadcrumb extends StatelessWidget {
  const _Breadcrumb({required this.trail, required this.onUp});

  final List<CategoryOption> trail;
  final VoidCallback onUp;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(top: 4, bottom: 8),
      child: Row(children: [
        IconButton(
          onPressed: hapticize(onUp),
          icon: const Icon(Icons.arrow_back, size: 18),
          visualDensity: VisualDensity.compact,
          padding: EdgeInsets.zero,
          constraints: const BoxConstraints(minWidth: 32, minHeight: 32),
          tooltip: 'Back',
        ),
        const SizedBox(width: 4),
        Expanded(
          child: Text(
            ['All categories', ...trail.map((c) => c.name)].join('  ›  '),
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: const TextStyle(
                fontSize: 12, color: AdminColors.textSecondary),
          ),
        ),
      ]),
    );
  }
}

/// "Use Electronics itself", offered at the top of its own children.
class _UseThisOne extends StatelessWidget {
  const _UseThisOne({
    required this.category,
    required this.selected,
    required this.onTap,
  });

  final CategoryOption category;
  final bool selected;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return InkWell(
      onTap: hapticize(onTap),
      borderRadius: BorderRadius.circular(8),
      child: Container(
        padding: const EdgeInsets.symmetric(vertical: 10, horizontal: 10),
        decoration: BoxDecoration(
          color: AdminColors.primaryFaint,
          borderRadius: BorderRadius.circular(8),
          border: Border.all(color: AdminColors.primaryLight),
        ),
        child: Row(children: [
          Expanded(
            child: Text('Use ${category.name} itself',
                style: TextStyle(
                    fontSize: 13.5,
                    fontWeight: selected ? FontWeight.w700 : FontWeight.w600,
                    color: AdminColors.primaryDeep)),
          ),
          if (selected)
            const Icon(Icons.check_circle, size: 18, color: AdminColors.primary),
        ]),
      ),
    );
  }
}

class _Heading extends StatelessWidget {
  const _Heading(this.text);

  final String text;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(top: 8, bottom: 4),
      child: Text(text.toUpperCase(),
          style: const TextStyle(
            fontSize: 10.5,
            fontWeight: FontWeight.w700,
            letterSpacing: 0.6,
            color: AdminColors.textSecondary,
          )),
    );
  }
}

class _Row extends StatelessWidget {
  const _Row({
    required this.category,
    required this.selectedId,
    this.onDrillDown,
  });

  final CategoryOption category;
  final int? selectedId;

  /// Non-null when this category has children worth walking into.
  ///
  /// THE COUNT USED TO BE DECORATION. It said "12" beside Electronics and
  /// tapping the row picked Electronics, so the twelve were unreachable
  /// unless the merchant already knew their names to type. The count is now
  /// a button, and the row keeps selecting - the two actions are separated
  /// rather than one of them guessed from the other.
  final VoidCallback? onDrillDown;

  @override
  Widget build(BuildContext context) {
    final selected = selectedId == category.id;
    return InkWell(
      onTap: hapticize(() => Navigator.of(context).pop(category)),
      borderRadius: BorderRadius.circular(8),
      child: Padding(
        padding: const EdgeInsets.symmetric(vertical: 10, horizontal: 4),
        child: Row(
          children: [
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(category.name,
                      style: TextStyle(
                          fontSize: 14,
                          fontWeight: selected ? FontWeight.w700 : FontWeight.w500,
                          color: selected ? AdminColors.primary : null)),
                  // THE PARENT, when there is one. "Mobile Phones" alone is
                  // ambiguous in a catalogue that may also have "Mobile
                  // Phones" under Repair; "Electronics" underneath resolves it.
                  if (category.parentName != null)
                    Text(category.parentName!,
                        style: const TextStyle(
                            fontSize: 11.5, color: AdminColors.textSecondary)),
                ],
              ),
            ),
            if (onDrillDown != null)
              InkWell(
                onTap: hapticize(onDrillDown!),
                borderRadius: BorderRadius.circular(20),
                child: Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 6),
                  child: Row(mainAxisSize: MainAxisSize.min, children: [
                    Text('${category.childCount}',
                        style: const TextStyle(
                            fontSize: 11.5,
                            fontWeight: FontWeight.w600,
                            color: AdminColors.primary)),
                    const Icon(Icons.chevron_right,
                        size: 18, color: AdminColors.primary),
                  ]),
                ),
              ),
            if (selected)
              const Icon(Icons.check_circle, size: 18, color: AdminColors.primary),
          ],
        ),
      ),
    );
  }
}

class _Empty extends StatelessWidget {
  const _Empty();

  @override
  Widget build(BuildContext context) {
    return const Center(
      child: Padding(
        padding: EdgeInsets.all(28),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(Icons.search_off, size: 38, color: AdminColors.textSecondary),
            SizedBox(height: 10),
            Text('No category matched',
                style: TextStyle(fontWeight: FontWeight.w700, fontSize: 14)),
            SizedBox(height: 4),
            Text('Try a shorter word, or a more general one.',
                textAlign: TextAlign.center,
                style: TextStyle(fontSize: 12.5, color: AdminColors.textSecondary)),
          ],
        ),
      ),
    );
  }
}
