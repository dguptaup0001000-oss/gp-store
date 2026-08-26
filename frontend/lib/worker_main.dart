import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'core/api/api_client.dart';
import 'core/config/app_environment.dart';
import 'core/monitoring/crash_reporter.dart';
import 'core/storage/token_storage.dart';
import 'features/worker/data/worker_repository.dart';
import 'features/worker/presentation/worker_gate.dart';

/// The GP-STORE Delivery Worker app.
///
/// A SEPARATE ENTRYPOINT, NOT A SEPARATE PROJECT, and that was a deliberate
/// trade. Building this as its own Flutter project would mean a second copy of
/// the HTTP client, the token storage, the refresh-on-401 handling and the
/// error messages - four things that took real work to get right and that
/// would then drift apart silently. Sharing the project means
/// `flutter build apk -t lib/worker_main.dart` produces a genuinely separate,
/// much smaller APK that reuses all of it.
///
/// THE COST, stated plainly: Flutter tree-shakes Dart, not Gradle plugins.
/// The customer APK still carries the scanner native library. Worker release
/// builds swap pubspec.worker.yaml (see tool/with_worker_pubspec.sh) so this
/// APK does not also ship Cashfree, Firebase, WebView/model-viewer, TTS or
/// the thermal-printer stack.
///
/// WHAT THIS APP IS NOT. There is no map, no route, no navigation, no delivery
/// confirmation, no OTP, no proof of delivery. A worker here packs and takes
/// responsibility; everything else is a later decision and this stays small
/// until then.
Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  AppEnvironment.assertReleaseBuildIsConfigured(isReleaseMode: kReleaseMode);
  await installCrashHandlers(const NoOpCrashReporter());

  // Portrait only. One hand, one thumb, a phone held while the other hand
  // holds a carton - a landscape layout would be a rotation nobody asked for.
  SystemChrome.setPreferredOrientations([DeviceOrientation.portraitUp]);

  runApp(const WorkerAppBootstrap());
}

class WorkerAppBootstrap extends StatefulWidget {
  const WorkerAppBootstrap({super.key});

  @override
  State<WorkerAppBootstrap> createState() => _WorkerAppBootstrapState();
}

class _WorkerAppBootstrapState extends State<WorkerAppBootstrap> {
  late final TokenStorage _tokenStorage;
  late final ApiClient _apiClient;
  late final WorkerRepository _repository;
  int _sessionEpoch = 0;

  @override
  void initState() {
    super.initState();
    _tokenStorage = TokenStorage(keyPrefix: 'worker_');
    _apiClient = ApiClient(
      tokenStorage: _tokenStorage,
      onSessionExpired: () {
        if (mounted) setState(() => _sessionEpoch++);
      },
    );
    _repository = WorkerRepository(apiClient: _apiClient);
  }

  @override
  Widget build(BuildContext context) {
    return WorkerApp(
      key: ValueKey(_sessionEpoch),
      apiClient: _apiClient,
      tokenStorage: _tokenStorage,
      repository: _repository,
    );
  }
}

class WorkerApp extends StatelessWidget {
  const WorkerApp({
    super.key,
    required this.apiClient,
    required this.tokenStorage,
    required this.repository,
  });

  final ApiClient apiClient;
  final TokenStorage tokenStorage;
  final WorkerRepository repository;

  /// The one colour this app has.
  ///
  /// Not the customer app's theme: a worker glances at this screen in a shop
  /// doorway in daylight, and high contrast beats brand consistency. Dark
  /// surface, one strong accent, nothing else.
  static const Color _accent = Color(0xFF00A86B);
  static const Color _surface = Color(0xFF14181C);

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'GP-Store Worker',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        useMaterial3: true,
        brightness: Brightness.dark,
        scaffoldBackgroundColor: _surface,
        colorScheme: ColorScheme.fromSeed(
          seedColor: _accent,
          brightness: Brightness.dark,
        ),
        // Deliberately blunt: a worker reads this at arm's length.
        textTheme: const TextTheme(
          headlineLarge: TextStyle(fontSize: 32, fontWeight: FontWeight.w800),
          titleLarge: TextStyle(fontSize: 20, fontWeight: FontWeight.w700),
          bodyLarge: TextStyle(fontSize: 17),
          bodyMedium: TextStyle(fontSize: 15),
        ),
      ),
      home: WorkerGate(
        apiClient: apiClient,
        tokenStorage: tokenStorage,
        repository: repository,
      ),
    );
  }
}
