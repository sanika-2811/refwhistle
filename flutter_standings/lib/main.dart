import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:http/http.dart' as http;

/// Live standings dashboard for spectators.
///
/// The Android app publishes the league table to Firestore at
/// tournaments/{code}/standings/latest whenever an admin taps "Upload my log to cloud".
/// This app reads that one document through the Firestore REST API (no login needed, because the
/// security rules allow public read of the standings document only) and refreshes every 10 s.
const projectId = 'refwhistle-a0303';

void main() => runApp(const StandingsApp());

class StandingsApp extends StatelessWidget {
  const StandingsApp({super.key});

  @override
  Widget build(BuildContext context) => MaterialApp(
        title: 'RefWhistle Live',
        debugShowCheckedModeBanner: false,
        theme: ThemeData(
            colorSchemeSeed: const Color(0xFF7C4DFF),
            brightness: Brightness.dark,
            useMaterial3: true),
        home: const StandingsPage(),
      );
}

class StandingsPage extends StatefulWidget {
  const StandingsPage({super.key});

  @override
  State<StandingsPage> createState() => _StandingsPageState();
}

class _StandingsPageState extends State<StandingsPage> {
  final _code = TextEditingController();
  List<Map<String, dynamic>> _rows = [];
  String _status = 'Enter the tournament code shown in the RefWhistle app.';
  DateTime? _updated;
  Timer? _timer;
  bool _live = false;

  @override
  void dispose() {
    _timer?.cancel();
    _code.dispose();
    super.dispose();
  }

  Future<void> _start() async {
    final code = _code.text.trim();
    if (code.isEmpty) return;
    _timer?.cancel();
    await _fetch(code);
    _timer = Timer.periodic(const Duration(seconds: 10), (_) => _fetch(code));
  }

  Future<void> _fetch(String code) async {
    final url = Uri.parse('https://firestore.googleapis.com/v1/projects/$projectId'
        '/databases/(default)/documents/tournaments/$code/standings/latest');
    try {
      final res = await http.get(url);
      if (res.statusCode == 404) {
        setState(() {
          _live = false;
          _status = 'Nothing published yet for "$code". Ask the admin to tap Upload in Sync.';
        });
        return;
      }
      if (res.statusCode != 200) {
        setState(() {
          _live = false;
          _status = 'Server said ${res.statusCode}. Check the code and the Firestore rules.';
        });
        return;
      }
      final doc = jsonDecode(res.body) as Map<String, dynamic>;
      final fields = doc['fields'] as Map<String, dynamic>;
      final data = jsonDecode(fields['json']['stringValue'] as String) as Map<String, dynamic>;
      final ms = int.tryParse('${fields['updated']?['integerValue'] ?? ''}');
      setState(() {
        _rows = (data['standings'] as List).cast<Map<String, dynamic>>();
        _updated = ms == null ? null : DateTime.fromMillisecondsSinceEpoch(ms);
        _live = true;
        _status = 'Live';
      });
    } catch (e) {
      setState(() {
        _live = false;
        _status = 'Could not load: $e';
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Scaffold(
      body: Container(
        decoration: const BoxDecoration(
          gradient: LinearGradient(
            begin: Alignment.topLeft,
            end: Alignment.bottomRight,
            colors: [Color(0xFF120C2E), Color(0xFF2B2467), Color(0xFF0B6E7A)],
          ),
        ),
        child: SafeArea(
          child: Padding(
            padding: const EdgeInsets.all(16),
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text('RefWhistle Live',
                  style: Theme.of(context)
                      .textTheme
                      .headlineMedium
                      ?.copyWith(fontWeight: FontWeight.bold)),
              const SizedBox(height: 4),
              Row(children: [
                Icon(Icons.circle, size: 10, color: _live ? Colors.greenAccent : Colors.grey),
                const SizedBox(width: 6),
                Expanded(
                    child: Text(
                        _live && _updated != null
                            ? 'Live  -  updated ${_updated!.hour.toString().padLeft(2, '0')}:${_updated!.minute.toString().padLeft(2, '0')}'
                            : _status,
                        style: Theme.of(context).textTheme.bodySmall)),
              ]),
              const SizedBox(height: 12),
              Row(children: [
                Expanded(
                  child: TextField(
                    controller: _code,
                    textCapitalization: TextCapitalization.characters,
                    decoration: const InputDecoration(
                        border: OutlineInputBorder(), labelText: 'Tournament code'),
                    onSubmitted: (_) => _start(),
                  ),
                ),
                const SizedBox(width: 8),
                FilledButton(onPressed: _start, child: const Text('Watch')),
              ]),
              const SizedBox(height: 12),
              Expanded(
                child: _rows.isEmpty
                    ? const SizedBox()
                    : ListView.builder(
                        itemCount: _rows.length,
                        itemBuilder: (context, i) => _row(context, i, scheme),
                      ),
              ),
            ]),
          ),
        ),
      ),
    );
  }

  Widget _row(BuildContext context, int i, ColorScheme scheme) {
    final r = _rows[i];
    final gd = (r['gf'] as int) - (r['ga'] as int);
    return Container(
      margin: const EdgeInsets.only(bottom: 8),
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
      decoration: BoxDecoration(
        color: Colors.white.withOpacity(0.12),
        borderRadius: BorderRadius.circular(18),
        border: Border.all(color: Colors.white.withOpacity(0.25)),
      ),
      child: Row(children: [
        SizedBox(
            width: 28,
            child: Text('${i + 1}',
                style: TextStyle(
                    fontWeight: FontWeight.bold,
                    fontSize: 16,
                    color: i < 3 ? scheme.primary : null))),
        Expanded(
            child: Text('${r['team']}',
                style: const TextStyle(fontWeight: FontWeight.w600, fontSize: 16))),
        Text('P${r['p']}  W${r['w']}  D${r['d']}  L${r['l']}   ${gd >= 0 ? '+' : ''}$gd   ',
            style: Theme.of(context).textTheme.bodySmall),
        Text('${r['pts']}',
            style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 20)),
      ]),
    );
  }
}
