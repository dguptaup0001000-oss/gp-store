import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/core/notifications/voice_announcement_service.dart';

/// The spoken line is the whole feature, and it is the one part that can be
/// tested without a device, a TTS engine, or a running app. buildAnnouncement
/// is pure for exactly that reason.
void main() {
  group('spoken announcement', () {
    test('says the customer name and a whole-rupee amount', () {
      expect(
        VoiceAnnouncementService.buildAnnouncement(
            customerName: 'Deepak', rupees: '520'),
        'New order received from Deepak. Order amount 520 rupees.',
      );
    });

    test('never speaks a currency symbol, a decimal point, or trailing zeros', () {
      final line = VoiceAnnouncementService.buildAnnouncement(
          customerName: 'Rahul', rupees: '350');
      expect(line, isNotNull);
      expect(line, isNot(contains('₹')));
      expect(line, isNot(contains('.00')));
      // The only full stops are the two sentence breaks.
      expect('.'.allMatches(line!).length, 2);
    });

    test('speaks paise as paise rather than a decimal', () {
      expect(
        VoiceAnnouncementService.buildAnnouncement(
            customerName: 'Priya', rupees: '780.50'),
        'New order received from Priya. Order amount 780 rupees 50 paise.',
      );
    });

    test('does not mangle paise through floating point', () {
      // (780.50 - 780) * 100 is 49.999... in binary floating point. Truncating
      // would announce 49 paise for a 50 paise order.
      final line = VoiceAnnouncementService.buildAnnouncement(
          customerName: 'Asha', rupees: '780.50');
      expect(line, contains('50 paise'));
      expect(line, isNot(contains('49 paise')));
    });

    test('uses the singular for exactly one rupee', () {
      expect(
        VoiceAnnouncementService.buildAnnouncement(
            customerName: 'Sunil', rupees: '1'),
        'New order received from Sunil. Order amount 1 rupee.',
      );
    });

    test('omits a zero-rupee prefix for a paise-only amount', () {
      expect(
        VoiceAnnouncementService.buildAnnouncement(
            customerName: 'Meera', rupees: '0.50'),
        'New order received from Meera. Order amount 50 paise.',
      );
    });

    test('carries paise up to a rupee when rounding demands it', () {
      expect(
        VoiceAnnouncementService.buildAnnouncement(
            customerName: 'Vikram', rupees: '99.999'),
        'New order received from Vikram. Order amount 100 rupees.',
      );
    });

    test('handles a large order without grouping artefacts', () {
      expect(
        VoiceAnnouncementService.buildAnnouncement(
            customerName: 'Farhan', rupees: '12500'),
        'New order received from Farhan. Order amount 12500 rupees.',
      );
    });

    test('trims stray whitespace around the name', () {
      expect(
        VoiceAnnouncementService.buildAnnouncement(
            customerName: '  Neha  ', rupees: '200'),
        'New order received from Neha. Order amount 200 rupees.',
      );
    });

    group('stays silent rather than speaking nonsense', () {
      test('when the name is missing', () {
        expect(
          VoiceAnnouncementService.buildAnnouncement(
              customerName: '   ', rupees: '520'),
          isNull,
        );
      });

      test('when the amount is not a number', () {
        // A malformed payload must not be read aloud verbatim.
        for (final bad in ['', 'abc', '₹520', 'null', '{"amount":520}']) {
          expect(
            VoiceAnnouncementService.buildAnnouncement(
                customerName: 'Deepak', rupees: bad),
            isNull,
            reason: 'should stay silent for "$bad"',
          );
        }
      });

      test('when the amount is zero or negative', () {
        expect(
          VoiceAnnouncementService.buildAnnouncement(
              customerName: 'Deepak', rupees: '0'),
          isNull,
        );
        expect(
          VoiceAnnouncementService.buildAnnouncement(
              customerName: 'Deepak', rupees: '-5'),
          isNull,
        );
      });
    });
  });
}
