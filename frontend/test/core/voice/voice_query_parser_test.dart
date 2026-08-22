import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/core/voice/voice_query_parser.dart';

/// The sentences a customer actually says.
///
/// Every case in this file is a real spoken order, not a synthetic string.
/// The parser's whole job is to survive them, so they are the specification:
/// if one of these regresses, somebody standing in a shop stops being able to
/// buy something.
///
/// WHAT IS ASSERTED, AND WHAT DELIBERATELY IS NOT. These pin the STRUCTURE
/// pulled out of speech - the quantity, the pack size, the search phrase -
/// never which product comes back. Which product comes back depends on the
/// catalogue, and a test that asserted "Aashirvaad Select Sharbati Atta"
/// would fail the day the shop renames it, without anything being broken.
void main() {
  VoiceIntent only(String said) {
    final query = VoiceQueryParser.parse(said);
    expect(query.intents, hasLength(1), reason: '"$said" is one shopping intent');
    return query.intents.single;
  }

  /// Phrase words, order-independent - the phrase is handed to a search that
  /// tokenises it anyway, so asserting exact word order would pin something
  /// the feature does not depend on.
  Matcher phraseHas(List<String> words) => predicate<VoiceIntent>(
        (intent) {
          final tokens = intent.searchPhrase.split(' ').where((t) => t.isNotEmpty).toSet();
          return words.every(tokens.contains);
        },
        'a search phrase containing ${words.join(', ')}',
      );

  Matcher phraseLacks(List<String> words) => predicate<VoiceIntent>(
        (intent) {
          final tokens = intent.searchPhrase.split(' ').where((t) => t.isNotEmpty).toSet();
          return words.every((w) => !tokens.contains(w));
        },
        'a search phrase without ${words.join(', ')}',
      );

  group('brand, product and pack size', () {
    test('1. "aashirwad ka atta paanch kilo"', () {
      final intent = only('aashirwad ka atta paanch kilo');

      expect(intent.size, '5 kg');
      expect(intent, phraseHas(['aashirwad', 'atta']));
      expect(intent, phraseLacks(['ka', 'paanch', 'kilo']),
          reason: 'the catalogue spells it "5 kg" - the spoken words match nothing');
    });

    test('2. the same sentence in Devanagari', () {
      final intent = only('आशीर्वाद का आटा पांच किलो');

      expect(intent.size, '5 kg',
          reason: 'a Hindi-keyboard speaker gets the same pack size as a Hinglish one');
      expect(intent, phraseHas(['आशीर्वाद', 'आटा']));
      expect(intent, phraseLacks(['का']));
    });

    test('"aashirvaad atta 5 kilo" - digits spoken instead of words', () {
      final intent = only('aashirvaad atta 5 kilo');
      expect(intent.size, '5 kg');
    });

    test('"aashirvaad atta 5kg" - recogniser glued the unit on', () {
      final intent = only('aashirvaad atta 5kg');
      expect(intent.size, '5 kg', reason: '"5kg" and "5 kg" are the same order');
    });

    test('4. "parle ji ka bada packet"', () {
      final intent = only('parle ji ka bada packet');

      expect(intent.sizePreference, SizePreference.large);
      expect(intent, phraseHas(['parle']));
      expect(intent.quantity, isNull,
          reason: '"bada packet" describes the pack, it does not ask for one packet');
    });

    test('5. "amul ka doodh ek litre"', () {
      final intent = only('amul ka doodh ek litre');

      expect(intent.size, '1 L');
      expect(intent, phraseHas(['amul', 'doodh']));
    });

    test('12. "maggi ka chhota packet"', () {
      final intent = only('maggi ka chhota packet');

      expect(intent.sizePreference, SizePreference.small);
      expect(intent, phraseHas(['maggi']));
    });

    test('14. "colgate ka bada wala"', () {
      final intent = only('colgate ka bada wala');

      expect(intent.sizePreference, SizePreference.large);
      expect(intent.searchPhrase, 'colgate');
    });
  });

  group('counts are not sizes and sizes are not counts', () {
    test('6. "do packet parle ji" is two packets', () {
      final intent = only('do packet parle ji');

      expect(intent.quantity, 2);
      expect(intent.size, isNull);
      expect(intent, phraseHas(['parle']));
      expect(intent, phraseLacks(['do', '2', 'packet']),
          reason: 'a count is never part of a product name');
    });

    test('7. "do kilo chini" is a two-kilo pack, not two of them', () {
      final intent = only('do kilo chini');

      expect(intent.size, '2 kg');
      expect(intent.quantity, isNull);
      expect(intent, phraseHas(['chini']));
    });

    test('8. "ek litre tel"', () {
      final intent = only('ek litre tel');

      expect(intent.size, '1 L');
      expect(intent, phraseHas(['tel']));
    });

    test('23. "2 maggi" is a bare count in front of a product', () {
      final intent = only('2 maggi');

      expect(intent.quantity, 2);
      expect(intent.searchPhrase, 'maggi');
    });

    test('"do maggi" - the same, spoken', () {
      final intent = only('do maggi');
      expect(intent.quantity, 2);
      expect(intent.searchPhrase, 'maggi');
    });

    test('11. "bhaiya das rupaye wala lays" is a PRICE, not ten packets', () {
      final intent = only('bhaiya das rupaye wala lays');

      expect(intent.quantity, isNull,
          reason: 'ten rupees of Lays is one small packet - this is the trap');
      expect(intent.pricePaise, 1000);
      expect(intent.searchPhrase, 'lays');
    });

    test('"10 rupaye wala biscuit" - the same in digits', () {
      final intent = only('10 rupaye wala biscuit');

      expect(intent.quantity, isNull);
      expect(intent.pricePaise, 1000);
      expect(intent.searchPhrase, 'biscuit');
    });
  });

  group('Hindi fractions', () {
    test('"aadha kilo chini" is half a kilo', () {
      expect(only('aadha kilo chini').size, '0.5 kg');
    });

    test('"dhai kilo aata" is two and a half', () {
      expect(only('dhai kilo aata').size, '2.5 kg');
    });

    test('"dedh kilo" is one and a half', () {
      expect(only('dedh kilo chawal').size, '1.5 kg');
    });

    test('"sawa do kilo" adds a quarter to the number that follows', () {
      expect(only('sawa do kilo chini').size, '2.25 kg');
    });

    test('"paune do kilo" takes a quarter off it', () {
      expect(only('paune do kilo chini').size, '1.75 kg');
    });

    test('a modifier with no number after it is not guessed at', () {
      // "sawa" alone means nothing. Inventing 1.25 here would silently change
      // what somebody ordered.
      final intent = only('sawa chini');
      expect(intent.size, isNull);
      expect(intent, phraseHas(['chini']));
    });

    test('a whole number prints without a trailing zero', () {
      expect(only('paanch kilo atta').size, '5 kg',
          reason: '"5.0 kg" is a search term that matches nothing');
    });
  });

  group('multiple products in one breath', () {
    test('15. "do kilo chini aur ek litre tel"', () {
      final query = VoiceQueryParser.parse('do kilo chini aur ek litre tel');

      expect(query.intents, hasLength(2));
      expect(query.intents[0].size, '2 kg');
      expect(query.intents[0], phraseHas(['chini']));
      expect(query.intents[1].size, '1 L');
      expect(query.intents[1], phraseHas(['tel']));
    });

    test('"chini do kilo aur tel ek litre" - the other word order', () {
      final query = VoiceQueryParser.parse('chini do kilo aur tel ek litre');

      expect(query.intents, hasLength(2));
      expect(query.intents[0].size, '2 kg');
      expect(query.intents[1].size, '1 L');
    });

    test('Devanagari "और" splits too', () {
      final query = VoiceQueryParser.parse('दो किलो चीनी और एक लीटर तेल');
      expect(query.intents, hasLength(2));
    });

    test('a comma lists products as well', () {
      final query = VoiceQueryParser.parse('maggi, bread, doodh');
      expect(query.intents, hasLength(3));
    });

    test('"aur" inside a word does not split it', () {
      // The word-boundary requirement. Without it, any product whose name
      // contains those three letters would be torn in half.
      final query = VoiceQueryParser.parse('aurangabad chana');
      expect(query.intents, hasLength(1));
    });
  });

  group('descriptions instead of names', () {
    test('3. "surf ka chota wala" keeps the brand and the size hint', () {
      final intent = only('surf ka chota wala');

      expect(intent.searchPhrase, 'surf');
      expect(intent.sizePreference, SizePreference.small);
    });

    test('9. "lal wala tel" keeps the colour as a search term', () {
      // The catalogue has no colour field, so "lal" can only match if it is
      // in the product's own name or keywords. Keeping it is the honest
      // move - dropping it would silently discard what the customer said.
      final intent = only('lal wala tel');

      expect(intent, phraseHas(['lal', 'tel']));
      expect(intent, phraseLacks(['wala']));
    });

    test('10. "green packet wala biscuit"', () {
      final intent = only('green packet wala biscuit');

      expect(intent, phraseHas(['green', 'biscuit']));
      expect(intent, phraseLacks(['wala']));
    });

    test('13. "lux wala sabun"', () {
      final intent = only('lux wala sabun');
      expect(intent.searchPhrase, 'lux sabun');
    });

    test('"bhaiya wo jo red packet mein aata hai" keeps aata', () {
      // The one that would be easy to get catastrophically wrong: "aata hai"
      // is a verb, but "aata" is flour. The filler list must never contain a
      // grocery word.
      final intent = only('bhaiya wo jo red packet mein aata hai');

      expect(intent, phraseHas(['aata']),
          reason: 'aata is flour - stripping it as a verb loses the product');
      expect(intent, phraseLacks(['bhaiya', 'wo', 'jo', 'mein', 'hai']));
    });
  });

  group('short and degenerate input', () {
    test('20. a single word is a search on its own', () {
      expect(only('milk').searchPhrase, 'milk');
    });

    test('21. a brand on its own', () {
      expect(only('Amul').searchPhrase, 'amul');
    });

    test('22. a category on its own', () {
      expect(only('biscuit').searchPhrase, 'biscuit');
    });

    test('16. an utterance of pure filler produces no intent, not a bad one', () {
      // The alternative is searching for "" and showing the customer an empty
      // results screen as though their product did not exist.
      final query = VoiceQueryParser.parse('bhaiya wo ka ki');
      expect(query.isEmpty, isTrue);
      expect(query.primary, isNull);
    });

    test('empty and whitespace transcripts are handled, not thrown on', () {
      expect(VoiceQueryParser.parse('').isEmpty, isTrue);
      expect(VoiceQueryParser.parse('   ').isEmpty, isTrue);
    });

    test('the transcript is always preserved for the customer to see', () {
      final query = VoiceQueryParser.parse('  do kilo chini  ');
      expect(query.transcript, 'do kilo chini',
          reason: 'a search that silently rewrites itself is one nobody can correct');
    });

    test('a sentence-final full stop does not become part of a word', () {
      expect(only('milk.').searchPhrase, 'milk');
    });

    test('an implausibly large number is treated as a word, not a count', () {
      // A recogniser mishearing something as "1998" must not order 1998 of it.
      final intent = only('1998 maggi');
      expect(intent.quantity, isNull);
    });

    test('18. mixed Hindi and English in one sentence', () {
      final query = VoiceQueryParser.parse('do packet bread aur ek kilo चीनी');

      expect(query.intents, hasLength(2));
      expect(query.intents[0].quantity, 2);
      expect(query.intents[1].size, '1 kg');
    });

    test('24. "पाँच किलो आटा"', () {
      final intent = only('पाँच किलो आटा');

      expect(intent.size, '5 kg');
      expect(intent, phraseHas(['आटा']));
    });
  });
}
