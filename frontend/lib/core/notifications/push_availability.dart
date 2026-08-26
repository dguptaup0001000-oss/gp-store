/// Whether Firebase initialized in [main]. False until that one-time setup
/// is done — order status still exists in-app; push banners must not pretend
/// otherwise.
class PushAvailability {
  PushAvailability._();

  static bool firebaseReady = false;
}
