package com.example.refwhistle.sim;

import com.example.refwhistle.domain.DelayPropagator;
import com.example.refwhistle.domain.GreedyScheduler;
import com.example.refwhistle.domain.Match;
import com.example.refwhistle.domain.Schedule;
import com.example.refwhistle.domain.Tournament;

import com.example.refwhistle.domain.OpLog;
import com.example.refwhistle.domain.Operation;
import com.example.refwhistle.domain.WeatherPlanner;

import java.util.ArrayList;
import java.util.List;

/** Prints the numbers for the report. Run: see README ("Running the simulator"). */
public class SimMain {
    public static void main(String[] args) {
        System.out.println("== 1. Schedule quality: greedy vs greedy+annealing (lower cost is better) ==");
        System.out.println("teams  greedy  improved  refSpread(g->sa)  heat(g->sa)  unplaced  hard-rules-ok");
        for (int n = 6; n <= 12; n += 2) {
            Simulator.QualityRow r = Simulator.quality(n, 42 + n);
            System.out.printf("%5d  %6d  %8d  %9d->%-5d  %6d->%-4d  %8d  %s%n", r.teams, r.greedyCost,
                    r.saCost, r.greedySpread, r.saSpread, r.greedyHeat, r.saHeat, r.unplaced, r.feasible);
        }

        System.out.println();
        System.out.println("== 2. Sync: same logs, 1000 shuffled arrival orders ==");
        Tournament t = Simulator.randomTournament(6, 2, 3, 2, 1);
        int bad = 0;
        for (int seed = 1; seed <= 5; seed++) {
            int distinct = Simulator.distinctOutcomes(t, seed, 1000);
            System.out.println("log #" + seed + ": distinct schedules = " + distinct + (distinct == 1 ? "  OK" : "  FAIL"));
            if (distinct != 1) bad++;
        }
        System.out.println(bad == 0 ? "All replicas converge." : "DIVERGENCE FOUND");

        System.out.println();
        System.out.println("== 3. Referee fairness (worst busiest-minus-least-busy over 20 tournaments) ==");
        System.out.println("greedy: " + Simulator.worstSpread(true, 20)
                + "   improved: " + Simulator.worstSpread(false, 20));

        System.out.println();
        System.out.println("== 4. Delay recompute speed, 16 teams ==");
        System.out.printf("average %.2f ms per delay%n", Simulator.delayMillis(50));

        System.out.println();
        System.out.println("== 5. Delay propagation example ==");
        Tournament d = Simulator.randomTournament(8, 2, 3, 2, 3);
        Schedule s = GreedyScheduler.schedule(d);
        Match first = s.matches.get(0);
        DelayPropagator.Result r = DelayPropagator.propagate(s.matches, d, first.id, 20);
        System.out.println(first.id + " is 20 min late -> " + r.affected() + " match(es) moved, "
                + r.flags.size() + " flag(s)");
        List<Match> moves = r.moves;
        for (Match m : moves) System.out.println("   " + m);

        System.out.println();

        System.out.println();
        System.out.println("== 7. Rain plan (Day 1, outdoor grounds closed 08:00-22:00) ==");
        Tournament rt = Simulator.randomTournament(8, 3, 4, 3, 21);
        Schedule rs = GreedyScheduler.schedule(rt);
        WeatherPlanner.Result wr = WeatherPlanner.plan(rs.matches, rt, 0, 8 * 60, 22 * 60);
        System.out.println(wr.moves.size() + " match(es) moved to indoor/later slots, " + wr.flags.size() + " unresolved");
    
        System.out.println();
        System.out.println("== 8. Delay resilience: matches moved by ONE 15-min delay (average over 6 random tournaments) ==");
        System.out.println("teams  greedy   optimiser w/o term   optimiser WITH term   reduction");
        for (int n = 6; n <= 12; n += 2) {
            double g = 0, p = 0, w = 0;
            for (int seed = 1; seed <= 6; seed++) {
                Simulator.Resilience rr = Simulator.resilience(n, seed);
                g += rr.greedyAvg;
                p += rr.plainAvg;
                w += rr.robustAvg;
            }
            g /= 6; p /= 6; w /= 6;
            System.out.printf("%5d  %6.2f   %18.2f   %19.2f   %8.0f%%%n", n, g, p, w, p == 0 ? 0 : 100 * (p - w) / p);
        }
    }
}
