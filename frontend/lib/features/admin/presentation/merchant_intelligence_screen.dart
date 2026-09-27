import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../auth/presentation/auth_providers.dart';

class MerchantIntelligenceScreen extends ConsumerWidget {
  const MerchantIntelligenceScreen({super.key});

  Future<Map<String, dynamic>> _load(WidgetRef ref) async {
    final response = await ref.read(apiClientProvider).dio.get(
      '/api/shop/intelligence',
      queryParameters: {'days': 30},
    );
    return Map<String, dynamic>.from(response.data as Map);
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return Scaffold(
      appBar: AppBar(title: const Text('Merchant Intelligence')),
      body: FutureBuilder<Map<String, dynamic>>(
        future: _load(ref),
        builder: (context, snapshot) {
          if (snapshot.connectionState != ConnectionState.done) {
            return const Center(child: CircularProgressIndicator());
          }
          if (snapshot.hasError) {
            return const Center(child: Text('Could not load marketplace intelligence.'));
          }
          final data = snapshot.data!;
          final unmet = data['unmetDemand'] as List? ?? const [];
          final frequent = data['frequentSearches'] as List? ?? const [];
          return ListView(
            padding: const EdgeInsets.all(16),
            children: [
              const Text(
                'Aggregated nearby demand only — no customer identities, contact details, addresses, or individual histories.',
              ),
              const SizedBox(height: 16),
              Wrap(
                spacing: 12,
                runSpacing: 12,
                children: [
                  _Metric('Nearby searches', data['searchesNearby']),
                  _Metric('Unmet searches', data['zeroResultSearchesNearby']),
                  _Metric('Open requests', data['openDemandRequests']),
                  _Metric('Merchant responses', data['demandResponses']),
                  _Metric('Visit interest', data['visitInterest']),
                  _Metric('Service interest', data['serviceInterest']),
                ],
              ),
              const SizedBox(height: 24),
              const Text('Unmet local demand', style: TextStyle(fontWeight: FontWeight.w700, fontSize: 17)),
              for (final raw in unmet)
                _DemandRow(Map<String, dynamic>.from(raw as Map)),
              const SizedBox(height: 20),
              const Text('Frequently searched', style: TextStyle(fontWeight: FontWeight.w700, fontSize: 17)),
              for (final raw in frequent)
                _DemandRow(Map<String, dynamic>.from(raw as Map)),
            ],
          );
        },
      ),
    );
  }
}

class _Metric extends StatelessWidget {
  const _Metric(this.label, this.value);
  final String label;
  final Object? value;

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      width: 150,
      child: Card(
        child: Padding(
          padding: const EdgeInsets.all(14),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text('${value ?? 0}', style: const TextStyle(fontSize: 24, fontWeight: FontWeight.w800)),
              Text(label),
            ],
          ),
        ),
      ),
    );
  }
}

class _DemandRow extends StatelessWidget {
  const _DemandRow(this.data);
  final Map<String, dynamic> data;

  @override
  Widget build(BuildContext context) => ListTile(
        contentPadding: EdgeInsets.zero,
        leading: const Icon(Icons.search),
        title: Text(data['query'] as String? ?? ''),
        subtitle: Text('${data['searches'] ?? 0} aggregated searches'),
        trailing: Text('${data['zeroResultSearches'] ?? 0} unmet'),
      );
}
