import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../admin/design/admin_components.dart';
import '../../../admin/design/admin_tokens.dart';
import '../../auth/presentation/auth_providers.dart';
import '../../products/domain/product_models.dart';
import '../domain/shop_category.dart';
import 'admin_providers.dart';
import '../../../core/util/haptic_widgets.dart';

class AdminCategoryListScreen extends ConsumerWidget {
  const AdminCategoryListScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final derivedAsync = ref.watch(adminMyCategoriesProvider);
    final ownAsync = ref.watch(adminShopCategoriesProvider);

    return Scaffold(
      appBar: AppBar(title: const Text('Categories')),
      body: RefreshIndicator(
        onRefresh: () async {
          ref.invalidate(adminMyCategoriesProvider);
          ref.invalidate(adminShopCategoriesProvider);
        },
        child: derivedAsync.when(
          loading: () => const AdminListSkeleton(),
          error: (error, stackTrace) => AdminErrorState(
            message: "Couldn't load categories: ${extractErrorMessage(error)}",
            onRetry: hapticize(() {
              ref.invalidate(adminMyCategoriesProvider);
              ref.invalidate(adminShopCategoriesProvider);
            }),
          ),
          data: (derived) {
            final own = ownAsync.asData?.value ?? const <ShopCategory>[];

            if (derived.isEmpty && own.isEmpty) {
              return ListView(
                padding: const EdgeInsets.all(16),
                children: const [
                  SizedBox(height: 80),
                  AdminEmptyState(
                    icon: Icons.category_outlined,
                    title: 'No departments yet',
                    message: 'Add a department of your own with the button '
                        'below, or add a product and pick its category.',
                  ),
                ],
              );
            }

            return ListView(
              padding: const EdgeInsets.all(16),
              children: [
                // THE SHOP'S OWN, FIRST, because these are the ones the
                // merchant made and the only ones they can rename here.
                if (own.isNotEmpty) ...[
                  const _SectionHeading(
                    title: 'Your departments',
                    subtitle: 'You created these. Only this shop sees them.',
                  ),
                  for (final category in own) ...[
                    _ShopCategoryTile(category: category),
                    const SizedBox(height: 8),
                  ],
                  const SizedBox(height: 16),
                ],

                // DERIVED FROM THE SHELF, and read-only on purpose: these rows
                // belong to the platform taxonomy every merchant shares, so
                // renaming one here would rename it for every other shop.
                if (derived.isNotEmpty) ...[
                  const _SectionHeading(
                    title: 'From your products',
                    subtitle: 'Shared marketplace departments your products '
                        'are in. Managed by GP-STORE.',
                  ),
                  for (final category in derived) ...[
                    _CategoryTile(category: category),
                    const SizedBox(height: 8),
                  ],
                ],
              ],
            );
          },
        ),
      ),
      floatingActionButton: FloatingActionButton.extended(
        onPressed: hapticize(() async {
          final saved = await showDialog<bool>(
            context: context,
            builder: (context) => const _CategoryFormDialog(),
          );
          if (saved == true) ref.invalidate(adminShopCategoriesProvider);
        }),
        icon: const Icon(Icons.add),
        label: const Text('Add Category'),
      ),
    );
  }
}

class _SectionHeading extends StatelessWidget {
  const _SectionHeading({required this.title, required this.subtitle});

  final String title;
  final String subtitle;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 8),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(title,
              style: Theme.of(context)
                  .textTheme
                  .titleSmall
                  ?.copyWith(fontWeight: FontWeight.w700)),
          Text(subtitle,
              style: Theme.of(context)
                  .textTheme
                  .bodySmall
                  ?.copyWith(color: AdminColors.textSecondary)),
        ],
      ),
    );
  }
}

/// One of THIS shop's own departments - tappable, because it is the merchant's
/// to rename or retire.
class _ShopCategoryTile extends ConsumerWidget {
  const _ShopCategoryTile({required this.category});

  final ShopCategory category;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return InkWell(
      borderRadius: BorderRadius.circular(12),
      onTap: hapticize(() async {
        final saved = await showDialog<bool>(
          context: context,
          builder: (context) => _CategoryFormDialog(shopCategory: category),
        );
        if (saved == true) ref.invalidate(adminShopCategoriesProvider);
      }),
      child: Container(
        padding: const EdgeInsets.all(14),
        decoration: BoxDecoration(
          color: AdminColors.surface,
          borderRadius: AdminRadius.card,
          border: Border.all(color: AdminColors.border),
          boxShadow: AdminShadows.card,
        ),
        child: Row(
          children: [
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
                      Flexible(
                        child: Text(category.name,
                            style: const TextStyle(fontWeight: FontWeight.w700)),
                      ),
                      if (!category.active) ...[
                        const SizedBox(width: 8),
                        const Text('Inactive',
                            style: TextStyle(
                                color: AdminColors.danger,
                                fontSize: 11,
                                fontWeight: FontWeight.w600)),
                      ],
                    ],
                  ),
                  if (category.description != null)
                    Text(category.description!,
                        style: Theme.of(context).textTheme.bodyMedium),
                ],
              ),
            ),
            const Icon(Icons.chevron_right, color: AdminColors.textSecondary),
          ],
        ),
      ),
    );
  }
}

class _CategoryTile extends ConsumerWidget {
  const _CategoryTile({required this.category});

  final Category category;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    // READ-ONLY, AND THAT IS THE ARCHITECTURE. This row belongs to the shared
    // marketplace taxonomy; renaming it here would rename it for every other
    // merchant selling in it. The screen used to open an edit dialog that
    // always answered 403 - a control that cannot succeed is worse than no
    // control, because the merchant cannot tell a bug from a rule.
    return Semantics(
      readOnly: true,
      child: Container(
        padding: const EdgeInsets.all(14),
        decoration: BoxDecoration(
        color: AdminColors.surface,
        borderRadius: AdminRadius.card,
        border: Border.all(color: AdminColors.border),
        boxShadow: AdminShadows.card,
      ),
        child: Row(
          children: [
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
                      Text(category.name, style: const TextStyle(fontWeight: FontWeight.w700)),
                      if (!category.active) ...[
                        const SizedBox(width: 8),
                        const Text('Inactive', style: TextStyle(color: AdminColors.danger, fontSize: 11, fontWeight: FontWeight.w600)),
                      ],
                    ],
                  ),
                  if (category.description != null)
                    Text(category.description!, style: Theme.of(context).textTheme.bodyMedium),
                  if (category.gstRate != null)
                    Text('GST: ${category.gstRate}%', style: Theme.of(context).textTheme.bodyMedium?.copyWith(fontSize: 11)),
                ],
              ),
            ),
            const Icon(Icons.lock_outline,
                size: 16, color: AdminColors.textSecondary),
          ],
        ),
      ),
    );
  }
}

class _CategoryFormDialog extends ConsumerStatefulWidget {
  const _CategoryFormDialog({this.shopCategory});

  /// Null means "add a new department for this shop" - non-null means editing
  /// one this shop already owns. The platform taxonomy is never edited here.
  final ShopCategory? shopCategory;

  @override
  ConsumerState<_CategoryFormDialog> createState() => _CategoryFormDialogState();
}

class _CategoryFormDialogState extends ConsumerState<_CategoryFormDialog> {
  late final TextEditingController _nameController;
  late final TextEditingController _descriptionController;
  late bool _active;
  bool _isSaving = false;

  bool get _isEditing => widget.shopCategory != null;

  @override
  void initState() {
    super.initState();
    final c = widget.shopCategory;
    _nameController = TextEditingController(text: c?.name ?? '');
    _descriptionController = TextEditingController(text: c?.description ?? '');
    _active = c?.active ?? true;
  }

  @override
  void dispose() {
    _nameController.dispose();
    _descriptionController.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    if (_nameController.text.trim().isEmpty) return;

    setState(() => _isSaving = true);

    final description = _descriptionController.text.trim().isEmpty
        ? null
        : _descriptionController.text.trim();

    try {
      final repository = ref.read(adminProductsRepositoryProvider);

      if (_isEditing) {
        await repository.renameShopCategory(
          shopCategoryId: widget.shopCategory!.id,
          name: _nameController.text.trim(),
          description: description,
          active: _active,
        );
      } else {
        // THIS SHOP'S OWN DEPARTMENT, not the marketplace taxonomy. The button
        // used to post to /api/categories, which needs CATALOG_DEFINE once a
        // second merchant is trading, so on a real phone it always answered
        // "You don't have permission to do that".
        await repository.createCategory(
          name: _nameController.text.trim(),
          description: description,
        );
      }

      if (!mounted) return;
      Navigator.of(context).pop(true);
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text(extractErrorMessage(e))),
      );
    } finally {
      if (mounted) setState(() => _isSaving = false);
    }
  }

  Future<void> _deactivate() async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Remove this department?'),
        content: const Text(
            'Products filed under it keep their marketplace category - they '
            'just stop being grouped here.'),
        actions: [
          TextButton(
              onPressed: hapticize(() => Navigator.of(context).pop(false)),
              child: const Text('Cancel')),
          TextButton(
              onPressed: hapticize(() => Navigator.of(context).pop(true)),
              child: const Text('Remove')),
        ],
      ),
    );
    if (confirmed != true) return;

    try {
      await ref
          .read(adminProductsRepositoryProvider)
          .removeShopCategory(widget.shopCategory!.id);
      if (!mounted) return;
      Navigator.of(context).pop(true);
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text(extractErrorMessage(e))),
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: Text(_isEditing ? 'Edit department' : 'Add department'),
      content: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            // SAYS WHOSE IT IS. A merchant typing "charger" is organising
            // their own shelf, not adding a department to GP-STORE for every
            // other shop - and the screen should not leave that ambiguous.
            Text(
              'Only this shop sees this department. Marketplace categories are '
              'managed by GP-STORE.',
              style: Theme.of(context).textTheme.bodySmall,
            ),
            const SizedBox(height: 12),
            TextField(
                controller: _nameController,
                decoration:
                    const InputDecoration(labelText: 'Department name')),
            const SizedBox(height: 12),
            TextField(
              controller: _descriptionController,
              decoration: const InputDecoration(labelText: 'Description (optional)'),
            ),
            if (_isEditing) ...[
              const SizedBox(height: 8),
              SwitchListTile(
                contentPadding: EdgeInsets.zero,
                title: const Text('Active'),
                value: _active,
                onChanged: hapticizeValue((value) => setState(() => _active = value)),
              ),
            ],
          ],
        ),
      ),
      actions: [
        if (_isEditing)
          TextButton(
            onPressed: hapticize(_deactivate),
            style: TextButton.styleFrom(foregroundColor: Colors.red),
            child: const Text('Remove'),
          ),
        TextButton(onPressed: hapticize(() => Navigator.of(context).pop(false)), child: const Text('Cancel')),
        FilledButton(
          onPressed: _isSaving ? null : _save,
          child: _isSaving
              ? const SizedBox(height: 16, width: 16, child: CircularProgressIndicator(strokeWidth: 2))
              : const Text('Save'),
        ),
      ],
    );
  }
}
