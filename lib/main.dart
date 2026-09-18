import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:geolocator/geolocator.dart';
import 'package:http/http.dart' as http;
import 'package:shared_preferences/shared_preferences.dart';

void main() {
  runApp(const MyApp());
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Такси Трекер',
      theme: ThemeData(primarySwatch: Colors.blue),
      home: const GPSTrackerScreen(),
    );
  }
}

class GPSTrackerScreen extends StatefulWidget {
  const GPSTrackerScreen({super.key});

  @override
  State<GPSTrackerScreen> createState() => _GPSTrackerScreenState();
}

class _GPSTrackerScreenState extends State<GPSTrackerScreen> {
  final TextEditingController _idController = TextEditingController();
  bool _isTracking = false;
  String _statusText = 'Ожидание запуска...';
  StreamSubscription<Position>? _positionStream;

  // Твой реальный URL сервера
  final String serverUrl = 'https://такси-люкс.рф/update_gps.php';

  @override
  void initState() {
    super.initState();
    _loadSavedId();
  }

  // Загрузка сохраненного ID при запуске
  Future<void> _loadSavedId() async {
    final prefs = await SharedPreferences.getInstance();
    final savedId = prefs.getString('driver_id') ?? '';
    setState(() {      _idController.text = savedId;
    });
  }

  // Сохранение ID при изменении
  Future<void> _saveId(String id) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString('driver_id', id.trim());
  }

  // Проверка и запрос разрешений
  Future<bool> _checkPermissions() async {
    bool serviceEnabled = await Geolocator.isLocationServiceEnabled();
    if (!serviceEnabled) {
      setState(() => _statusText = '❌ Служба геолокации отключена');
      return false;
    }

    LocationPermission permission = await Geolocator.checkPermission();
    if (permission == LocationPermission.denied) {
      permission = await Geolocator.requestPermission();
      if (permission == LocationPermission.denied) {
        setState(() => _statusText = '❌ Разрешение на геолокацию отклонено');
        return false;
      }
    }

    if (permission == LocationPermission.deniedForever) {
      setState(() => _statusText = '❌ Разрешение отклонено навсегда (проверьте настройки)');
      return false;
    }

    return true;
  }

  // Запуск отслеживания
  Future<void> _startTracking() async {
    final driverId = _idController.text.trim();
    if (driverId.isEmpty) {
      setState(() => _statusText = '⚠️ Введите ID машины!');
      return;
    }

    _saveId(driverId);

    final hasPermission = await _checkPermissions();
    if (!hasPermission) return;

    setState(() {
      _isTracking = true;      _statusText = '🛰️ Поиск спутников...';
    });

    // Настройки высокой точности
    const LocationSettings locationSettings = LocationSettings(
      accuracy: LocationAccuracy.best,
      distanceFilter: 0, // Обновлять при любом изменении
    );

    _positionStream = Geolocator.getPositionStream(locationSettings: locationSettings)
        .listen((Position position) {
      _sendLocation(driverId, position);
    });
  }

  // Остановка отслеживания
  void _stopTracking() {
    _positionStream?.cancel();
    _positionStream = null;
    setState(() {
      _isTracking = false;
      _statusText = '⏹️ Передача остановлена';
    });
  }

  // Отправка данных на сервер
  Future<void> _sendLocation(String driverId, Position position) async {
    try {
      final payload = {
        'driver_id': driverId,
        'lat': position.latitude,
        'lon': position.longitude,
        'accuracy': position.accuracy,
        'timestamp': DateTime.now().toIso8601String(),
      };

      final response = await http.post(
        Uri.parse(serverUrl),
        headers: {'Content-Type': 'application/json'},
        body: jsonEncode(payload),
      );

      if (response.statusCode == 200) {
        setState(() {
          _statusText = '✅ Отправлено: ${position.latitude.toStringAsFixed(5)}, ${position.longitude.toStringAsFixed(5)} (±${position.accuracy.toStringAsFixed(0)}м)';
        });
      } else {
        setState(() => _statusText = '❌ Ошибка сервера: ${response.statusCode}');
      }
    } catch (e) {      setState(() => _statusText = '❌ Ошибка сети: $e');
    }
  }

  @override
  void dispose() {
    _positionStream?.cancel();
    _idController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('🚕 Трекер водителя')),
      body: Padding(
        padding: const EdgeInsets.all(24.0),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            TextField(
              controller: _idController,
              onChanged: _saveId,
              decoration: const InputDecoration(
                labelText: 'ID машины / MAX ID',
                border: OutlineInputBorder(),
                prefixIcon: Icon(Icons.directions_car),
              ),
              keyboardType: TextInputType.text,
            ),
            const SizedBox(height: 30),
            SizedBox(
              width: double.infinity,
              height: 60,
              child: ElevatedButton(
                style: ElevatedButton.styleFrom(
                  backgroundColor: _isTracking ? Colors.red : Colors.blue,
                  shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
                ),
                onPressed: _isTracking ? _stopTracking : _startTracking,
                child: Text(
                  _isTracking ? 'Остановить передачу' : 'Начать передачу GPS',
                  style: const TextStyle(fontSize: 20, fontWeight: FontWeight.bold, color: Colors.white),
                ),
              ),
            ),
            const SizedBox(height: 30),
            Text(
              _statusText,
              textAlign: TextAlign.center,              style: TextStyle(
                fontSize: 16,
                color: _statusText.contains('✅') ? Colors.green : (_statusText.contains('❌') ? Colors.red : Colors.grey),
                fontWeight: FontWeight.w500,
              ),
            ),
          ],
        ),
      ),
    );
  }
}
