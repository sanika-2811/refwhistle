package com.example.refwhistle;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.example.refwhistle.domain.ConflictChecker;
import com.example.refwhistle.domain.DelayPropagator;
import com.example.refwhistle.domain.GreedyScheduler;
import com.example.refwhistle.domain.HybridLogicalClock;
import com.example.refwhistle.domain.Ledger;
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
    public void ledgerHeadIsOrderIndependentButDetectsTampering() {
        Tournament t = Simulator.randomTournament(6, 2, 3, 2, 1);
        OpLog a = new OpLog();
        for (OpLog l : Simulator.randomDevices(t, 3, 30)) a.merge(l.all());
        List<Operation> ops = a.all();
        OpLog b = new OpLog();
        java.util.Collections.shuffle(ops, new java.util.Random(9));
        b.merge(ops);
        assertEquals(Ledger.head(a.sorted()), Ledger.head(b.sorted()));

        List<String> trusted = Ledger.chain(a.sorted());
        List<Operation> forged = a.sorted();
        Operation victim = forged.get(forged.size() / 2);
        forged.set(forged.size() / 2, new Operation(victim.stamp, victim.type, victim.matchId,
                victim.payload, "9|0"));
        assertEquals(forged.size() / 2, Ledger.firstMismatch(forged, trusted));
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

    @Test
    public void logosTravelThroughTheLogAndLastOneWins() {
        Tournament t = Simulator.randomTournament(6, 2, 3, 2, 1);
        HybridLogicalClock c1 = new HybridLogicalClock("devA");
        HybridLogicalClock c2 = new HybridLogicalClock("devB");
        OpLog log = new OpLog();
        log.append(new Operation(c1.tick(1000), Operation.Type.SET_LOGO, "T1", null, "AAAA"));
        Operation newer = new Operation(c2.tick(2000), Operation.Type.SET_LOGO, "T1", null, "BBBB");
        log.append(newer);
        // survives the text codec used by every sync channel
        log.append(LogCodec.decode(LogCodec.encode(newer)));
        Replayer.Result r = Replayer.replay(log.sorted(), t);
        assertEquals("BBBB", r.logos.get("T1"));
        assertEquals(2, log.size());
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
}
