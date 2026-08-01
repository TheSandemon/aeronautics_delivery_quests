package com.ladderstar.adq;

final class DryLandingRules {
    private DryLandingRules() {
    }

    static boolean canHaveGround(int surfaceY, int minBuildHeight) {
        return surfaceY > minBuildHeight;
    }
}
