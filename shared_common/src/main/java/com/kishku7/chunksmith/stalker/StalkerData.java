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

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Everything ChunkyStalker persists, serialised with Gson to {@code stalker.json}.
 *
 * <p>Plain mutable fields on purpose: Gson fills them reflectively, and a field missing from an older
 * file simply keeps the default written here.
 */
public final class StalkerData {
    public Settings settings = new Settings();
    /** Latest logout spot per player, keyed by UUID string. */
    public Map<String, Spot> spots = new LinkedHashMap<>();
    /** Per-player radius overrides in blocks, keyed by UUID string. */
    public Map<String, Double> radii = new LinkedHashMap<>();
    /** The job currently being worked on (running or paused), or null. */
    public Job active;

    public static final class Settings {
        public boolean enabled = true;
        public String windowStart = "02:00";
        public String windowEnd = "06:00";
        /** Blank means the server's own time zone. Otherwise any java.time zone id, e.g. America/New_York. */
        public String timezone = "";
        public double defaultRadius = 1000;
        /** Forget spots of players who have not logged out for this many days. 0 = never forget. */
        public int expiryDays = 14;
        public boolean pauseWhenPlayersOnline = true;
        public String shape = "circle";
        /** Concentric works outward from the spot, so if the night runs out the nearest chunks are done. */
        public String pattern = "concentric";
    }

    public static final class Spot {
        public String name;
        public String world;
        public double x;
        public double z;
        public long loggedOutAt;
        /** True once the area around this exact spot has been pre-generated. A new logout clears it. */
        public boolean completed;

        public Spot() {
        }

        public Spot(String name, String world, double x, double z, long loggedOutAt) {
            this.name = name;
            this.world = world;
            this.x = x;
            this.z = z;
            this.loggedOutAt = loggedOutAt;
        }
    }

    public static final class Job {
        public String uuid;
        public String name;
        public String world;
        public double x;
        public double z;
        public double radius;

        public Job() {
        }

        public Job(String uuid, String name, String world, double x, double z, double radius) {
            this.uuid = uuid;
            this.name = name;
            this.world = world;
            this.x = x;
            this.z = z;
            this.radius = radius;
        }
    }
}
