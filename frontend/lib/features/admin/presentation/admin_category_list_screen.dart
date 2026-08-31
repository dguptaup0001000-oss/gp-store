import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../admin/design/admin_tokens.dart';
import '../../auth/presentation/auth_providers.dart';
import '../../products/domain/product_models.dart';
import 'admin_providers.dart';
import '../../../core/util/haptic_widgets.dart';

class AdminCategoryListScreen extends ConsumerWidget {
  const AdminCategoryListScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final categoriesAsync = ref.watch(adminCategoriesProvider);

    return Scaffold(
      appBar: AppBar(title: const Text('Categories')),
      body: categoriesAsync.when(
        loading: () => const Center(child: CircularProgressIndicator(strokeWidth: 2)),
        error: (error, stackTrace) => Center(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              // TEMPORARY, for active debugging - see RootScreen's identical
              // comment for why this shows the real failure reason instead
              // of one static string.
              Text("Couldn't load categories: ${extractErrorMessage(error)}"),
              TextButton(onPressed: hapticize(() => ref.invalidate(adminCategoriesProvider)), child: const Text('Retry')),
            ],
          ),
        ),
        data: (categories) {
          if (categories.isEmpty) {
            return const Center(
              child: Text('No categories yet - tap + to add one', style: TextStyle(color: AdminColors.textSecondary)),
            );
          }

          return ListView.separated(
            padding: const EdgeInsets.all(16),
            itemCount: categories.length,
            separatorBuilder: (_, __) => const SizedBox(height: 8),
            itemBuilder: (context, index) => _CategoryTile(category: categories[index]),
          );
        },
      ),
      floatingActionButton: FloatingActionButton.extended(
        onPressed: hapticize(() async {
          final saved = await showDialog<bool>(
            context: context,
            builder: (context) => const _CategoryFormDialog(),
          );
          if (saved == true) ref.invalidate(adminCategoriesProvider);
        }),
        icon: const Icon(Icons.add),
        label: const Text('Add Category'),
      ),
    );
  }
}

class _CategoryTile extends ConsumerWidget {
  const _CategoryTile({required this.category});

  final Category category;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return InkWell(
      borderRadius: BorderRadius.circular(12),
      onTap: hapticize(() async {
        final saved = await showDialog<bool>(
          context: context,
          builder: (context) => _CategoryFormDialog(category: category),
        );
        if (saved == true) ref.invalidate(adminCategoriesProvider);
      }),
      child: Container(
        padding: const EdgeInsets.all(14),
        decoration: BoxDecoration(color: AdminColors.surface, borderRadius: BorderRadius.circular(12)),
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
            const Icon(Icons.chevron_right, color: AdminColors.textSecondary),
          ],
        ),
      ),
    );
  }
}

class _CategoryFormDialog extends ConsumerStatefulWidget {
  const _CategoryFormDialog({this.category});

  /// Null means "add new category" - non-null means editing this one.
  final Category? category;

  @override
  ConsumerState<_CategoryFormDialog> createState() => _CategoryFormDialogState();
}

class _CategoryFormDialogState extends ConsumerState<_CategoryFormDialog> {
  late final TextEditingController _nameController;
  late final TextEditingController _descriptionController;
  late final TextEditingController _gstRateController;
  late bool _active;
  bool _isSaving = false;

  bool get _isEditing => widget.category != null;

  @override
  void initState() {
    super.initState();
    final c = widget.category;
    _nameController = TextEditingController(text: c?.name ?? '');
    _descriptionController = TextEditingController(text: c?.description ?? '');
    _gstRateController = TextEditingController(text: c?.gstRate?.toString() ?? '');
    _active = c?.active ?? true;
  }

  @override
  void dispose() {
    _nameController.dispose();
    _descriptionController.dispose();
    _gstRateController.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    if (_nameController.text.trim().isEmpty) return;

    setState(() => _isSaving = true);

    final gstRate = _gstRateController.text.trim().isEmpty ? null : double.tryParse(_gstRateController.text.trim());

    try {
      final repository = ref.read(adminProductsRepositoryProvider);

      if (_isEditing) {
        await repository.updateCategory(
          categoryId: widget.category!.id,
          name: _nameController.text.trim(),
          description: _descriptionController.text.trim().isEmpty ? null : _descriptionController.text.trim(),
          gstRate: gstRate,
          active: _active,
        );
      } else {
        await repository.createCategory(
          name: _nameController.text.trim(),
          description: _descriptionController.text.trim().isEmpty ? null : _descriptionController.text.trim(),
          gstRate: gstRate,
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
        title: const Text('Deactivate this category?'),
        content: const Text('Existing products keep their reference to it - it will just stop showing to customers.'),
        actions: [
          TextButton(onPressed: hapticize(() => Navigator.of(context).pop(false)), child: const Text('Cancel')),
          TextButton(onPressed: hapticize(() => Navigator.of(context).pop(true)), child: const Text('Deactivate')),
        ],
      ),
    );
    if (confirmed != true) return;

    try {
      await ref.read(adminProductsRepositoryProvider).deactivateCategory(widget.category!.id);
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
      title: Text(_isEditing ? 'Edit Category' : 'Add Category'),
      content: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            TextField(controller: _nameController, decoration: const InputDecoration(labelText: 'Category name')),
            const SizedBox(height: 12),
            TextField(
              controller: _descriptionController,
              decoration: const InputDecoration(labelText: 'Description (optional)'),
            ),
            const SizedBox(height: 12),
            TextField(
              controller: _gstRateController,
              keyboardType: const TextInputType.numberWithOptions(decimal: true),
              decoration: const InputDecoration(labelText: 'GST rate % (optional)'),
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
            child: const Text('Deactivate'),
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
