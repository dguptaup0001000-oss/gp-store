import 'package:flutter_riverpod/flutter_riverpod.dart';

/// Which shop the app is currently acting for.
///
/// WHAT THIS IS NOT: an authorization. Nothing the app puts here grants
/// anything. The backend derives the caller's scope from their credential and
/// nothing else (§78, TenantResolver.resolve); a shop id arriving on a request
/// may only NARROW a scope the credential already permits, and asking for a
/// shop the credential does not cover is refused. So this holds a preference -
/// which of my shops am I looking at, which storefront am I browsing - and the
/// server decides whether that preference is allowed, every time.
///
/// That distinction is the whole reason this is a plain holder with no
/// validation in it. Validating here would create a second, weaker copy of a
/// rule the server already enforces, and the first time the two disagreed the
/// app's copy would be the one that was wrong.
///
/// NULL IS THE NORMAL STATE, and it is what the existing single-shop app has.
/// With no shop named, the backend answers Shop #1 under SINGLE_SHOP and the
/// customer's nearest serving shop under a marketplace - both without the app
/// saying anything. Nothing about Shop #1 requires this to be set.
class ShopContext extends Notifier<int?> {
  @override
  int? build() => null;

  /// Acts for this shop from now on. The server still decides whether the
  /// caller may.
  void select(int shopId) => state = shopId;

  /// Goes back to letting the backend choose - the customer's nearest serving
  /// shop, or the staff member's own default shop.
  void clear() => state = null;
}

final shopContextProvider = NotifierProvider<ShopContext, int?>(ShopContext.new);

/// The header name the backend reads a narrowing request from.
///
/// One constant so the app and TenantContextFilter.SHOP_HEADER cannot drift
/// apart in spelling.
const String shopHeaderName = 'X-Shop-Id';
