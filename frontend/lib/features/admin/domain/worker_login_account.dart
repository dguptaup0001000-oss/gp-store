/// Which account, if any, a delivery partner signs in to the worker app with.
///
/// [canSignIn] is separate from [linked] on purpose. Creating a partner
/// already made them a login account - by mobile number, for OTP - and that
/// account has no password. The worker app has no OTP form; email and password
/// is its only way in. So an account can be perfectly well linked and still be
/// unable to sign in, and an admin screen that showed only "linked" would be
/// telling the shopkeeper their rider is sorted when they are still locked out.
class WorkerLoginAccount {
  const WorkerLoginAccount({
    required this.linked,
    required this.email,
    required this.canSignIn,
  });

  final bool linked;
  final String? email;
  final bool canSignIn;

  static const none = WorkerLoginAccount(linked: false, email: null, canSignIn: false);

  factory WorkerLoginAccount.fromJson(Map<String, dynamic> json) {
    return WorkerLoginAccount(
      linked: json['linked'] as bool? ?? false,
      email: json['email'] as String?,
      canSignIn: json['canSignIn'] as bool? ?? false,
    );
  }
}
