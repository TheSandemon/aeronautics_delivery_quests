package com.ladderstar.adq;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DryLandingRulesTest {
    @Test
    void permitsValidTerrainNearTheDimensionFloor() {
        assertTrue(DryLandingRules.canHaveGround(-60, -64));
        assertTrue(DryLandingRules.canHaveGround(-63, -64));
    }

    @Test
    void rejectsAnEmptyHeightmapAtTheDimensionFloor() {
        assertFalse(DryLandingRules.canHaveGround(-64, -64));
    }
}
