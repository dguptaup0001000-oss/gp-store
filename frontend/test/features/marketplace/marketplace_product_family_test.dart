import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/features/marketplace/domain/marketplace_feed_models.dart';
import 'package:gpstore/features/marketplace/presentation/marketplace_product_family_screen.dart';

MarketplaceCard card(String name, {String? category}) => MarketplaceCard(
      productId: 1,
      name: name,
      categoryName: category,
      commerceMode: CommerceMode.buyOnline,
      priceMode: ListingPriceMode.exact,
      addable: true,
    );

void main() {
  test('namak opens the Salt family instead of one exact packet', () {
    final family =
        MarketplaceProductFamily.fromCard(card('TATA NIMAK', category: 'Salt & Sugar'));
    expect(family.title, 'Salt');
    expect(family.query, 'salt');
  });

  test('mobile category opens Phones even when model name omits phone', () {
    final family = MarketplaceProductFamily.fromCard(
      card('moto edge 50 pro', category: 'Mobile and Electronics'),
    );
    expect(family.title, 'Phones');
    expect(family.query, 'phone');
  });

  test('namkeen and soap resolve to narrow product families', () {
    expect(MarketplaceProductFamily.fromCard(card('Aloo Bhujia Namkeen')).query,
        'namkeen');
    expect(MarketplaceProductFamily.fromCard(card('Neem Bathing Soap')).query,
        'soap');
  });

  test('unknown family falls back to authored category', () {
    final family = MarketplaceProductFamily.fromCard(
      card('Model X', category: 'Hardware and Electrical'),
    );
    expect(family.query, 'Hardware and Electrical');
  });
}
