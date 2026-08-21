/// One tile of the home screen's Bestsellers collage.
///
/// Deliberately NOT a Category plus a List<Product>. The tile draws four
/// thumbnails and a name; it never reads a price, a rating, a stock flag or
/// a variant list. Asking the backend for whole products would put tens of
/// kilobytes of unused fields on the wire for every cold app open, on a
/// phone that may be on mobile data - see BestsellerTileResponse on the
/// backend for the same reasoning from the other side.
class BestsellerTile {
  const BestsellerTile({
    required this.categoryId,
    required this.categoryName,
    required this.productIds,
    required this.imageUrls,
  });

  final int categoryId;
  final String categoryName;
  final List<int> productIds;

  /// Parallel to [productIds], and nullable per entry: a product with no
  /// variant, or a variant with no photo, keeps its square and renders the
  /// placeholder icon. Dropping it would shift the other three thumbnails
  /// into the wrong positions.
  final List<String?> imageUrls;

  /// Tolerant of a partial payload on purpose. A malformed tile costs the
  /// customer four thumbnails; throwing would cost them the whole home
  /// screen, and the collage is the least important thing on it.
  factory BestsellerTile.fromJson(Map<String, dynamic> json) {
    final ids = (json['productIds'] as List?) ?? const [];
    final urls = (json['imageUrls'] as List?) ?? const [];

    return BestsellerTile(
      categoryId: (json['categoryId'] as num?)?.toInt() ?? 0,
      categoryName: (json['categoryName'] as String?) ?? '',
      productIds: ids.map((e) => (e as num?)?.toInt() ?? 0).toList(),
      // Padded to match, so the two lists are safe to read positionally even
      // if a backend change ever lets them drift apart.
      imageUrls: List<String?>.generate(
        ids.length,
        (i) => i < urls.length ? urls[i] as String? : null,
      ),
    );
  }

  /// A tile with nothing to show is not worth a square on the grid.
  bool get isRenderable => categoryName.isNotEmpty && productIds.isNotEmpty;
}
