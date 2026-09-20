/// What a shop's offline listings attracted, and what those numbers are not.
///
/// An online sale records itself - an order, a payment, a receipt - and every
/// other number on the analytics screen comes from one. A Visit-to-Buy
/// listing has none of that: the customer sees the card, taps Directions,
/// walks in and pays in cash.
///
/// So this measures INTEREST. The sentence that keeps it honest arrives WITH
/// the numbers as [note], from the server, rather than living in this screen's
/// copy - a count without it invites exactly the reading that would make it a
/// lie, and the screen must not be the only thing standing between a merchant
/// and "you sold Rs 1 lakh".
class ListingEngagementReport {
  const ListingEngagementReport({
    required this.byMode,
    required this.note,
  });

  /// Commerce mode -> kind of interest -> how many.
  final Map<String, Map<String, int>> byMode;

  /// The server's own statement of what these numbers are not. Never
  /// rewritten here, and never omitted from the screen.
  final String note;

  static const empty = ListingEngagementReport(byMode: {}, note: '');

  factory ListingEngagementReport.fromJson(Map<String, dynamic> json) {
    final raw = (json['byMode'] as Map?) ?? const {};
    final byMode = <String, Map<String, int>>{};
    raw.forEach((mode, kinds) {
      if (kinds is! Map) return;
      final counts = <String, int>{};
      kinds.forEach((kind, value) {
        if (value is num) counts['$kind'] = value.toInt();
      });
      byMode['$mode'] = counts;
    });
    return ListingEngagementReport(
      byMode: byMode,
      note: (json['note'] as String?) ?? '',
    );
  }

  int get total => byMode.values
      .expand((kinds) => kinds.values)
      .fold(0, (sum, count) => sum + count);

  /// Counts for one mode, across every kind of interest.
  Map<String, int> forMode(String mode) => byMode[mode] ?? const {};

  bool get hasOfflineListings =>
      byMode.keys.any((mode) => mode != 'ONLINE_PURCHASE');

  /// Reads a kind as something a shopkeeper would say, never as an enum name.
  static String kindLabel(String kind) {
    switch (kind) {
      case 'VIEWED_CARD':
        return 'Seen in the app';
      case 'OPENED_DETAIL':
        return 'Opened';
      case 'ASKED_DIRECTIONS':
        return 'Asked for directions';
      case 'CALLED_SHOP':
        return 'Called the shop';
      default:
        // A kind a newer server knows and this build does not. Dropped rather
        // than printed raw - a screen showing SOMETHING_NEW to a shopkeeper
        // reads as a broken app.
        return '';
    }
  }

  static String modeLabel(String mode) {
    switch (mode) {
      case 'VISIT_TO_BUY':
        return 'Visit to Buy';
      case 'SERVICE_AT_SHOP':
        return 'Service at Shop';
      case 'ONLINE_PURCHASE':
        return 'Buy Online';
      default:
        return '';
    }
  }
}
