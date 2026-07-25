import 'package:flutter/material.dart';
import 'package:package_info_plus/package_info_plus.dart';

import '../../../core/theme/app_theme.dart';

class AboutScreen extends StatelessWidget {
  const AboutScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('About')),
      body: ListView(
        padding: const EdgeInsets.all(24),
        children: [
          const Icon(Icons.storefront_outlined, size: 64, color: AppColors.primary),
          const SizedBox(height: 16),
          Text('GP-Store', style: Theme.of(context).textTheme.headlineSmall, textAlign: TextAlign.center),
          const SizedBox(height: 8),
          const Text(
            'Quick commerce from your neighbourhood kirana store - order groceries and daily essentials for fast delivery.',
            textAlign: TextAlign.center,
            style: TextStyle(color: AppColors.textSecondary),
          ),
          const SizedBox(height: 24),
          const Divider(),
          const SizedBox(height: 16),
          // Reads the real version from the actual build (pubspec.yaml's
          // version field) rather than a hardcoded string that would
          // silently drift out of sync at the next release, exactly as the
          // previous "Version 1.0.0" already had against pubspec's 0.1.0.
          FutureBuilder<PackageInfo>(
            future: PackageInfo.fromPlatform(),
            builder: (context, snapshot) {
              final info = snapshot.data;
              final versionText = info == null
                  ? ' '
                  : 'Version ${info.version}${info.buildNumber.isNotEmpty ? '+${info.buildNumber}' : ''}';
              return Text(versionText, textAlign: TextAlign.center, style: const TextStyle(color: AppColors.textSecondary));
            },
          ),
        ],
      ),
    );
  }
}
