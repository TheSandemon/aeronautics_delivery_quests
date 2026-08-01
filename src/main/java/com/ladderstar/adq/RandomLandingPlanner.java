package com.ladderstar.adq;

final class RandomLandingPlanner {
    private static final double GOLDEN_ANGLE = Math.PI * (3.0 - Math.sqrt(5.0));

    private RandomLandingPlanner() {
    }

    static Candidate candidate(
            int originX,
            int originZ,
            double minRadius,
            double maxRadius,
            int attempt,
            int totalAttempts,
            double phase) {
        double boundedMin = Math.max(0.0, minRadius);
        double boundedMax = Math.max(boundedMin, maxRadius);
        double progress = (attempt + 0.5) / Math.max(1, totalAttempts);
        double radiusSquared = boundedMin * boundedMin
                + progress * (boundedMax * boundedMax - boundedMin * boundedMin);
        double radius = Math.sqrt(radiusSquared);
        double angle = phase + attempt * GOLDEN_ANGLE;
        return new Candidate(
                originX + (int) Math.round(radius * Math.cos(angle)),
                originZ + (int) Math.round(radius * Math.sin(angle)));
    }

    record Candidate(int x, int z) {
    }
}
