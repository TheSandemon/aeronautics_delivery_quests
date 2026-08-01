package com.ladderstar.adq;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RandomLandingPlannerTest {
    @Test
    void candidatesStayInsideTheRequestedAnnulus() {
        int originX = 120;
        int originZ = -75;
        double minRadius = 300.0;
        double maxRadius = 2000.0;

        for (int attempt = 0; attempt < 96; attempt++) {
            RandomLandingPlanner.Candidate candidate = RandomLandingPlanner.candidate(
                    originX, originZ, minRadius, maxRadius, attempt, 96, 0.73);
            double distance = Math.hypot(candidate.x() - originX, candidate.z() - originZ);
            assertTrue(distance >= minRadius - 1.0);
            assertTrue(distance <= maxRadius + 1.0);
        }
    }

    @Test
    void candidatesSpreadAcrossAllQuadrants() {
        boolean[] quadrants = new boolean[4];

        for (int attempt = 0; attempt < 32; attempt++) {
            RandomLandingPlanner.Candidate candidate = RandomLandingPlanner.candidate(
                    0, 0, 100.0, 1000.0, attempt, 32, 0.0);
            int quadrant = (candidate.x() < 0 ? 2 : 0) + (candidate.z() < 0 ? 1 : 0);
            quadrants[quadrant] = true;
        }

        for (boolean covered : quadrants) {
            assertTrue(covered);
        }
    }
}
