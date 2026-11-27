/*
 * ChunkyStalker -- logout-driven nightly pre-generation, built on Chunksmith.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.kishku7.chunksmith.stalker;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * The bookkeeping rules for logout spots. Pure functions over {@link StalkerData}, no game types,
 * so they can be unit-tested.
 */
public final class StalkerSpots {
    /**
     * A logout this close to an already-finished spot, in the same world and with the same radius,
     * does not queue the area again. Going AFK at the base every night should not re-scan the base
     * every night.
     */
    static final double SAME_SPOT_BLOCKS = 32;

    private StalkerSpots() {
    }

    /** Records a logout. Returns true if the spot now needs pre-generating. */
    public static boolean recordLogout(StalkerData data, String uuid, String name, String world, double x, double z, long now) {
        StalkerData.Spot previous = data.spots.get(uuid);
        if (previous != null && previous.completed && world.equals(previous.world)
                && Math.hypot(previous.x - x, previous.z - z) <= SAME_SPOT_BLOCKS) {
            previous.name = name;
            previous.loggedOutAt = now;
            return false;
        }
        data.spots.put(uuid, new StalkerData.Spot(name, world, x, z, now));
        return true;
    }

    /** Removes spots older than the expiry. Returns the names of the players removed. */
    public static List<String> pruneExpired(StalkerData data, long now) {
        List<String> removed = new ArrayList<>();
        int days = data.settings.expiryDays;
        if (days <= 0) {
            return removed;
        }
        long cutoff = now - TimeUnit.DAYS.toMillis(days);
        data.spots.entrySet().removeIf(entry -> {
            if (entry.getValue().loggedOutAt < cutoff) {
                removed.add(entry.getValue().name);
                return true;
            }
            return false;
        });
        return removed;
    }

    public static double radiusFor(StalkerData data, String uuid) {
        Double override = data.radii.get(uuid);
        return override != null && override > 0 ? override : data.settings.defaultRadius;
    }

    /** Spots still waiting, most recent logout first. */
    public static List<Map.Entry<String, StalkerData.Spot>> pending(StalkerData data) {
        List<Map.Entry<String, StalkerData.Spot>> list = new ArrayList<>();
        for (Map.Entry<String, StalkerData.Spot> entry : data.spots.entrySet()) {
            if (!entry.getValue().completed) {
                list.add(entry);
            }
        }
        list.sort(Comparator.comparingLong((Map.Entry<String, StalkerData.Spot> e) -> e.getValue().loggedOutAt).reversed());
        return list;
    }

    /** The next pending spot the caller says it can work on now. */
    public static Optional<Map.Entry<String, StalkerData.Spot>> next(StalkerData data, Predicate<StalkerData.Spot> eligible) {
        for (Map.Entry<String, StalkerData.Spot> entry : pending(data)) {
            if (eligible.test(entry.getValue())) {
                return Optional.of(entry);
            }
        }
        return Optional.empty();
    }

    /** Finds a stored player by name, case-insensitively. Returns the UUID key. */
    public static Optional<String> findByName(StalkerData data, String name) {
        for (Map.Entry<String, StalkerData.Spot> entry : data.spots.entrySet()) {
            if (entry.getValue().name != null && entry.getValue().name.equalsIgnoreCase(name)) {
                return Optional.of(entry.getKey());
            }
        }
        return Optional.empty();
    }

    /** Marks the spot finished, but only if it is still the spot the job was built from. */
    public static void markJobDone(StalkerData data, StalkerData.Job job) {
        StalkerData.Spot spot = data.spots.get(job.uuid);
        if (spot != null && job.world.equals(spot.world) && spot.x == job.x && spot.z == job.z) {
            spot.completed = true;
        }
    }
}
