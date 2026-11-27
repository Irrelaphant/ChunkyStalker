package com.kishku7.chunksmith.stalker;

import org.junit.Test;

import java.time.LocalTime;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class StalkerRulesTest {
    private static LocalTime t(int h, int m) {
        return LocalTime.of(h, m);
    }

    @Test
    public void windowSameDay() {
        assertTrue(StalkerWindow.contains(t(2, 0), t(6, 0), t(2, 0)));
        assertTrue(StalkerWindow.contains(t(2, 0), t(6, 0), t(5, 59)));
        assertFalse(StalkerWindow.contains(t(2, 0), t(6, 0), t(6, 0)));
        assertFalse(StalkerWindow.contains(t(2, 0), t(6, 0), t(1, 59)));
        assertFalse(StalkerWindow.contains(t(2, 0), t(6, 0), t(14, 0)));
    }

    @Test
    public void windowWrapsMidnight() {
        assertTrue(StalkerWindow.contains(t(22, 0), t(4, 0), t(23, 30)));
        assertTrue(StalkerWindow.contains(t(22, 0), t(4, 0), t(0, 0)));
        assertTrue(StalkerWindow.contains(t(22, 0), t(4, 0), t(3, 59)));
        assertFalse(StalkerWindow.contains(t(22, 0), t(4, 0), t(4, 0)));
        assertFalse(StalkerWindow.contains(t(22, 0), t(4, 0), t(12, 0)));
    }

    @Test
    public void equalEndsMeanAllDay() {
        assertTrue(StalkerWindow.contains(t(3, 0), t(3, 0), t(15, 0)));
    }

    @Test
    public void parsesTimes() {
        assertEquals(t(2, 0), StalkerWindow.parse("2").orElseThrow());
        assertEquals(t(2, 30), StalkerWindow.parse("02:30").orElseThrow());
        assertEquals(t(2, 30), StalkerWindow.parse("0230").orElseThrow());
        assertEquals(t(23, 5), StalkerWindow.parse(" 23:05 ").orElseThrow());
        assertTrue(StalkerWindow.parse("25:00").isEmpty());
        assertTrue(StalkerWindow.parse("2am").isEmpty());
        assertTrue(StalkerWindow.parse(null).isEmpty());
        assertEquals("02:05", StalkerWindow.format(t(2, 5)));
    }

    @Test
    public void newLogoutQueuesAndNearbyRepeatDoesNot() {
        StalkerData data = new StalkerData();
        assertTrue(StalkerSpots.recordLogout(data, "u1", "Alex", "minecraft:overworld", 100, 100, 1000));
        data.spots.get("u1").completed = true;
        // AFK at the same base again: no new work.
        assertFalse(StalkerSpots.recordLogout(data, "u1", "Alex", "minecraft:overworld", 110, 95, 2000));
        assertTrue(data.spots.get("u1").completed);
        assertEquals(2000, data.spots.get("u1").loggedOutAt);
        // Moved far away: queued.
        assertTrue(StalkerSpots.recordLogout(data, "u1", "Alex", "minecraft:overworld", 5000, 100, 3000));
        assertFalse(data.spots.get("u1").completed);
        // Same coordinates, other dimension: queued.
        data.spots.get("u1").completed = true;
        assertTrue(StalkerSpots.recordLogout(data, "u1", "Alex", "minecraft:the_nether", 5000, 100, 4000));
    }

    @Test
    public void pendingIsMostRecentFirst() {
        StalkerData data = new StalkerData();
        StalkerSpots.recordLogout(data, "a", "A", "w", 0, 0, 100);
        StalkerSpots.recordLogout(data, "b", "B", "w", 0, 0, 300);
        StalkerSpots.recordLogout(data, "c", "C", "w", 0, 0, 200);
        data.spots.get("c").completed = true;
        assertEquals("b", StalkerSpots.pending(data).get(0).getKey());
        assertEquals("a", StalkerSpots.pending(data).get(1).getKey());
        assertEquals(2, StalkerSpots.pending(data).size());
        assertEquals("a", StalkerSpots.next(data, spot -> !spot.name.equals("B")).orElseThrow().getKey());
    }

    @Test
    public void expiry() {
        StalkerData data = new StalkerData();
        long now = TimeUnit.DAYS.toMillis(100);
        data.settings.expiryDays = 14;
        StalkerSpots.recordLogout(data, "old", "Old", "w", 0, 0, now - TimeUnit.DAYS.toMillis(15));
        StalkerSpots.recordLogout(data, "new", "New", "w", 0, 0, now - TimeUnit.DAYS.toMillis(13));
        assertEquals(java.util.List.of("Old"), StalkerSpots.pruneExpired(data, now));
        assertEquals(1, data.spots.size());
        data.settings.expiryDays = 0;
        StalkerSpots.recordLogout(data, "ancient", "Ancient", "w", 0, 0, 0);
        assertTrue(StalkerSpots.pruneExpired(data, now).isEmpty());
    }

    @Test
    public void radiusOverride() {
        StalkerData data = new StalkerData();
        data.settings.defaultRadius = 1000;
        assertEquals(1000, StalkerSpots.radiusFor(data, "u"), 0);
        data.radii.put("u", 2500d);
        assertEquals(2500, StalkerSpots.radiusFor(data, "u"), 0);
    }

    @Test
    public void jobDoneOnlyMarksTheSpotItWasBuiltFrom() {
        StalkerData data = new StalkerData();
        StalkerSpots.recordLogout(data, "u", "U", "w", 10, 20, 1);
        StalkerData.Job job = new StalkerData.Job("u", "U", "w", 10, 20, 1000);
        // The player logged in and out somewhere else while the job ran.
        StalkerSpots.recordLogout(data, "u", "U", "w", 9000, 20, 2);
        StalkerSpots.markJobDone(data, job);
        assertFalse(data.spots.get("u").completed);
        StalkerData.Job job2 = new StalkerData.Job("u", "U", "w", 9000, 20, 1000);
        StalkerSpots.markJobDone(data, job2);
        assertTrue(data.spots.get("u").completed);
    }

    @Test
    public void findByNameIgnoresCase() {
        StalkerData data = new StalkerData();
        StalkerSpots.recordLogout(data, "u", "Steve", "w", 0, 0, 1);
        assertEquals("u", StalkerSpots.findByName(data, "steve").orElseThrow());
        assertTrue(StalkerSpots.findByName(data, "alex").isEmpty());
    }
}
