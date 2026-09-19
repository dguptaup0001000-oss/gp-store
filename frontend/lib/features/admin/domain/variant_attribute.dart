import 'package:flutter/foundation.dart';

/// One thing that is true about a variant: `RAM = 8 GB`.
///
/// WHY THIS REPLACED "PACK SIZE" AND "UNIT". The variant form asked every
/// trade the grocer's question. A phone merchant was told to type "8" into a
/// box labelled "Pack size" and "8 gb and 128 gb" into one labelled
/// "Unit (kg, g, L...)", because those were the only two fields the model had.
///
/// A name and a value describe a phone, a saree, a shoe, a strip of tablets
/// and a bag of atta equally well, and need no new field for the trade after
/// them. The screen may OFFER a category-shaped set of names to fill in -
/// that is a template, and templates are a convenience on top of this, not a
/// constraint underneath it.
@immutable
class VariantAttribute {
  const VariantAttribute({required this.name, required this.value});

  /// "RAM", "Storage", "Colour", "Size", "Material", "Strength".
  final String name;

  /// "8 GB", "128 GB", "Black", "9", "Silk", "500 mg".
  final String value;

  bool get isEmpty => name.trim().isEmpty || value.trim().isEmpty;

  VariantAttribute copyWith({String? name, String? value}) => VariantAttribute(
        name: name ?? this.name,
        value: value ?? this.value,
      );

  factory VariantAttribute.fromJson(Map<String, dynamic> json) =>
      VariantAttribute(
        name: (json['name'] ?? '').toString(),
        value: (json['value'] ?? '').toString(),
      );

  Map<String, dynamic> toJson() => {'name': name, 'value': value};

  /// How a variant reads on one line: "8 GB · 128 GB · Black".
  static String describe(List<VariantAttribute> attributes) =>
      attributes.where((a) => !a.isEmpty).map((a) => a.value.trim()).join(' · ');

  @override
  bool operator ==(Object other) =>
      other is VariantAttribute && other.name == name && other.value == value;

  @override
  int get hashCode => Object.hash(name, value);
}

/// The attribute names a trade usually wants, offered as a starting point.
///
/// DELIBERATELY NOT A SWITCH THAT BINDS THE SYSTEM TO TODAY'S CATEGORIES.
/// Nothing below is required, nothing is rejected, and a merchant whose trade
/// is not listed simply types their own names - the backend stores whatever
/// arrives. This map only decides which empty rows a form opens with, so a
/// phone shop is not made to invent the word "RAM" from a blank screen.
///
/// Matching is on a lowercased substring of the category name, so "Mobile
/// Phones" and "phone" both find the phone template.
class VariantAttributeTemplates {
  const VariantAttributeTemplates._();

  static const Map<String, List<String>> _byKeyword = {
    'phone': ['RAM', 'Storage', 'Colour'],
    'mobile': ['RAM', 'Storage', 'Colour'],
    'laptop': ['RAM', 'Storage', 'Screen'],
    'electronic': ['Model', 'Colour'],
    'charger': ['Wattage', 'Connector'],
    'saree': ['Colour', 'Material', 'Design'],
    'cloth': ['Size', 'Colour'],
    'shirt': ['Size', 'Colour'],
    'shoe': ['Size', 'Colour'],
    'footwear': ['Size', 'Colour'],
    'medicine': ['Strength', 'Pack'],
    'pharma': ['Strength', 'Pack'],
    'hardware': ['Size', 'Material'],
    'tool': ['Size', 'Material'],
    'automobile': ['Part number', 'Fits'],
    'restaurant': ['Portion'],
    'food': ['Portion'],
    'grocery': ['Pack size', 'Unit'],
    'atta': ['Pack size', 'Unit'],
    'rice': ['Pack size', 'Unit'],
    'dal': ['Pack size', 'Unit'],
    'beverage': ['Volume', 'Unit'],
    'drink': ['Volume', 'Unit'],
  };

  /// A sensible starting set of attribute names for this category, or a
  /// generic pair when nothing matches. Never empty, never authoritative.
  static List<String> forCategory(String? categoryName) {
    final name = (categoryName ?? '').toLowerCase();
    if (name.isNotEmpty) {
      for (final entry in _byKeyword.entries) {
        if (name.contains(entry.key)) return entry.value;
      }
    }
    return const ['Size', 'Colour'];
  }
}
