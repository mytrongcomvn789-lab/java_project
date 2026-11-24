import 'order_models.dart';

enum EventOutcome {
  /// Next version in sequence: applied to the timeline.
  applied,

  /// Same eventId or a version already shown: dropped.
  duplicate,

  /// Older than the current version: dropped.
  stale,

  /// A version is missing in between: caller must reload from REST.
  gap,

  /// Belongs to another order: dropped.
  foreign,
}

class TrackingState {
  const TrackingState(
      {required this.orderId,
      this.loaded = false,
      this.version = 0,
      this.status = '',
      this.statusLabel = '',
      this.terminal = false,
      this.timeline = const [],
      this.pickup,
      this.dropoff,
      this.error,
      this.refreshing = false,
      this.live = false,
      this.denied = false,
      this.duplicates = 0,
      this.stale = 0,
      this.gaps = 0,
      this.catchUps = 0});
  final String orderId;
  final bool loaded, terminal, refreshing, denied;

  /// Socket subscribed and confirmed for this order.
  final bool live;
  final int version;
  final String status, statusLabel;
  final List<TimelineEntry> timeline;
  final StationPoint? pickup, dropoff;
  final String? error;
  // Counters shown only in diagnostics.
  final int duplicates, stale, gaps, catchUps;

  TrackingState copyWith(
          {bool? loaded,
          int? version,
          String? status,
          String? statusLabel,
          bool? terminal,
          List<TimelineEntry>? timeline,
          StationPoint? pickup,
          StationPoint? dropoff,
          Object? error = _keep,
          bool? refreshing,
          bool? live,
          bool? denied,
          int? duplicates,
          int? stale,
          int? gaps,
          int? catchUps}) =>
      TrackingState(
          orderId: orderId,
          loaded: loaded ?? this.loaded,
          version: version ?? this.version,
          status: status ?? this.status,
          statusLabel: statusLabel ?? this.statusLabel,
          terminal: terminal ?? this.terminal,
          timeline: timeline ?? this.timeline,
          pickup: pickup ?? this.pickup,
          dropoff: dropoff ?? this.dropoff,
          error: identical(error, _keep) ? this.error : error as String?,
          refreshing: refreshing ?? this.refreshing,
          live: live ?? this.live,
          denied: denied ?? this.denied,
          duplicates: duplicates ?? this.duplicates,
          stale: stale ?? this.stale,
          gaps: gaps ?? this.gaps,
          catchUps: catchUps ?? this.catchUps);
  static const _keep = Object();
}

/// Applies one realtime event. Pure: no I/O, so ordering rules are unit-tested.
({TrackingState state, EventOutcome outcome}) applyRealtimeEvent(
    TrackingState state, OrderRealtimeEvent event) {
  if (event.orderId != state.orderId) {
    return (state: state, outcome: EventOutcome.foreign);
  }
  if (!state.loaded) {
    // Nothing to order against yet; the pending REST load will include it.
    return (state: state, outcome: EventOutcome.gap);
  }
  if (state.timeline.any((e) => e.eventId == event.eventId)) {
    return (
      state: state.copyWith(duplicates: state.duplicates + 1),
      outcome: EventOutcome.duplicate
    );
  }
  if (event.version <= state.version) {
    return (
      state: state.copyWith(stale: state.stale + 1),
      outcome: EventOutcome.stale
    );
  }
  if (event.version > state.version + 1) {
    return (
      state: state.copyWith(gaps: state.gaps + 1),
      outcome: EventOutcome.gap
    );
  }
  return (
    state: state.copyWith(
        version: event.version,
        status: event.status,
        statusLabel: labelOfStatus(event.status),
        terminal: terminalStatuses.contains(event.status),
        timeline: [...state.timeline, TimelineEntry.fromEvent(event)],
        error: null),
    outcome: EventOutcome.applied
  );
}

/// Merges a REST snapshot. REST wins per version, but a snapshot older than
/// what realtime already showed never moves the screen backwards.
TrackingState mergeSnapshot(TrackingState state, TrackingSnapshot rest) {
  final byVersion = <int, TimelineEntry>{
    for (final e in state.timeline) e.version: e,
    for (final e in rest.timeline) e.version: e,
  };
  final timeline = byVersion.values.toList()
    ..sort((a, b) => a.version.compareTo(b.version));
  final restIsNewer = rest.version >= state.version;
  return state.copyWith(
      loaded: true,
      timeline: timeline,
      version: restIsNewer ? rest.version : state.version,
      status: restIsNewer ? rest.status : state.status,
      statusLabel: restIsNewer ? rest.statusLabel : state.statusLabel,
      terminal: restIsNewer ? rest.terminal : state.terminal,
      pickup: rest.pickup ?? state.pickup,
      dropoff: rest.dropoff ?? state.dropoff,
      error: null,
      refreshing: false);
}
