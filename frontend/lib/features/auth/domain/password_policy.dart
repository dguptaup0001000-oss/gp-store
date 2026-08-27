/// Mirrors backend [PasswordPolicy] for new passwords (register, change, reset).
/// Existing shorter hashes still sign in; the floor applies only when setting
/// a password.
///
/// The blocked-password list is stored as SHA-256 digests so a unzip-the-APK
/// audit does not find the plaintext denylist. Matching is still exact: the
/// typed password is hashed and compared.
library;

import 'dart:convert';

import 'package:crypto/crypto.dart';

class AppPasswordPolicy {
  AppPasswordPolicy._();

  static const minLength = 10;
  static const maxLength = 128;
  static const tooShortMessage = 'Password must be at least 10 characters.';
  static const message =
      'Password must be 10–128 characters and contain at least one letter and one number.';
  static const helperText =
      'At least 10 characters, with a letter and a number.';

  static const _denylistSha256 = {
    '5e884898da28047151d0e56f8dc6292773603d0d6aabbdd62a11ef721d1542d8',
    '0b14d501a594442a01c6859541bcb3e8164d183d32937b851835442f69d5c94e',
    'ef92b778bafe771e89245b89ecbc08a44a4e166c06659911881f383d4473e94f',
    'ef797c8118f02dfb649607dd5d3f8c7623048c9c063d532cc95c5ed7a898a64f',
    '15e2b0d3c33891ebb0f1ef609ec419420c20e320ce94c65fbc8c3312448eb225',
    'c775e7b757ede630cd0aa1113bd102661ab38829ca52a6422ab782862f268646',
    'a17444550e2c127b02ea1c197bcffa422c21713040f53d5c2ca7925419bccf7f',
    'daaad6e5604e8e17bd9f108d91e26afe6281dac8fda0091040a7a6d7bd9b43b5',
    '9c56cc51b374c3ba189210d5b6d4bf57790d351c96c47c02190ecf1e430635ab',
    '2e844ad651c6b9a69cbe8f887b6a74dd3b9fc489aae777701e6e2d0d523a0cfb',
    'fcc3a23fc7232cc89c7cb0f23d8774fefb73d7dc2ab22e6a1b6b8b202b4dcc91',
    '240be518fabd2724ddb6f04eeb1da5967448d7e831c08c8fa822809f74c720a9',
    '107639d0d2d3572454b611c4c585b63f9fa7255e1cc7ddc48e837bee16eebee2',
    'f0a307e11224fdd46920c8fee58151eb8f7f50c4eed045d684ca1eab995332e8',
  };

  static String? validateNewPassword(String? value) {
    if (value == null || value.isEmpty) return 'Password is required';
    if (value.length < minLength) return tooShortMessage;
    if (value.length > maxLength) {
      return 'Password must be at most $maxLength characters.';
    }
    final hasLetter = value.contains(RegExp(r'[A-Za-z]'));
    final hasDigit = value.contains(RegExp(r'[0-9]'));
    if (!hasLetter || !hasDigit) return message;
    final digest = sha256.convert(utf8.encode(value.toLowerCase())).toString();
    if (_denylistSha256.contains(digest)) return message;
    return null;
  }
}
