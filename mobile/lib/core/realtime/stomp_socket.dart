import 'dart:async';
import 'package:web_socket_channel/web_socket_channel.dart';

/// Minimal text socket the STOMP client needs; faked in tests.
abstract class StompSocket {
  Stream<String> get messages;
  void send(String data);
  Future<void> close();
}

typedef SocketFactory = Future<StompSocket> Function(Uri url);

class ChannelStompSocket implements StompSocket {
  ChannelStompSocket._(this._channel);
  final WebSocketChannel _channel;

  static Future<StompSocket> connect(Uri url) async {
    final channel = WebSocketChannel.connect(url);
    await channel.ready;
    return ChannelStompSocket._(channel);
  }

  @override
  Stream<String> get messages => _channel.stream
      .map((e) => e is String ? e : String.fromCharCodes(e as List<int>));
  @override
  void send(String data) => _channel.sink.add(data);
  @override
  Future<void> close() => _channel.sink.close();
}
