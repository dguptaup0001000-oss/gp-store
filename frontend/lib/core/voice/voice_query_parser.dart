/// What a customer meant, pulled out of what they said.
///
/// PURE DART, NO PLUGIN, NO NETWORK. Everything here is a string transform,
/// which is what makes the twenty-odd real sentences in the brief testable as
/// unit tests rather than as something only verifiable by speaking into a
/// phone. The speech plugin's only job is to hand this a transcript.
///
/// WHY A CLIENT-SIDE PARSER AT ALL, when the backend already understands
/// Hinglish. The backend's dictionary translates WORDS - "chini" to "sugar" -
/// and its normalizer matches SPELLINGS. Neither converts "paanch kilo" into
/// "5 kg", and that conversion is the difference between a query that can
/// match the catalogue and one that cannot: shops write sizes in digits.
/// Doing it here also costs no round trip and no database row.
///
/// WHAT IT DELIBERATELY DOES NOT DO. It does not decide what a product IS.
/// "chini" stays "chini" and goes to the backend, where the vocabulary that
/// turns it into "sugar" already lives - in a table an admin can add rows to
/// without shipping a new APK. Duplicating that list here would freeze it
/// into the app.
library;

/// One shopping intent. A sentence can carry more than one.
class VoiceIntent {
  const VoiceIntent({
    required this.searchPhrase,
    this.quantity,
    this.size,
    this.sizePreference,
    this.pricePaise,
  });

  /// What to hand the existing search. Filler stripped, sizes in the
  /// catalogue's own spelling.
  final String searchPhrase;

  /// How many the customer asked for - "do packet" is two. Never folded into
  /// [searchPhrase]: a count is not part of any product's name, and leaving
  /// it in makes "2" a search term.
  final int? quantity;

  /// Pack size as the catalogue spells it: "5 kg", "500 g", "1 L".
  final String? size;

  /// "chhota" / "bada" with no number attached.
  ///
  /// Kept as a PREFERENCE rather than pushed into the phrase, because no
  /// grocery product is called "Surf Excel Small" - the catalogue says 500g
  /// and 1kg. Searching for the word would match nothing; ranking real
  /// variants by it is what the customer actually meant.
  final SizePreference? sizePreference;

  /// "das rupaye wala" - a price clue, in paise.
  ///
  /// NOT a quantity, which is the trap the brief calls out: ten rupees of
  /// Lays is one small packet, not ten packets.
  final int? pricePaise;

  bool get isEmpty => searchPhrase.isEmpty;

  @override
  String toString() => 'VoiceIntent($searchPhrase, qty: $quantity, size: $size, '
      'pref: $sizePreference, price: $pricePaise)';
}

enum SizePreference { small, large }

/// Everything understood from one utterance.
class VoiceQuery {
  const VoiceQuery({required this.transcript, required this.intents});

  /// Exactly what the recogniser heard. Shown back to the customer, because a
  /// search that silently corrects itself is one they cannot correct.
  final String transcript;

  final List<VoiceIntent> intents;

  bool get isEmpty => intents.isEmpty;

  /// The one the results screen opens on.
  VoiceIntent? get primary => intents.isEmpty ? null : intents.first;
}

class VoiceQueryParser {
  const VoiceQueryParser._();

  static VoiceQuery parse(String transcript) {
    final cleaned = transcript.trim();
    if (cleaned.isEmpty) {
      return VoiceQuery(transcript: transcript, intents: const []);
    }

    final intents = <VoiceIntent>[];
    for (final fragment in _splitProducts(cleaned)) {
      final intent = _parseFragment(fragment);
      if (!intent.isEmpty) intents.add(intent);
    }

    return VoiceQuery(transcript: cleaned, intents: intents);
  }

  // ---------------------------------------------------------------- splitting

  /// "do kilo chini aur ek litre tel" is two shopping intents, not one query.
  ///
  /// Split on the conjunction only, not on every comma-ish pause: "lal mirch,
  /// haldi" is two, but so is "lal mirch aur haldi", and a customer who says
  /// "surf excel, wala chhota" means one thing. The conjunction is the signal
  /// people actually use when listing.
  static final RegExp _conjunction = RegExp(
    r'\s+(?:aur|और|and)\s+|\s*,\s*',
    caseSensitive: false,
  );

  static List<String> _splitProducts(String input) {
    return input
        .split(_conjunction)
        .map((part) => part.trim())
        .where((part) => part.isNotEmpty)
        .toList();
  }

  // ----------------------------------------------------------------- parsing

  static VoiceIntent _parseFragment(String fragment) {
    final tokens = _tokenize(fragment);

    final kept = <String>[];
    int? quantity;
    String? size;
    SizePreference? preference;
    int? pricePaise;

    for (var i = 0; i < tokens.length; i++) {
      final token = tokens[i];

      // A size preference with no number - "chhota wala", "bada packet".
      final pref = _sizePreferences[token];
      if (pref != null) {
        preference ??= pref;
        continue;
      }

      if (_filler.contains(token)) continue;

      final value = _numberAt(tokens, i);
      if (value == null) {
        kept.add(token);
        continue;
      }

      // What follows the number decides what the number MEANS. This is the
      // whole of rule 10: five kilos is a size, two packets is a count, and
      // ten rupees is neither.
      final next = value.nextIndex < tokens.length ? tokens[value.nextIndex] : null;

      if (next != null && _priceWords.contains(next)) {
        pricePaise ??= (value.amount * 100).round();
        i = value.nextIndex;
        continue;
      }

      final unit = next == null ? null : _measureUnits[next];
      if (unit != null) {
        size ??= _formatSize(value.amount, unit);
        i = value.nextIndex;
        continue;
      }

      if (next != null && _countWords.contains(next)) {
        quantity ??= value.amount.round();
        i = value.nextIndex;
        continue;
      }

      // A bare number before a product word is a count: "2 maggi", "do maggi".
      //
      // Capped, because a bare number is the weakest evidence here and a
      // recogniser mishearing something as "1998" must not put 1998 packets
      // in front of somebody. Above the cap the number is far likelier to be
      // part of a name or a mishearing, so it stays a search term. The cap
      // applies ONLY to this branch - "1000 gram" is a real pack size and
      // goes down the unit path above, where no cap applies.
      if (next != null && value.amount <= _maxSpokenCount) {
        quantity ??= value.amount.round();
        i = value.nextIndex - 1;
        continue;
      }

      // A bare number with nothing after it is more likely a size the
      // customer trailed off on than a count, so it is kept as a search term
      // rather than guessed at.

      kept.add(_trimNumber(value.amount));
    }

    final phrase = <String>[...kept];
    // The size joins the search terms, because shops DO put it in the product
    // name - "Aashirvaad Atta 5 kg". The quantity never does.
    if (size != null) phrase.add(size);

    return VoiceIntent(
      searchPhrase: phrase.join(' ').trim(),
      quantity: quantity,
      size: size,
      sizePreference: preference,
      pricePaise: pricePaise,
    );
  }

  /// Splits on whitespace and on the digit/letter boundary, so "5kg",
  /// "500ml" and "2l" become two tokens each and go down the same path as
  /// "5 kg". Customers dictate both ways and recognisers transcribe both ways.
  static List<String> _tokenize(String fragment) {
    final spaced = fragment
        .toLowerCase()
        .replaceAllMapped(RegExp(r'(\d)([a-zऀ-ॿ])'), (m) => '${m[1]} ${m[2]}')
        .replaceAllMapped(RegExp(r'([a-zऀ-ॿ])(\d)'), (m) => '${m[1]} ${m[2]}');

    return spaced
        // The dot survives the split so "2.5" stays one token, then is
        // trimmed off the ends so a sentence-final "milk." is still "milk".
        .split(RegExp(r'[^\wऀ-ॿ.]+'))
        .map((t) => t.replaceAll(RegExp(r'^\.+|\.+$'), '').trim())
        .where((t) => t.isNotEmpty)
        .toList();
  }

  // ----------------------------------------------------------------- numbers

  /// A number found at [index], and where reading it finished.
  static _Number? _numberAt(List<String> tokens, int index) {
    final token = tokens[index];

    // "sawa do" is 2.25, "paune teen" is 2.75 - the modifier belongs to the
    // number AFTER it, so it has to look ahead before deciding anything.
    final modifier = _fractionModifiers[token];
    if (modifier != null) {
      final following = index + 1 < tokens.length ? _simpleNumber(tokens[index + 1]) : null;
      // "sawa" alone means nothing useful. Treated as filler rather than
      // guessed at, because guessing here silently changes an order.
      if (following == null) return null;
      return _Number(following + modifier, index + 2);
    }

    final standalone = _standaloneFractions[token];
    if (standalone != null) return _Number(standalone, index + 1);

    final simple = _simpleNumber(token);
    if (simple == null) return null;
    return _Number(simple, index + 1);
  }

  /// The most of one thing a person plausibly orders by voice in a kirana
  /// shop. Above this, a bare number is noise rather than a count.
  static const int _maxSpokenCount = 99;

  static double? _simpleNumber(String token) {
    final word = _numberWords[token];
    if (word != null) return word;

    final digits = double.tryParse(token);
    // A four-digit number in a grocery sentence is a price or a year, not a
    // count of anything - "1000 gram" is real, "1998 maggi" is not.
    if (digits == null || digits <= 0 || digits > 5000) return null;
    return digits;
  }

  static String _formatSize(double amount, String unit) => '${_trimNumber(amount)} $unit';

  /// 5.0 prints as "5", 2.5 prints as "2.5" - the catalogue says "5 kg", not
  /// "5.0 kg", and a trailing zero is a term that matches nothing.
  static String _trimNumber(double value) {
    if (value == value.roundToDouble()) return value.round().toString();
    return value.toString();
  }

  // ------------------------------------------------------------- vocabularies
  //
  // Deliberately small and additive. Every entry here is about NUMBERS,
  // UNITS or GRAMMAR - none of it is about what a product is. Product
  // vocabulary lives in the backend's search_synonyms table, where an admin
  // can add a word without shipping an app update.

  static const Map<String, double> _numberWords = {
    'ek': 1, 'एक': 1, 'one': 1,
    'do': 2, 'दो': 2, 'two': 2,
    'teen': 3, 'tin': 3, 'तीन': 3, 'three': 3,
    'char': 4, 'chaar': 4, 'चार': 4, 'four': 4,
    'panch': 5, 'paanch': 5, 'pach': 5, 'पांच': 5, 'पाँच': 5, 'five': 5,
    'chhe': 6, 'che': 6, 'chah': 6, 'chhah': 6, 'छह': 6, 'छे': 6, 'six': 6,
    'saat': 7, 'sat': 7, 'सात': 7, 'seven': 7,
    'aath': 8, 'ath': 8, 'आठ': 8, 'eight': 8,
    'nau': 9, 'नौ': 9, 'nine': 9,
    'das': 10, 'dus': 10, 'दस': 10, 'ten': 10,
    'gyarah': 11, 'ग्यारह': 11,
    'barah': 12, 'बारह': 12, 'dozen': 12,
    'pandrah': 15, 'पंद्रह': 15,
    'bees': 20, 'बीस': 20,
    'pachas': 50, 'पचास': 50,
    'sau': 100, 'सौ': 100,
  };

  /// Standalone fractions - these ARE the number, not a modifier of one.
  static const Map<String, double> _standaloneFractions = {
    'aadha': 0.5, 'adha': 0.5, 'aadhaa': 0.5, 'आधा': 0.5, 'half': 0.5,
    'paav': 0.25, 'pav': 0.25, 'पाव': 0.25,
    'dedh': 1.5, 'derh': 1.5, 'डेढ़': 1.5, 'डेढ': 1.5,
    'dhai': 2.5, 'dhaai': 2.5, 'ढाई': 2.5,
  };

  /// Modifiers that adjust the number that FOLLOWS them.
  static const Map<String, double> _fractionModifiers = {
    'sawa': 0.25, 'sava': 0.25, 'सवा': 0.25,
    'paune': -0.25, 'pone': -0.25, 'पौने': -0.25,
  };

  /// Units of MEASURE - a number in front of one of these is a pack size.
  /// Values are the catalogue's own spelling.
  static const Map<String, String> _measureUnits = {
    'kg': 'kg', 'kgs': 'kg', 'kilo': 'kg', 'kilos': 'kg', 'kilogram': 'kg',
    'kilograms': 'kg', 'kilogramme': 'kg', 'किलो': 'kg', 'किलोग्राम': 'kg',
    'g': 'g', 'gm': 'g', 'gms': 'g', 'gram': 'g', 'grams': 'g',
    'gramme': 'g', 'ग्राम': 'g',
    'l': 'L', 'ltr': 'L', 'ltrs': 'L', 'litre': 'L', 'litres': 'L',
    'liter': 'L', 'liters': 'L', 'लीटर': 'L',
    'ml': 'ml', 'millilitre': 'ml', 'मिली': 'ml', 'मिलीलीटर': 'ml',
  };

  /// Units of COUNT - a number in front of one of these is how many to buy.
  static const Set<String> _countWords = {
    'packet', 'packets', 'paket', 'pack', 'packs', 'पैकेट',
    'bottle', 'bottles', 'botal', 'बोतल',
    'dabba', 'dabbe', 'डिब्बा', 'box', 'boxes',
    'piece', 'pieces', 'pcs', 'pc', 'adad', 'नग',
    // "tin" is also three ("teen"). Both readings work because a count word
    // is only ever consulted for the token AFTER a number: "do tin" is two
    // tins, "tin packet" is three packets. A bare "tin" reads as the number,
    // which is the commoner intent in a spoken order.
    'tin', 'tins', 'can', 'cans',
    'pouch', 'pouches', 'सैशे', 'sachet', 'sachets',
  };

  static const Set<String> _priceWords = {
    'rupaye', 'rupay', 'rupaiya', 'rupee', 'rupees', 'rs', 'रुपये', 'रुपए', 'रूपये',
  };

  static const Map<String, SizePreference> _sizePreferences = {
    'chhota': SizePreference.small, 'chota': SizePreference.small,
    'chhoti': SizePreference.small, 'choti': SizePreference.small,
    'छोटा': SizePreference.small, 'छोटी': SizePreference.small,
    'small': SizePreference.small, 'chotu': SizePreference.small,
    'bada': SizePreference.large, 'bara': SizePreference.large,
    'badi': SizePreference.large, 'bari': SizePreference.large,
    'बड़ा': SizePreference.large, 'बड़ी': SizePreference.large,
    'large': SizePreference.large, 'big': SizePreference.large,
  };

  /// Grammar and politeness. Nothing here may be a grocery word.
  ///
  /// THE RULE THIS LIST LIVES BY: when in doubt, keep the token. A word left
  /// in costs one weak search term that the backend's layered retry drops
  /// anyway; a grocery word stripped by mistake costs the customer their
  /// product. "aata" is not in this list and must never be - it is flour, not
  /// the verb. Nor is "tin", which is three.
  static const Set<String> _filler = {
    'bhaiya', 'bhaiyya', 'bhaiyaa', 'bhayya', 'bhai', 'boss', 'ji',
    'mujhe', 'muje', 'hame', 'humko', 'mujhko',
    'chahiye', 'chaiye', 'dedo', 'dena', 'de',
    'please', 'zara', 'jara',
    'ka', 'ki', 'ke', 'ko', 'se', 'mein', 'ne', 'par',
    'wala', 'waala', 'wali', 'waali', 'wale', 'waale',
    'का', 'की', 'के', 'को', 'में', 'वाला', 'वाली', 'वाले',
    'wo', 'woh', 'ye', 'yeh', 'jo', 'us', 'uska', 'iska',
    'वो', 'ये', 'जो',
    'hai', 'hain', 'ha', 'haan',
    'the', 'a', 'an', 'of', 'for', 'give', 'me', 'want', 'need',
    'search', 'find', 'dhundo', 'dikhao', 'batao',
  };
}

class _Number {
  const _Number(this.amount, this.nextIndex);

  final double amount;

  /// Index of the token AFTER the number - which may be a unit, a count word
  /// or the product itself.
  final int nextIndex;
}
