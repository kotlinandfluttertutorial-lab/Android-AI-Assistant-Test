/// WebSocket service that wraps web_socket_channel.
///
/// Handles:
///   - JWT authentication via ?token= query param (backend requirement)
///   - Heartbeat ping/pong
///   - Reconnection with exponential back-off
///   - Typed incoming message model [WsMessage]
///   - Clean shutdown
library;

import 'dart:async';
import 'dart:convert';

import 'package:ai_assistant_flutter/core/constants/api_config.dart';
import 'package:ai_assistant_flutter/core/constants/app_constants.dart';
import 'package:ai_assistant_flutter/core/utils/app_logger.dart';
import 'package:web_socket_channel/web_socket_channel.dart';

/// Types emitted by the server over the WebSocket connection.
enum WsMessageType {
  token,
  done,
  error,
  toolCall,
  ping,
  unknown;

  static WsMessageType fromString(String? value) => switch (value) {
        'token' => WsMessageType.token,
        'done' => WsMessageType.done,
        'error' => WsMessageType.error,
        'tool_call' => WsMessageType.toolCall,
        'ping' => WsMessageType.ping,
        _ => WsMessageType.unknown,
      };
}

/// Strongly-typed server message.
class WsMessage {
  const WsMessage({
    required this.type,
    this.data,
    this.usage,
    this.message,
    this.toolName,
    this.toolInput,
  });

  factory WsMessage.fromJson(Map<String, dynamic> json) {
    return WsMessage(
      type: WsMessageType.fromString(json['type'] as String?),
      data: json['data'] as String?,
      usage: json['usage'] as Map<String, dynamic>?,
      message: json['message'] as String?,
      toolName: json['toolName'] as String?,
      toolInput: json['toolInput'] as Map<String, dynamic>?,
    );
  }

  final WsMessageType type;
  final String? data;               // token chunk
  final Map<String, dynamic>? usage; // done payload
  final String? message;            // error message
  final String? toolName;
  final Map<String, dynamic>? toolInput;
}

/// Connection state of the WebSocket.
enum WsConnectionState {
  disconnected,
  connecting,
  connected,
  reconnecting,
}

/// Manages a single WebSocket connection to /ws/chat/{conversationId}.
class WebSocketService {
  WebSocketService();

  WebSocketChannel? _channel;
  StreamController<WsMessage>? _controller;
  Timer? _reconnectTimer;
  int _reconnectAttempts = 0;
  bool _intentionallyClosed = false;

  String? _conversationId;
  String? _token;

  WsConnectionState _state = WsConnectionState.disconnected;
  WsConnectionState get state => _state;

  /// Stream of typed messages from the server.
  Stream<WsMessage>? get messages => _controller?.stream;

  /// Connect to /ws/chat/{conversationId}?token=<jwt>.
  Future<void> connect({
    required String conversationId,
    required String token,
  }) async {
    _conversationId = conversationId;
    _token = token;
    _intentionallyClosed = false;
    _reconnectAttempts = 0;
    await _connect();
  }

  Future<void> _connect() async {
    _setState(WsConnectionState.connecting);
    _controller ??= StreamController<WsMessage>.broadcast();

    final uri = Uri.parse(
      '${ApiConfig.wsBaseUrl}${ApiConfig.wsChatPath(_conversationId!)}?token=$_token',
    );

    AppLogger.i('WebSocket: connecting to $uri');

    try {
      _channel = WebSocketChannel.connect(uri);
      await _channel!.ready;
      _setState(WsConnectionState.connected);
      _reconnectAttempts = 0;
      AppLogger.i('WebSocket: connected (conversation=$_conversationId)');

      _channel!.stream.listen(
        _onData,
        onError: _onError,
        onDone: _onDone,
        cancelOnError: false,
      );
    } catch (e) {
      AppLogger.w('WebSocket: connection failed', e);
      _scheduleReconnect();
    }
  }

  void _onData(dynamic raw) {
    try {
      final json = jsonDecode(raw as String) as Map<String, dynamic>;
      final msg = WsMessage.fromJson(json);

      // Respond to server pings immediately.
      if (msg.type == WsMessageType.ping) {
        _channel?.sink.add(jsonEncode({'type': 'pong'}));
        return;
      }

      _controller?.add(msg);
    } catch (e) {
      AppLogger.w('WebSocket: failed to parse message: $raw', e);
    }
  }

  void _onError(Object error) {
    AppLogger.w('WebSocket: stream error', error);
    _scheduleReconnect();
  }

  void _onDone() {
    AppLogger.i('WebSocket: stream closed (intentional=$_intentionallyClosed)');
    if (!_intentionallyClosed) _scheduleReconnect();
  }

  void _scheduleReconnect() {
    _setState(WsConnectionState.reconnecting);
    if (_intentionallyClosed) return;
    if (_reconnectAttempts >= AppConstants.wsMaxReconnectAttempts) {
      AppLogger.w('WebSocket: max reconnect attempts reached — giving up');
      _setState(WsConnectionState.disconnected);
      _controller?.add(const WsMessage(
        type: WsMessageType.error,
        message: 'Connection lost. Please try again.',
      ));
      return;
    }

    final delay = Duration(
      seconds: AppConstants.wsReconnectDelay.inSeconds *
          (1 << _reconnectAttempts).clamp(1, 16),
    );
    _reconnectAttempts++;
    AppLogger.i(
      'WebSocket: reconnecting in ${delay.inSeconds}s '
      '(attempt $_reconnectAttempts/${AppConstants.wsMaxReconnectAttempts})',
    );

    _reconnectTimer?.cancel();
    _reconnectTimer = Timer(delay, _connect);
  }

  /// Send a user message to the server.
  void sendMessage(String message, {String? provider}) {
    if (_state != WsConnectionState.connected) {
      AppLogger.w('WebSocket: tried to send while not connected');
      return;
    }
    _channel?.sink.add(jsonEncode({
      'user_message': message,
      if (provider != null) 'provider': provider,
    }));
  }

  void _setState(WsConnectionState s) {
    _state = s;
  }

  /// Close the connection cleanly.
  Future<void> disconnect() async {
    _intentionallyClosed = true;
    _reconnectTimer?.cancel();
    await _channel?.sink.close();
    await _controller?.close();
    _channel = null;
    _controller = null;
    _setState(WsConnectionState.disconnected);
    AppLogger.i('WebSocket: disconnected (intentional)');
  }
}
