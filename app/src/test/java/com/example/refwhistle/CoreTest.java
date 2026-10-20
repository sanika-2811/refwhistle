package com.example.refwhistle;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.example.refwhistle.domain.ConflictChecker;
import com.example.refwhistle.domain.DelayPropagator;
import com.example.refwhistle.domain.GreedyScheduler;
import com.example.refwhistle.domain.HybridLogicalClock;
import com.example.refwhistle.domain.LiveOps;
import com.example.refwhistle.domain.Robustness;
import com.example.refwhistle.domain.ScheduleCost;
import com.example.refwhistle.domain.LogCodec;
import com.example.refwhistle.domain.WeatherPlanner;
import com.example.refwhistle.domain.Match;
import com.example.refwhistle.domain.Operation;
import com.example.refwhistle.domain.OpLog;
import com.example.refwhistle.domain.Replayer;
import com.example.refwhistle.domain.Schedule;
import com.example.refwhistle.domain.Tournament;
import com.example.refwhistle.sim.Simulator;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class CoreTest {

    @Test
    public void greedyNeverBreaksHardRules() {
        for (int seed = 1; seed <= 10; seed++) {
            Tournament t = Simulator.randomTournament(6 + seed % 7, 3, 4, 3, seed);
            Schedule s = GreedyScheduler.schedule(t);
            assertTrue(Simulator.allFeasible(s.matches, t));
        }
    }

    @Test
    public void refereeCannotBeDoubleBooked() {
        Tournament t = Simulator.randomTournament(4, 2, 2, 1, 1);
        Match a = new Match("A", "T1", "T2", "V1", "R1", 0, 600, 60);
        Match b = new Match("B", "T3", "T4", "V2", "R1", 0, 630, 60);
        assertFalse(ConflictChecker.isFeasible(b, java.util.Arrays.asList(a), t));
    }

    @Test
    public void replicasConvergeUnderShuffledReplay() {
        Tournament t = Simulator.randomTournament(6, 2, 3, 2, 1);
        for (int seed = 1; seed <= 3; seed++) {
            assertEquals(1, Simulator.distinctOutcomes(t, seed, 200));
        }
    }

    @Test
    public void earlierBookingKeepsSlotAndLaterIsRescheduled() {
        Tournament t = Simulator.randomTournament(6, 1, 3, 2, 1);
        HybridLogicalClock c1 = new HybridLogicalClock("devA");
        HybridLogicalClock c2 = new HybridLogicalClock("devB");
        Match m1 = new Match("M1", "T4", "T5", "V1", "R1", 0, 600, 60);
        Match m2 = new Match("M2", "T3", "T6", "V1", "R2", 0, 600, 60); // same venue and time
        OpLog log = new OpLog();
        log.append(new Operation(c2.tick(2000), Operation.Type.ADD_MATCH, "M2", m2));
        log.append(new Operation(c1.tick(1000), Operation.Type.ADD_MATCH, "M1", m1));
        Replayer.Result r = Replayer.replay(log.sorted(), t);
        assertEquals(600, r.matches.get("M1").startMin);
        assertTrue(r.rescheduled.contains("M2"));
        assertTrue(Simulator.allFeasible(new ArrayList<Match>(r.matches.values()), t));
    }

    @Test
    public void delayPushesDependentMatchesOnly() {
        Tournament t = Simulator.randomTournament(6, 1, 2, 1, 1);
        Match a = new Match("A", "T3", "T4", "V1", "R1", 0, 600, 60);
        Match b = new Match("B", "T5", "T6", "V1", "R2", 0, 660, 60); // same venue, depends on A
        Match c = new Match("C", "T3", "T5", "V1", "R1", 0, 720, 60);
        List<Match> all = java.util.Arrays.asList(a, b, c);
        DelayPropagator.Result r = DelayPropagator.propagate(all, t, "A", 15);
        assertEquals(3, r.moves.size());
        for (Match m : r.moves) {
            if (m.id.equals("B")) assertEquals(675, m.startMin);
            if (m.id.equals("C")) assertEquals(765, m.startMin);
        }
    }

    @Test
    public void logCodecRoundTrips() {
        HybridLogicalClock c = new HybridLogicalClock("devA");
        Operation op = new Operation(c.tick(5), Operation.Type.ADD_MATCH, "M1",
                new Match("M1", "T1", "T2", "V1", "R1", 0, 600, 60));
        Operation back = LogCodec.decode(LogCodec.encode(op));
        assertEquals(op.id(), back.id());
        assertEquals(op.payload.toString(), back.payload.toString());
    }

    @Test
    public void refereeNeverOfficiatesOwnTeam() {
        Tournament t = Simulator.randomTournament(8, 3, 4, 3, 5);
        Match own = new Match("X", "T1", "T2", "V1", "R1", 0, 600, 60); // R1 belongs to T1
        assertFalse(ConflictChecker.isFeasible(own, new ArrayList<Match>(), t));
        Schedule s = GreedyScheduler.schedule(t);
        for (Match m : s.matches) {
            String refTeam = t.refereeTeamMap().get(m.refereeId);
            assertFalse(m.hasTeam(refTeam));
        }
    }

    @Test
    public void rainMovesOutdoorMatchesToSafeSlots() {
        Tournament t = Simulator.randomTournament(4, 3, 4, 1, 1);
        Schedule s = GreedyScheduler.schedule(t);
        WeatherPlanner.Result r = WeatherPlanner.plan(s.matches, t, 0, 8 * 60, 22 * 60);
        List<Match> after = new ArrayList<Match>(s.matches);
        for (Match mv : r.moves) {
            for (int i = 0; i < after.size(); i++) if (after.get(i).id.equals(mv.id)) after.set(i, mv);
        }
        assertTrue(Simulator.allFeasible(after, t));
        for (Match mv : r.moves) assertFalse(t.venueMap().get(mv.venueId).outdoor);
    }

    private static Tournament oneVenue() {
        return Simulator.randomTournament(6, 1, 3, 1, 1);
    }

    @Test
    public void earlyFinishPullsDependentMatchesForwardButNeverMoreThanFreedTime() {
        Tournament t = oneVenue();
        Match a = new Match("A", "T3", "T4", "V1", "R1", 0, 600, 60);   // planned 10:00-11:00
        Match b = new Match("B", "T5", "T6", "V1", "R2", 0, 660, 60);   // same venue
        Match c = new Match("C", "T1", "T2", "V1", "R3", 0, 720, 60);   // same venue, after B
        DelayPropagator.Result r = DelayPropagator.reclaim(java.util.Arrays.asList(a, b, c), t, "A", 645);
        assertEquals(3, r.moves.size());   // A shortened to its real length, then B and C pulled forward
        for (Match m : r.moves) {
            if (m.id.equals("A")) assertEquals(45, m.durationMin);
            if (m.id.equals("B")) assertEquals(645, m.startMin);   // 15 min earlier, not more
            if (m.id.equals("C")) assertEquals(705, m.startMin);
        }
        assertEquals("A", r.moves.get(0).id);                         // shortened first, so the rest is feasible
    }

    @Test
    public void reclaimLeavesIndependentMatchesAlone() {
        Tournament t = Simulator.randomTournament(6, 2, 3, 1, 1);
        Match a = new Match("A", "T3", "T4", "V1", "R1", 0, 600, 60);
        Match other = new Match("O", "T5", "T6", "V2", "R2", 0, 660, 60);  // other venue, other teams, other ref
        DelayPropagator.Result r = DelayPropagator.reclaim(java.util.Arrays.asList(a, other), t, "A", 640);
        assertEquals(0, r.moves.size());
    }

    @Test
    public void lateKickoffBecomesASuggestionAndDisappearsOnceApplied() {
        Tournament t = oneVenue();
        Match a = new Match("A", "T3", "T4", "V1", "R1", 0, 600, 60);
        Match b = new Match("B", "T5", "T6", "V1", "R2", 0, 660, 60);
        HybridLogicalClock clock = new HybridLogicalClock("devA");
        OpLog log = new OpLog();
        log.append(new Operation(clock.tick(1), Operation.Type.ADD_MATCH, "A", a));
        log.append(new Operation(clock.tick(2), Operation.Type.ADD_MATCH, "B", b));
        log.append(new Operation(clock.tick(3), Operation.Type.KICKOFF, "A", null, "612"));  // 12 min late
        Replayer.Result st = Replayer.replay(log.sorted(), t);
        List<Match> now = new ArrayList<Match>(st.matches.values());
        List<LiveOps.Suggestion> sug = LiveOps.suggest(st, now, t);
        assertEquals(1, sug.size());
        assertEquals(LiveOps.Kind.LATE, sug.get(0).kind);
        assertEquals(12, sug.get(0).minutes);

        // admin applies the moves -> the suggestion is gone (derived state, nothing to clean up)
        for (Match mv : sug.get(0).plan.moves) {
            log.append(new Operation(clock.tick(10), Operation.Type.MOVE_MATCH, mv.id, mv));
        }
        Replayer.Result after = Replayer.replay(log.sorted(), t);
        assertEquals(0, LiveOps.suggest(after, new ArrayList<Match>(after.matches.values()), t).size());
    }

    @Test
    public void liveEventsSurviveTheSyncCodecAndLearnMatchLength() {
        Tournament t = oneVenue();
        HybridLogicalClock clock = new HybridLogicalClock("devA");
        OpLog log = new OpLog();
        int[] starts = {600, 700, 800, 900};
        for (int i = 0; i < starts.length; i++) {
            String id = "M" + i;
            log.append(new Operation(clock.tick(10 * i + 1), Operation.Type.ADD_MATCH, id,
                    new Match(id, "T3", "T4", "V1", "R1", 0, starts[i], 60)));
            Operation k = new Operation(clock.tick(10 * i + 2), Operation.Type.KICKOFF, id, null, String.valueOf(starts[i]));
            Operation f = new Operation(clock.tick(10 * i + 3), Operation.Type.FULLTIME, id, null, String.valueOf(starts[i] + 70));
            log.append(LogCodec.decode(LogCodec.encode(k)));   // as received from another phone
            log.append(LogCodec.decode(LogCodec.encode(f)));
        }
        Replayer.Result st = Replayer.replay(log.sorted(), t);
        int[] obs = LiveOps.observedLength(st);
        assertEquals(70, obs[0]);
        assertEquals(4, obs[1]);
        assertEquals(70, LiveOps.learnedMatchMin(st, 60));
        assertEquals(60, LiveOps.learnedMatchMin(Replayer.replay(new ArrayList<Operation>(), t), 60)); // no data: keep plan
    }

    @Test
    public void tightLinksCountBackToBackChainsAndBlastRadiusFollows() {
        Tournament t = oneVenue();
        Match a = new Match("A", "T3", "T4", "V1", "R1", 0, 600, 60);
        Match tight = new Match("B", "T5", "T6", "V1", "R2", 0, 660, 60);   // starts the minute A ends
        Match loose = new Match("B", "T5", "T6", "V1", "R2", 0, 720, 60);   // 60 min of slack
        assertEquals(1, ScheduleCost.tightLinks(java.util.Arrays.asList(a, tight)));
        assertEquals(0, ScheduleCost.tightLinks(java.util.Arrays.asList(a, loose)));
        assertTrue(Robustness.blastRadius(java.util.Arrays.asList(a, tight), t, 15).average
                > Robustness.blastRadius(java.util.Arrays.asList(a, loose), t, 15).average);
    }

    @Test
    public void delayResilienceTermReducesBlastRadiusOnAverage() {
        double plain = 0, robust = 0;
        for (int seed = 1; seed <= 3; seed++) {
            Simulator.Resilience r = Simulator.resilience(10, seed);
            plain += r.plainAvg;
            robust += r.robustAvg;
        }
        assertTrue("with term " + robust + " vs without " + plain, robust < plain);
    }

    @Test
    public void bigLogTravelsInPartsAndComesBackIdentical() {
        // 600 entries do not fit one Bluetooth message: they are split, arrive shuffled and twice
        StringBuilder log = new StringBuilder();
        for (int i = 0; i < 600; i++) log.append("1000\t").append(i).append("\tdev-a\tADD_MATCH\tm").append(i).append("\t\t\n");
        String text = log.toString();
        List<String> parts = com.example.refwhistle.domain.SyncChunks.split(text, 4000);
        assertTrue(parts.size() > 1);
        for (String p : parts) assertTrue(p.getBytes(java.nio.charset.Charset.forName("UTF-8")).length <= 4000);

        java.util.Collections.reverse(parts);
        com.example.refwhistle.domain.SyncChunks.Assembler a = new com.example.refwhistle.domain.SyncChunks.Assembler();
        String full = null;
        for (String p : parts) {
            String done = a.add(p);
            if (done == null && p == parts.get(1)) a.add(p);        // a repeated message changes nothing
            if (done != null) full = done;
        }
        assertTrue(full != null);
        assertEquals(text.split("\n").length, full.split("\n").length);
        java.util.Set<String> before = new java.util.TreeSet<String>(java.util.Arrays.asList(text.split("\n")));
        java.util.Set<String> after = new java.util.TreeSet<String>(java.util.Arrays.asList(full.split("\n")));
        assertEquals(before, after);
    }

    private static String setupFingerprint(List<Operation> ops) {
        Tournament t = new Tournament();
        com.example.refwhistle.domain.SetupOps.fold(ops, t);
        StringBuilder sb = new StringBuilder();
        for (com.example.refwhistle.domain.Team x : t.teams) sb.append("T:").append(x.id).append('=').append(x.name).append(';');
        for (com.example.refwhistle.domain.Venue v : t.venues) {
            sb.append("V:").append(v.id).append('=').append(v.name).append(',').append(v.outdoor).append(',')
                    .append(v.openMin).append(',').append(v.closeMin).append(',').append(v.lat).append(',').append(v.lng).append(';');
        }
        for (com.example.refwhistle.domain.Referee r : t.referees) {
            sb.append("R:").append(r.id).append('=').append(r.name).append(',').append(r.teamId).append(';');
        }
        return sb.toString();
    }

    private static Operation setupOp(long t, String dev, Operation.Type type, String id, String data) {
        return new Operation(new com.example.refwhistle.domain.Stamp(t, 0, dev), type, id, null, data);
    }

    @Test
    public void setupTravelsThroughTheLogAndEveryPhoneEndsWithTheSameLists() {
        List<Operation> ops = new ArrayList<Operation>();
        ops.add(setupOp(1, "dev-a", Operation.Type.ADD_TEAM, "T1", com.example.refwhistle.domain.SetupOps.teamData("Thunder Hawks")));
        ops.add(setupOp(2, "dev-b", Operation.Type.ADD_TEAM, "T2", com.example.refwhistle.domain.SetupOps.teamData("Blue | Lions")));
        ops.add(setupOp(3, "dev-a", Operation.Type.ADD_VENUE, "V1",
                com.example.refwhistle.domain.SetupOps.venueData("Main Ground", true, 480, 1200, 0, 0)));
        ops.add(setupOp(4, "dev-b", Operation.Type.ADD_REFEREE, "R1", com.example.refwhistle.domain.SetupOps.refereeData("Aarav", "T1")));
        ops.add(setupOp(5, "dev-a", Operation.Type.SET_VENUE_LOCATION, "V1", com.example.refwhistle.domain.SetupOps.locationData(18.5, 73.8)));

        String expected = setupFingerprint(ops);
        assertTrue(expected, expected.contains("T:T2=Blue / Lions"));          // the separator was cleaned
        assertTrue(expected, expected.contains("18.5,73.8"));                  // the location was applied
        assertTrue(expected, expected.contains("R:R1=Aarav,T1"));

        java.util.Random rnd = new java.util.Random(7);
        for (int i = 0; i < 200; i++) {
            List<Operation> shuffled = new ArrayList<Operation>(ops);
            java.util.Collections.shuffle(shuffled, rnd);
            assertEquals(expected, setupFingerprint(shuffled));
        }
        // sent through the text format and read back, nothing changes; duplicates change nothing
        List<Operation> again = LogCodec.decodeAll(LogCodec.encodeAll(ops) + LogCodec.encodeAll(ops));
        assertEquals(expected, setupFingerprint(again));
    }

    @Test
    public void laterEditOfTheSameVenueWinsOnEveryPhone() {
        List<Operation> ops = new ArrayList<Operation>();
        ops.add(setupOp(1, "dev-a", Operation.Type.ADD_VENUE, "V1",
                com.example.refwhistle.domain.SetupOps.venueData("Field", true, 480, 1200, 1, 1)));
        ops.add(setupOp(9, "dev-b", Operation.Type.SET_VENUE_LOCATION, "V1", com.example.refwhistle.domain.SetupOps.locationData(2, 2)));
        ops.add(setupOp(5, "dev-a", Operation.Type.SET_VENUE_LOCATION, "V1", com.example.refwhistle.domain.SetupOps.locationData(3, 3)));
        String one = setupFingerprint(ops);
        java.util.Collections.reverse(ops);
        assertEquals(one, setupFingerprint(ops));
        assertTrue(one, one.contains("2.0,2.0"));
    }

    @Test
    public void renameAndRemoveWorkTheSameOnEveryPhone() {
        List<Operation> ops = new ArrayList<Operation>();
        ops.add(setupOp(1, "dev-a", Operation.Type.ADD_TEAM, "T1", "Hawks"));
        ops.add(setupOp(2, "dev-a", Operation.Type.ADD_TEAM, "T2", "Lions"));
        ops.add(setupOp(3, "dev-b", Operation.Type.ADD_TEAM, "T1", "Thunder Hawks"));   // rename
        ops.add(setupOp(4, "dev-a", Operation.Type.REMOVE_TEAM, "T2", null));
        ops.add(setupOp(5, "dev-b", Operation.Type.ADD_TEAM, "T3", "Wolves"));
        ops.add(setupOp(6, "dev-a", Operation.Type.REMOVE_TEAM, "T3", null));
        ops.add(setupOp(7, "dev-b", Operation.Type.ADD_TEAM, "T3", "Wolves again"));      // added back later
        String expected = setupFingerprint(ops);
        assertEquals("T:T1=Thunder Hawks;T:T3=Wolves again;", expected);
        java.util.Random rnd = new java.util.Random(3);
        for (int i = 0; i < 100; i++) {
            List<Operation> shuffled = new ArrayList<Operation>(ops);
            java.util.Collections.shuffle(shuffled, rnd);
            assertEquals(expected, setupFingerprint(shuffled));
        }
    }

    @Test
    public void firstAdminPasswordWinsOnEveryPhone() {
        String mine = com.example.refwhistle.domain.AdminLock.hash("owner-secret");
        String other = com.example.refwhistle.domain.AdminLock.hash("someone-else");
        assertFalse(mine.equals(other));
        assertEquals(mine, com.example.refwhistle.domain.AdminLock.hash("owner-secret"));     // same input, same hash
        assertTrue(!mine.contains("owner"));                                                  // the password itself is never stored

        List<Operation> ops = new ArrayList<Operation>();
        assertEquals(null, com.example.refwhistle.domain.AdminLock.passwordHash(ops));        // nobody set one yet
        ops.add(setupOp(50, "dev-b", Operation.Type.SET_ADMIN_PIN, "admin", other));          // a later attempt
        ops.add(setupOp(10, "dev-a", Operation.Type.SET_ADMIN_PIN, "admin", mine));           // the first one
        assertEquals(mine, com.example.refwhistle.domain.AdminLock.passwordHash(ops));
        java.util.Collections.reverse(ops);
        assertEquals(mine, com.example.refwhistle.domain.AdminLock.passwordHash(ops));
        // it travels through the text format unchanged and does not touch the schedule
        List<Operation> back = LogCodec.decodeAll(LogCodec.encodeAll(ops));
        assertEquals(mine, com.example.refwhistle.domain.AdminLock.passwordHash(back));
        assertTrue(Replayer.replay(back, new Tournament()).matches.isEmpty());
    }

    @Test
    public void unreadableSetupEntriesAreSkipped() {
        List<Operation> ops = new ArrayList<Operation>();
        ops.add(setupOp(1, "dev-a", Operation.Type.ADD_VENUE, "V1", "broken"));
        ops.add(setupOp(2, "dev-a", Operation.Type.ADD_TEAM, "T1", "Hawks"));
        assertEquals("T:T1=Hawks;", setupFingerprint(ops));
    }

    @Test
    public void setupEntriesDoNotChangeTheSchedule() {
        Tournament t = new Tournament();
        List<Operation> ops = new ArrayList<Operation>();
        ops.add(setupOp(1, "dev-a", Operation.Type.ADD_TEAM, "T1", "Hawks"));
        Replayer.Result r = Replayer.replay(ops, t);
        assertTrue(r.matches.isEmpty());
    }

    @Test
    public void emptyLogStillSendsOneMessage() {
        List<String> parts = com.example.refwhistle.domain.SyncChunks.split("", 4000);
        assertEquals(1, parts.size());
        assertEquals("", new com.example.refwhistle.domain.SyncChunks.Assembler().add(parts.get(0)));
    }
}
