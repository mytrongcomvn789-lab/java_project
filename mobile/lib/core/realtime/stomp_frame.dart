/// STOMP 1.2 frame encoding/decoding. Pure Dart so it can be unit tested.
class StompFrame {
  const StompFrame(this.command,
      [this.headers = const <String, String>{}, this.body = '']);
  final String command;
  final Map<String, String> headers;
  final String body;

  /// A bare newline is a heart-beat, not a command frame.
  static const heartbeat = StompFrame('');
  bool get isHeartbeat => command.isEmpty;

  String encode() {
    final out = StringBuffer(command)..write('\n');
    headers.forEach((k, v) => out.write('${_escape(k)}:${_escape(v)}\n'));
    out
      ..write('\n')
      ..write(body)
      ..write('\u0000');
    return out.toString();
  }

  static String _escape(String v) => v
      .replaceAll(r'\', r'\\')
      .replaceAll('\n', r'\n')
      .replaceAll('\r', r'\r')
      .replaceAll(':', r'\c');

  static String _unescape(String v) {
    if (!v.contains(r'\')) return v;
    final out = StringBuffer();
    for (var i = 0; i < v.length; i++) {
      final c = v[i];
      if (c != r'\' || i + 1 >= v.length) {
        out.write(c);
        continue;
      }
      final next = v[++i];
      out.write(switch (next) {
        'n' => '\n',
        'r' => '\r',
        'c' => ':',
        r'\' => r'\',
        _ => '\\$next',
      });
    }
    return out.toString();
  }

  /// Splits one websocket text message into frames. A message may carry
  /// several NUL-terminated frames and bare heart-beat newlines between them.
  static List<StompFrame> parse(String data) {
    final frames = <StompFrame>[];
    var rest = data;
    while (rest.isNotEmpty) {
      final trimmed = rest.replaceFirst(RegExp(r'^[\r\n]+'), '');
      if (trimmed.length != rest.length) frames.add(heartbeat);
      if (trimmed.isEmpty) break;
      final end = trimmed.indexOf('\u0000');
      final raw = end < 0 ? trimmed : trimmed.substring(0, end);
      rest = end < 0 ? '' : trimmed.substring(end + 1);
      final frame = _parseOne(raw);
      if (frame != null) frames.add(frame);
    }
    return frames;
  }

  static StompFrame? _parseOne(String raw) {
    final split = raw.indexOf('\n\n');
    final head = split < 0 ? raw : raw.substring(0, split);
    final body = split < 0 ? '' : raw.substring(split + 2);
    final lines = head.split('\n').map((l) => l.replaceAll('\r', '')).toList();
    final command = lines.first.trim();
    if (command.isEmpty) return null;
    final headers = <String, String>{};
    for (final line in lines.skip(1)) {
      final i = line.indexOf(':');
      if (i <= 0) continue;
      // STOMP: the first occurrence of a repeated header wins.
      headers.putIfAbsent(_unescape(line.substring(0, i)),
          () => _unescape(line.substring(i + 1)));
    }
    return StompFrame(command, headers, body);
  }
}
