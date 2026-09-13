/// The moves a merchant can make from where it is, and what to call them.
///
/// A MIRROR, NOT THE RULE. `MerchantStatus.allowedNext` on the server is the
/// rule and the server re-checks every transition; this copy exists so that
/// buttons which cannot work are never drawn. When the two disagree the server
/// wins and the person is shown a refusal - the safe direction for a copy to be
/// wrong in - and `merchant_transitions_offered_test` keeps them in step by
/// reading this file and MerchantStatus.java side by side.
///
/// WHY A COPY AT ALL. The console used to offer every status on every card, so
/// the platform owner could tap "Approve" on a business still in APPLICATION
/// and be answered "A merchant cannot go from APPLICATION to APPROVED" - a
/// refusal that is correct, arrives after the tap, and explains nothing about
/// what to do instead. The real answer was "Send for review" first, and nothing
/// on the screen said so.
///
/// IT LIVES HERE RATHER THAN IN ONE SCREEN because two screens now draw these
/// buttons - the merchant card in the console list and the merchant detail
/// screen - and two copies of a copy is how the drift this guards against
/// starts.
class MerchantTransitions {
  const MerchantTransitions._();

  static const Map<String, List<String>> nextFrom = {
    'APPLICATION': ['PENDING_REVIEW', 'REJECTED', 'REMOVED'],
    'PENDING_REVIEW': ['VERIFICATION_REQUIRED', 'APPROVED', 'REJECTED', 'REMOVED'],
    'VERIFICATION_REQUIRED': ['PENDING_REVIEW', 'APPROVED', 'REJECTED', 'REMOVED'],
    'APPROVED': ['ACTIVE', 'PAUSED', 'SUSPENDED', 'REMOVED'],
    'ACTIVE': ['PAUSED', 'SUSPENDED', 'REMOVED'],
    'PAUSED': ['ACTIVE', 'SUSPENDED', 'REMOVED'],
    // Lifting enforcement lets a business trade again; it does not get
    // downgraded to "they are just closed today".
    'SUSPENDED': ['ACTIVE', 'REMOVED'],
    // Terminal. A re-application is a new merchant record, so the first
    // decision and its reason stay readable.
    'REJECTED': <String>[],
    'REMOVED': <String>[],
  };

  /// Empty for a status with nowhere to go, and for one this app has never
  /// heard of - a merchant state added on the server and not here draws no
  /// buttons rather than guessing at them.
  static List<String> from(String? status) =>
      nextFrom[status] ?? const <String>[];

  static String label(String status) => switch (status) {
        'APPROVED' => 'Approve',
        'ACTIVE' => 'Let them trade',
        'SUSPENDED' => 'Suspend',
        // PAUSE AND SUSPEND MUST NOT READ ALIKE. One is a shutter down for a
        // festival; the other is an accusation on a permanent record.
        'PAUSED' => 'Pause trading',
        'REJECTED' => 'Reject',
        'REMOVED' => 'Remove',
        'PENDING_REVIEW' => 'Send for review',
        'VERIFICATION_REQUIRED' => 'Ask for documents',
        _ => status,
      };
}
