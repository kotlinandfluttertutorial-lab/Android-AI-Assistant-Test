/// Raw API calls for authentication endpoints.
///
/// Returns typed response objects — the repository handles persistence.
library;

import 'package:ai_assistant_flutter/core/constants/api_config.dart';
import 'package:ai_assistant_flutter/core/error/error_mapper.dart';
import 'package:ai_assistant_flutter/core/utils/result.dart';
import 'package:ai_assistant_flutter/features/auth/domain/auth_models.dart';
import 'package:dio/dio.dart';

class AuthApi {
  const AuthApi(this._dio);
  final Dio _dio;

  Future<Result<LoginResponse>> login(LoginRequest request) async {
    try {
      final response = await _dio.post<Map<String, dynamic>>(
        ApiConfig.authLogin,
        data: request.toJson(),
      );
      return Success(LoginResponse.fromJson(response.data!));
    } catch (e, st) {
      return Failure(ErrorMapper.map(e, st));
    }
  }

  Future<Result<RegisterResponse>> register(RegisterRequest request) async {
    try {
      final response = await _dio.post<Map<String, dynamic>>(
        ApiConfig.authRegister,
        data: request.toJson(),
      );
      return Success(RegisterResponse.fromJson(response.data!));
    } catch (e, st) {
      return Failure(ErrorMapper.map(e, st));
    }
  }

  Future<Result<void>> logout() async {
    try {
      await _dio.post<void>(ApiConfig.authLogout);
      return const Success(null);
    } catch (e, st) {
      return Failure(ErrorMapper.map(e, st));
    }
  }
}
