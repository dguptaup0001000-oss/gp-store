import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/api/error_messages.dart';
import '../domain/catalog_import_models.dart';
import 'admin_providers.dart';

/// Loading a shop's catalogue from a spreadsheet.
///
/// TWO DELIBERATE STEPS. Choosing a file only CHECKS it; nothing is written
/// until the shopkeeper reads the counts and presses Import. A one-tap
/// importer would be faster and would also be the thing that silently
/// rewrites 900 prices from a file with a misplaced decimal.
///
/// The picked bytes are held in memory and sent again on import, unchanged.
/// The server stores a SHA-256 of what it previewed and refuses a commit whose
/// bytes differ, so re-reading the file from disk would risk failing that
/// check if the sheet were edited in between - which is precisely the case it
/// is there to catch.
class AdminCatalogImportScreen extends ConsumerStatefulWidget {
  const AdminCatalogImportScreen({super.key});

  @override
  ConsumerState<AdminCatalogImportScreen> createState() =>
      _AdminCatalogImportScreenState();
}

class _AdminCatalogImportScreenState
    extends ConsumerState<AdminCatalogImportScreen> {
  String? _filename;
  List<int>? _bytes;
  CatalogImportSummary? _summary;
  bool _updateOnly = false;
  bool _busy = false;
  String? _error;

  Future<void> _pickAndPreview() async {
    if (_busy) return;

    final picked = await FilePicker.platform.pickFiles(
      type: FileType.custom,
      // The server accepts exactly these, so offering more would only produce
      // a refusal after the upload.
      allowedExtensions: const ['csv', 'xlsx'],
      withData: true,
    );
    if (picked == null || picked.files.isEmpty) {
      return;
    }
    final file = picked.files.first;
    final bytes = file.bytes;
    if (bytes == null) {
      setState(() => _error =
          'That file could not be read. Try choosing it from Downloads.');
      return;
    }

    setState(() {
      _filename = file.name;
      _bytes = bytes;
      _summary = null;
      _error = null;
      _busy = true;
    });

    try {
      final summary = await ref.read(catalogImportRepositoryProvider).preview(
            filename: file.name,
            bytes: bytes,
            updateOnly: _updateOnly,
          );
      if (!mounted) return;
      setState(() => _summary = summary);
    } catch (e) {
      if (!mounted) return;
      setState(() => _error = extractErrorMessage(e));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _commit() async {
    final summary = _summary;
    final bytes = _bytes;
    final filename = _filename;
    if (summary == null || bytes == null || filename == null || _busy) return;

    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final done = await ref.read(catalogImportRepositoryProvider).commit(
            runId: summary.runId,
            filename: filename,
            bytes: bytes,
          );
      if (!mounted) return;
      setState(() => _summary = done);
      ref.invalidate(catalogImportHistoryProvider);
    } catch (e) {
      if (!mounted) return;
      setState(() => _error = extractErrorMessage(e));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final summary = _summary;

    return Scaffold(
      appBar: AppBar(title: const Text('Import catalogue')),
      body: ListView(
        padding: const EdgeInsets.fromLTRB(16, 12, 16, 32),
        children: [
          Text('Load products from a spreadsheet',
              style: theme.textTheme.titleLarge),
          const SizedBox(height: 4),
          Text(
            'Choose a .csv or .xlsx file. Nothing is saved until you have seen '
            'what it would do and pressed Import.',
            style: theme.textTheme.bodyMedium
                ?.copyWith(color: theme.colorScheme.outline),
          ),
          const SizedBox(height: 16),

          // UPDATE-ONLY IS A REAL SAFETY SWITCH, not a preference. With it on,
          // a typo'd SKU fails that row instead of quietly creating a product
          // the shop does not sell.
          SwitchListTile(
            contentPadding: EdgeInsets.zero,
            value: _updateOnly,
            onChanged: _busy ? null : (v) => setState(() => _updateOnly = v),
            title: const Text('Only update products that already exist'),
            subtitle: const Text(
                'A SKU that is not in the catalogue fails instead of creating '
                'a new product. Use this for price and stock sheets.'),
          ),
          const SizedBox(height: 8),

          SizedBox(
            height: 52,
            child: FilledButton.icon(
              onPressed: _busy ? null : _pickAndPreview,
              icon: _busy && summary == null
                  ? const SizedBox(
                      width: 18,
                      height: 18,
                      child: CircularProgressIndicator(strokeWidth: 2))
                  : const Icon(Icons.upload_file),
              label: Text(_filename == null ? 'CHOOSE FILE' : 'CHOOSE ANOTHER FILE'),
            ),
          ),
          if (_filename != null) ...[
            const SizedBox(height: 8),
            Text(_filename!, style: theme.textTheme.bodyMedium),
          ],

          if (_error != null) ...[
            const SizedBox(height: 16),
            Container(
              width: double.infinity,
              padding: const EdgeInsets.all(12),
              decoration: BoxDecoration(
                color: theme.colorScheme.errorContainer,
                borderRadius: BorderRadius.circular(8),
              ),
              child: Text(_error!,
                  style: TextStyle(color: theme.colorScheme.onErrorContainer)),
            ),
          ],

          if (summary != null) ...[
            const SizedBox(height: 24),
            _Counts(summary: summary),
            const SizedBox(height: 16),

            if (summary.committed)
              Container(
                width: double.infinity,
                padding: const EdgeInsets.all(16),
                decoration: BoxDecoration(
                  color: theme.colorScheme.tertiaryContainer,
                  borderRadius: BorderRadius.circular(8),
                ),
                child: Text(
                  'Imported. ${summary.createdCount} new '
                  '${summary.createdCount == 1 ? 'product' : 'products'}, '
                  '${summary.updatedCount} updated.',
                  style: theme.textTheme.titleMedium,
                ),
              )
            else
              SizedBox(
                height: 52,
                child: FilledButton(
                  // Nothing importable means no button that pretends to work.
                  onPressed: _busy || !summary.hasImportableRows ? null : _commit,
                  child: _busy
                      ? const SizedBox(
                          width: 18,
                          height: 18,
                          child: CircularProgressIndicator(strokeWidth: 2))
                      : Text(summary.hasImportableRows
                          ? 'IMPORT ${summary.totalRows - summary.errorRows} ROWS'
                          : 'NOTHING CAN BE IMPORTED'),
                ),
              ),

            if (summary.problems.isNotEmpty) ...[
              const SizedBox(height: 24),
              Text('What needs fixing', style: theme.textTheme.titleLarge),
              const SizedBox(height: 4),
              Text(
                'Row numbers match your spreadsheet - the header is row 1.',
                style: theme.textTheme.bodySmall
                    ?.copyWith(color: theme.colorScheme.outline),
              ),
              const SizedBox(height: 8),
              for (final problem in summary.problems)
                _ProblemTile(problem: problem),
            ],
          ],

          const SizedBox(height: 32),
          Text('Columns the sheet may contain',
              style: theme.textTheme.titleLarge),
          const SizedBox(height: 4),
          Text(
            'Only the columns you include are changed. A sheet of just SKU and '
            'Selling Price updates prices and leaves names, photos, stock and '
            'everything else exactly as it is.',
            style: theme.textTheme.bodyMedium,
          ),
          const SizedBox(height: 8),
          Text(catalogImportColumns.join(', '), style: theme.textTheme.bodySmall),
          const SizedBox(height: 8),
          // COPY, NOT DOWNLOAD. The app has no file-saving code anywhere and
          // adding storage permissions to every install for one admin screen
          // is a worse trade than a clipboard paste into the first row of a
          // blank sheet - which is the same starting point the downloaded
          // template would have given.
          OutlinedButton.icon(
            onPressed: () async {
              await Clipboard.setData(
                  ClipboardData(text: catalogImportColumns.join(',')));
              if (!context.mounted) return;
              ScaffoldMessenger.of(context).showSnackBar(const SnackBar(
                  content: Text(
                      'Column headings copied. Paste into row 1 of a blank sheet.')));
            },
            icon: const Icon(Icons.content_copy, size: 18),
            label: const Text('COPY COLUMN HEADINGS'),
          ),

          const SizedBox(height: 32),
          Text('Past imports', style: theme.textTheme.titleLarge),
          const SizedBox(height: 8),
          const _ImportHistory(),
        ],
      ),
    );
  }
}

/// The rows one past import refused.
///
/// The preview shows these at upload time and they vanish with the screen,
/// leaving a history row that says "3 refused" and no way to learn which
/// three. Read back into a sheet rather than downloaded as a file: the app
/// has no file-saving code anywhere, and adding a storage permission to
/// every customer install for one admin screen is the worse trade.
void _showPastProblems(BuildContext context, CatalogImportRun run) {
  showModalBottomSheet<void>(
    context: context,
    isScrollControlled: true,
    builder: (context) => DraggableScrollableSheet(
      expand: false,
      initialChildSize: 0.7,
      maxChildSize: 0.9,
      builder: (context, controller) => Consumer(
        builder: (context, ref, _) {
          final problems = ref.watch(catalogImportProblemsProvider(run.id));
          final theme = Theme.of(context);

          return ListView(
            controller: controller,
            padding: const EdgeInsets.fromLTRB(16, 16, 16, 32),
            children: [
              Text(run.filename, style: theme.textTheme.titleMedium),
              const SizedBox(height: 2),
              Text(
                '${run.errorRows} of ${run.totalRows} rows were refused. '
                'Row numbers match your spreadsheet - the header is row 1.',
                style: theme.textTheme.bodySmall
                    ?.copyWith(color: theme.colorScheme.outline),
              ),
              const SizedBox(height: 16),
              // Explicit type argument: the three branches return lists of
              // three different widget classes, and letting inference pick the
              // common supertype is not worth the risk in a spread.
              ...problems.when<List<Widget>>(
                loading: () => const [
                  Padding(
                    padding: EdgeInsets.symmetric(vertical: 32),
                    child: Center(child: CircularProgressIndicator()),
                  ),
                ],
                // A LIST THAT FAILED SAYS SO. An empty sheet here would read
                // as "nothing was refused", which is the opposite of true for
                // a run that only opens because something was.
                error: (e, _) => [
                  Text(
                    "Couldn't load what this import refused: "
                    '${extractErrorMessage(e)}',
                    style: theme.textTheme.bodyMedium
                        ?.copyWith(color: theme.colorScheme.error),
                  ),
                ],
                data: (rows) => rows.isEmpty
                    ? [
                        Text(
                          'This import kept no record of the refused rows.',
                          style: theme.textTheme.bodyMedium
                              ?.copyWith(color: theme.colorScheme.outline),
                        ),
                      ]
                    : [for (final problem in rows) _ProblemTile(problem: problem)],
              ),
            ],
          );
        },
      ),
    ),
  );
}

class _Counts extends StatelessWidget {
  const _Counts({required this.summary});

  final CatalogImportSummary summary;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Wrap(
      spacing: 10,
      runSpacing: 10,
      children: [
        _chip(theme, 'VALID', summary.validRows, theme.colorScheme.primary),
        _chip(theme, 'WARNINGS', summary.warningRows, Colors.orange.shade800),
        _chip(theme, 'ERRORS', summary.errorRows, theme.colorScheme.error),
      ],
    );
  }

  Widget _chip(ThemeData theme, String label, int count, Color color) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
      decoration: BoxDecoration(
        border: Border.all(color: color.withValues(alpha: 0.5)),
        borderRadius: BorderRadius.circular(8),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(label,
              style: theme.textTheme.labelSmall
                  ?.copyWith(letterSpacing: 1.1, color: color)),
          Text('$count', style: theme.textTheme.headlineSmall),
        ],
      ),
    );
  }
}

class _ProblemTile extends StatelessWidget {
  const _ProblemTile({required this.problem});

  final CatalogImportProblem problem;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final color =
        problem.isError ? theme.colorScheme.error : Colors.orange.shade800;

    return Padding(
      padding: const EdgeInsets.only(bottom: 10),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(problem.isError ? Icons.error_outline : Icons.warning_amber,
              size: 18, color: color),
          const SizedBox(width: 8),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  problem.field == null
                      ? 'Row ${problem.row}'
                      : 'Row ${problem.row} - ${problem.field}',
                  style: theme.textTheme.labelLarge?.copyWith(color: color),
                ),
                Text(problem.problem, style: theme.textTheme.bodyMedium),
                if (problem.suggestion != null)
                  Text(problem.suggestion!,
                      style: theme.textTheme.bodySmall
                          ?.copyWith(color: theme.colorScheme.outline)),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _ImportHistory extends ConsumerWidget {
  const _ImportHistory();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final history = ref.watch(catalogImportHistoryProvider);
    final theme = Theme.of(context);

    return history.when(
      loading: () => const Padding(
        padding: EdgeInsets.symmetric(vertical: 12),
        child: Center(child: CircularProgressIndicator()),
      ),
      // A LIST THAT FAILED SAYS SO. An empty list here would read as "no
      // imports have ever happened", which is a different and misleading fact.
      error: (e, _) => Text('Past imports could not be loaded: '
          '${extractErrorMessage(e)}',
          style: theme.textTheme.bodyMedium
              ?.copyWith(color: theme.colorScheme.error)),
      data: (runs) {
        if (runs.isEmpty) {
          return Text('No catalogue has been imported yet.',
              style: theme.textTheme.bodyMedium
                  ?.copyWith(color: theme.colorScheme.outline));
        }
        return Column(
          children: [
            for (final run in runs)
              ListTile(
                contentPadding: EdgeInsets.zero,
                title: Text(run.filename),
                subtitle: Text(
                  '${run.status} - ${run.totalRows} rows, '
                  '${run.createdCount} new, ${run.updatedCount} updated'
                  '${run.errorRows > 0 ? ', ${run.errorRows} refused' : ''}'
                  '${run.adminEmail != null ? '\nby ${run.adminEmail}' : ''}',
                ),
                isThreeLine: run.adminEmail != null,
                // ONLY A RUN THAT REFUSED SOMETHING OPENS. A chevron on a
                // clean import would promise a list that turns out to be
                // empty, which reads as a screen that failed to load.
                trailing: run.errorRows > 0
                    ? const Icon(Icons.chevron_right, size: 20)
                    : null,
                onTap: run.errorRows > 0
                    ? () => _showPastProblems(context, run)
                    : null,
              ),
          ],
        );
      },
    );
  }
}
