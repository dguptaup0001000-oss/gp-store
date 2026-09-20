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

    return ListView(
      padding: const EdgeInsets.fromLTRB(
          AdminSpacing.lg, 0, AdminSpacing.lg, AdminSpacing.xxl),
      children: [
        // YOUR CATEGORIES FIRST, and labelled, so a merchant understands why
        // these are at the top rather than wondering what the order means.
        if (mine.isNotEmpty) ...[
          const _Heading('Your categories'),
          for (final category in mine) _Row(category: category, selectedId: widget.selectedId),
          const SizedBox(height: AdminSpacing.md),
        ],
        if (rest.isNotEmpty) ...[
          _Heading(mine.isEmpty ? 'Categories' : 'All categories'),
          for (final category in rest) _Row(category: category, selectedId: widget.selectedId),
        ],
      ],
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
  const _Row({required this.category, required this.selectedId});

  final CategoryOption category;
  final int? selectedId;

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
            if (category.hasChildren)
              Padding(
                padding: const EdgeInsets.only(right: 8),
                child: Text('${category.childCount}',
                    style: const TextStyle(
                        fontSize: 11, color: AdminColors.textSecondary)),
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
