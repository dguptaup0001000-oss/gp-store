import 'dart:async';

import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart';

/// One place that decides when a search actually reaches the network.
///
/// Before this, three screens - global search, brand browse, category browse
/// - each carried their own Timer and their own 400ms literal. Three copies
/// of a rule is three places for it to drift, and it had already drifted from
/// the interval anyone would have chosen deliberately.
///
/// It does three things that a bare Timer does not:
///
/// 1. ENFORCES A MINIMUM LENGTH. A single character matches a large fraction
///    of any catalogue, so the request costs the backend real work to return
///    something useless. Typing "s" no longer reaches the server at all.
///
/// 2. CANCELS THE SUPERSEDED REQUEST. Debouncing limits how often a request
///    STARTS; it does nothing about one already in flight. Typing "sug" then
///    pausing, then continuing to "sugar", leaves the first search running -
///    the client ignores its answer, but the server has already paid for it.
///    Cancelling means a superseded search stops costing anything.
///
/// 3. Keeps the empty case explicit, so clearing the box restores the
///    previous screen rather than issuing a search for "".
class SearchDebouncer {
  SearchDebouncer({
    this.delay = const Duration(milliseconds: 300),
    this.minLength = 2,
  });

  /// 300ms: long enough that a normal typing burst is one request, short
  /// enough that the pause does not read as lag. Below ~200ms a fast typist
  /// still generates several requests; above ~400ms the wait becomes visible.
  final Duration delay;

  /// Below this, the query is treated as not yet meaningful.
  final int minLength;

  Timer? _timer;
  CancelToken? _inFlight;
  bool _disposed = false;

  /// The token for the current search. Repositories pass it to Dio so the
  /// request can actually be aborted rather than merely ignored.
  CancelToken? get cancelToken => _inFlight;

  /// Call on every keystroke.
  ///
  /// [onSearch] fires once the query has settled and is long enough.
  /// [onCleared] fires immediately when the box is emptied - no delay, since
  /// there is nothing to wait for and the customer expects the results to go.
  void onQueryChanged(
    String raw, {
    required void Function(String query, CancelToken token) onSearch,
    required VoidCallback onCleared,
  }) {
    if (_disposed) return;

    _timer?.cancel();
    _cancelInFlight();

    final query = raw.trim();

    if (query.isEmpty) {
      onCleared();
      return;
    }

    // Too short to mean anything yet. Deliberately silent rather than
    // clearing: the customer is mid-word, and wiping their previous results
    // on the way to a longer query is worse than leaving them.
    if (query.length < minLength) return;

    _timer = Timer(delay, () {
      if (_disposed) return;
      final token = CancelToken();
      _inFlight = token;
      onSearch(query, token);
    });
  }

  /// Runs a search immediately, skipping the debounce - for a submitted query
  /// or a tapped suggestion, where the customer has already decided.
  void searchNow(String raw, {required void Function(String query, CancelToken token) onSearch}) {
    if (_disposed) return;

    _timer?.cancel();
    _cancelInFlight();

    final query = raw.trim();
    if (query.isEmpty) return;

    final token = CancelToken();
    _inFlight = token;
    onSearch(query, token);
  }

  void _cancelInFlight() {
    final token = _inFlight;
    _inFlight = null;
    if (token != null && !token.isCancelled) {
      token.cancel('superseded by a newer search');
    }
  }

  /// Cancels the pending timer and any in-flight request.
  ///
  /// Must be called from the owning State's dispose, or a search fired after
  /// the screen is gone resolves into a disposed widget.
  void dispose() {
    _disposed = true;
    _timer?.cancel();
    _timer = null;
    _cancelInFlight();
  }
}
