import 'package:flutter/material.dart';
import 'package:model_viewer_plus/model_viewer_plus.dart';

import '../../../core/theme/app_theme.dart';

/// The 3D view, on its own route, reached only by an explicit tap.
///
/// WHY A SEPARATE SCREEN AND NOT A TAB IN THE GALLERY. A GLB is orders of
/// magnitude larger than a product photo - single-digit megabytes is normal
/// for something worth rendering. Putting the viewer inline on the detail
/// page would start that download for every customer who opened the product,
/// including the overwhelming majority who only wanted to check the price.
/// Behind a route, the bytes are spent by people who asked for them.
///
/// NOTHING ABOUT THIS REACHES THE HOME SCREEN, the feed, or any list. The
/// backend does not even send model3dUrl on list responses (see
/// ProductResponse), so a browsing customer cannot incur this cost by
/// accident - the field they would need is not in the payload.
class Product3dViewScreen extends StatelessWidget {
  const Product3dViewScreen({
    super.key,
    required this.modelUrl,
    required this.productName,
  });

  final String modelUrl;
  final String productName;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: Text(productName, maxLines: 1, overflow: TextOverflow.ellipsis)),
      body: ModelViewer(
        src: modelUrl,
        alt: '3D model of $productName',
        // Rotatable and zoomable but NOT auto-rotating: a model that spins
        // on its own is a novelty on first sight and an irritation while
        // someone is trying to read a label on the packaging.
        cameraControls: true,
        autoRotate: false,
        // Transparent so the viewer sits on the app's own surface colour
        // rather than punching a white rectangle into a lavender screen.
        backgroundColor: AppColors.surfaceSoft,
        // A model that fails to load must not leave a blank screen with no
        // explanation - see the poster/placeholder note in the loading UI.
        loading: Loading.eager,
      ),
    );
  }
}

/// The entry point, and the whole of the fallback behaviour.
///
/// Returns nothing at all when the product has no model, which is almost
/// every product. That IS the fallback: no button, no empty state, no
/// "3D unavailable" row taking up space on a page where the customer wants
/// the price and the ADD button. A product without a model should look
/// exactly as it did before this feature existed.
class View3dButton extends StatelessWidget {
  const View3dButton({super.key, required this.modelUrl, required this.productName});

  /// Null or blank for a product with no model - see Product.has3dModel.
  final String? modelUrl;
  final String productName;

  @override
  Widget build(BuildContext context) {
    final url = modelUrl?.trim();
    if (url == null || url.isEmpty) return const SizedBox.shrink();

    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 4, 16, 0),
      child: OutlinedButton.icon(
        onPressed: () => Navigator.of(context).push(
          MaterialPageRoute(
            builder: (_) => Product3dViewScreen(modelUrl: url, productName: productName),
          ),
        ),
        icon: const Icon(Icons.threed_rotation, size: 20),
        label: const Text('View in 3D'),
        style: OutlinedButton.styleFrom(
          minimumSize: const Size.fromHeight(44),
          foregroundColor: AppColors.primary,
        ),
      ),
    );
  }
}
