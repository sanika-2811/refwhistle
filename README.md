# RefWhistle

Android app (Java) that builds a tournament schedule automatically, keeps it consistent across offline phones, and re-times the day when a match runs late.

## Layout

| Path | What it is |
|---|---|
| `app/src/main/java/.../domain` | Pure Java core (no Android imports): scheduler, op-log, replay, delay propagation, standings, CSV |
| `app/src/main/java/.../data/DBHelper.java` | SQLite. `op_log` is the single source of truth; the schedule is the replay of the sorted log |
| `app/src/main/java/.../sync` | Automatic sync: `SyncManager` (runs both routes), `NearbySync` (Bluetooth, no network needed) |
| `app/src/main/java/.../cloud/Cloud.java` | Internet route: Firestore log, one document per entry |
| `app/src/main/java/.../ui` | Screens: Main (role, schedule, match actions), Create fixture, Setup, Sync, Standings |
| `app/src/test/.../sim` | Simulator + `SimMain` (numbers for the report) |
| `app/src/test/.../CoreTest.java` | JUnit tests |
| `flutter_standings` | Small Flutter standings dashboard |

## The three core features

**A. Constraint scheduler** (`GreedyScheduler`, `SimulatedAnnealing`, `ConflictChecker`, `ScheduleCost`, `Diagnosis`)
Hard rules (venue, referee, team double-booking, team rest, venue hours) are checked by one `ConflictChecker` used by the scheduler, the manual form, replay and delay propagation. Soft goals (team idle gaps, referee spread, midday heat for outdoor venues) are one cost number. Greedy is the baseline; simulated annealing improves it and only accepts moves that keep every hard rule. If matches cannot be placed, `Diagnosis` explains which resource is short.

**B. Offline-first sync** (`HybridLogicalClock`, `Stamp`, `OpLog`, `Replayer`, `LogCodec`, `SyncManager`)
Every change (add, move, cancel, result) is an append-only row in `op_log`, stamped with (hybrid logical clock, counter, device id). Sync = set union of logs. Replay in stamp order is deterministic, so all phones show the same schedule. If two offline admins book the same slot the earlier stamp keeps it; the later match is moved to the next free slot and shown as `CLASH: auto-rescheduled`.
Sync is automatic. Enter the same tournament code on every phone once; after that:
- **Internet:** each log entry is one Firestore document, so phones anywhere in the world exchange changes. Writes made offline are queued and sent when the connection returns.
- **No internet:** phones within Bluetooth range find each other (Google Nearby Connections, service id derived from the tournament code) and swap their logs. No shared Wi-Fi, hotspot or typed address. Large logs are split into parts (`SyncChunks`).
- **Last resort:** share/import the log as a text file.
Both routes carry the same append-only log and merging is a set union, so receiving an entry twice or in any order is harmless.

**C. Delay propagation** (`DelayPropagator`)
Tap a match > "Mark running late". Dependents (same venue, referee or team, with team rest) are pushed transitively, matches pushed past venue closing move to the next free slot, remaining clashes are flagged, and local notifications go to the affected teams/referees. The moves are logged, so the other phone gets them on its next sync and notifies its own referee/team member.

## Advanced features (beyond the brief)

### Original contribution 1: delay-resistant scheduling
Most tools react to delays. RefWhistle schedules so that a delay is cheap.
- `ScheduleCost.tightLinks()` counts back-to-back pairs on the same venue or referee with under 15 min of slack. Each one lets a delay cascade, so the optimiser penalises them (weight 30).
- `Robustness.blastRadius()` measures the true effect: for every match it runs the real `DelayPropagator` for a 15-minute delay and counts the matches that move.
- Measured with `SimMain` section 8 (average of 6 random tournaments, matches moved by one 15-min delay):

| Teams | Greedy | Optimiser without the term | Optimiser with the term | Reduction |
|---|---|---|---|---|
| 8 | 2.15 | 1.24 | 1.08 | 13% |
| 10 | 4.67 | 2.30 | 1.42 | 38% |
| 12 | 6.31 | 4.07 | 2.31 | 43% |

The other cost terms stay almost the same (12 teams: 9337 vs 9300). A higher weight cuts the blast radius further at the cost of more idle time between a team's matches. The idea is known as robust scheduling in operations research.

### Original contribution 2: live events, auto-detected lateness and reclaimed time
- A referee taps **Kick-off** and **Full time** on a match. These are log operations (`KICKOFF`, `FULLTIME`), so they sync like everything else.
- `LiveOps.suggest()` derives suggestions from the replayed log, with no extra state: a kick-off 5+ minutes late proposes re-timing the following matches; a match that finishes 10+ minutes early proposes pulling dependent matches forward (`DelayPropagator.reclaim`, never earlier than the freed time and never before another predecessor allows). The admin sees them as a banner, previews the exact moves, and applies or dismisses. Applied suggestions disappear on their own.
- `LiveOps.learnedMatchMin()` learns the real match length from played matches (3+ needed) and the next Generate uses it.
- **Visual timeline**: Gantt-style day view, one lane per venue, coloured by referee, with the midday-heat window shaded on outdoor lanes and clash/re-timed outlines. Tap a block for actions.
- **Referee conflict of interest**: a referee who also plays for a team is never assigned to that team's match (hard rule in `ConflictChecker`, enforced by scheduler, replay, delay and rain planning).
- **Rain / ground-closed planner** (`WeatherPlanner`): outdoor matches in a closed window move to the nearest free slot, preferring indoor venues; shown as a preview first.
- **Delay preview**: every re-timing shows exactly which matches move before anything is written.
- **Next free slot**: when a manual fixture clashes, the app offers the nearest valid slot.
- **Insights dashboard**: cost vs greedy baseline, referee workload, team waiting time, venue utilisation, guarantees.

## Firebase (optional)
The app works fully offline without it. To enable sync over the internet:
1. Firebase console > create project > add Android app with package `com.example.refwhistle`.
2. Download `google-services.json` into the `app/` folder, then sync Gradle.
3. Authentication > Sign-in method > enable **Email/Password** (everyone signs in once; the app then works offline).
4. Firestore Database > create (production mode) > Rules:
   `rules_version = '2'; service cloud.firestore { match /databases/{db}/documents { match /tournaments/{code}/ops/{op} { allow read, write: if request.auth != null; } } }`
Cloud design: one Firestore document per op-log entry (never overwritten), so multiple admins cannot lose each other's changes (Firestore's own offline writes are last-write-wins).

## Google Map (optional key)
Menu > Venue map shows all venues (orange = outdoor, green = indoor) with their next match; long-press the map to place a venue. Needs a Maps SDK for Android key: add `MAPS_API_KEY=your_key` to `local.properties` and rebuild. Without a key the app still works and the map screen shows a notice.

## Roles
First launch asks who you are: Admin (everything), Referee (own matches, results, navigate, GPS check-in), Team (own fixtures, standings).

**Admin password.** Choosing Admin asks for a password. The first admin creates it (6+ characters); other admins type the same one. Only its hash is stored, as one entry in the log (`AdminLock`), so every phone can check it; the earliest entry is the real one. Referees and team members cannot open Setup, Add a match, Rain planner, Load demo, place venues, re-time, cancel or generate. This is an in-app lock: it stops people switching role by accident or curiosity, but it is not a server-side lock, and the password cannot be reset.

## Running the simulator and tests (no Android needed)

```bash
cd app
mkdir -p /tmp/rw && javac -d /tmp/rw $(find src/main/java/com/example/refwhistle/domain src/test/java/com/example/refwhistle/sim -name '*.java')
java -cp /tmp/rw com.example.refwhistle.sim.SimMain
```
`./gradlew test` runs `CoreTest` (JUnit 4) inside Android Studio.

Measured on this machine (SimMain): schedule cost improved over greedy in every run, hard rules always held, 1000 shuffled replays of the same logs gave exactly 1 distinct schedule, and a 16-team delay recompute took about 6 ms.

## Demo script (3 min)
1. Admin phone A: **Seed**, then **Generate** (shows greedy vs RefWhistle cost).
2. Both phones: **Sync**, type the same tournament code, **Save code and start**. Phone B: **Load demo tournament** (same ids).
3. Set both phones to airplane mode, then turn Bluetooth back on. On each, **Add** a fixture on the same venue and time.
4. Within a minute both phones show the same list by themselves; the later booking is flagged and rescheduled.
5. Phone A: tap a match > **Mark running late** 15 min. Phone B updates and notifies on its own.

## Build / release
Open the folder in Android Studio and run. For a signed release copy `keystore.properties.example` to `keystore.properties`, point it at your `.jks`, then build the `release` variant.

## Live dashboard
Open https://refwhistle-a0303.web.app, type TEST2026 and press Watch.
TEST2026 is a public demo tournament. Do not use it for a real tournament.

## Not done / honest limits
- The Android app compiles and the JUnit tests pass (`./gradlew testDebugUnitTest`). Nearby (Bluetooth) sync needs two real phones and has not been field-tested yet; the merge logic itself is tested.
- Internet sync needs your own `google-services.json` and Email/Password sign-in enabled. Without it the app works offline and syncs over Bluetooth only.
- Two phones that are both far apart and both offline cannot exchange changes until one of them gets internet. That is physics, not a bug.
- Teams, venues and referees are log entries too (`SetupOps`: ADD_TEAM, ADD_VENUE, ADD_REFEREE, SET_VENUE_LOCATION), so a phone that joins a tournament receives the whole setup by itself. Setup can be renamed or removed (a team, venue or referee that still has matches cannot be removed). Setup saved by an older version is added to the log on first start.
- **Load demo tournament** never wipes the log: it cancels the current matches and adds the demo setup, so it is safe while syncing.
- No embedded Google Map (needs an API key); venues open in the maps app for navigation. Check-in uses the last known GPS fix within 300 m.
- Rain planning uses a manual closure window (no weather API).
- The scheduler is a heuristic (fine for 8-16 teams, not guaranteed optimal). Notifications are local only.
- Flutter dashboard reads pasted JSON or a URL; it needs `flutter create .` once inside `flutter_standings` to generate the platform folders.
