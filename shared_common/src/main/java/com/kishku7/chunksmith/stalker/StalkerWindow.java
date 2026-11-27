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

import java.time.LocalTime;
import java.time.DateTimeException;
import java.util.Optional;

/**
 * A daily time-of-day window, such as 02:00-06:00.
 *
 * <p>The window may wrap past midnight (22:00-04:00). The start is inclusive and the end exclusive.
 * A window whose start equals its end covers the whole day.
 */
public final class StalkerWindow {
    private StalkerWindow() {
    }

    public static boolean contains(LocalTime start, LocalTime end, LocalTime now) {
        if (start.equals(end)) {
            return true;
        }
        if (start.isBefore(end)) {
            return !now.isBefore(start) && now.isBefore(end);
        }
        // Wraps midnight: e.g. 22:00-04:00 is [22:00, 24:00) plus [00:00, 04:00).
        return !now.isBefore(start) || now.isBefore(end);
    }

    /**
     * Parses a time of day. Accepts {@code 2}, {@code 02}, {@code 2:30}, {@code 02:30} and {@code 0230}.
     */
    public static Optional<LocalTime> parse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        String s = text.trim();
        try {
            if (s.matches("\\d{1,2}")) {
                return Optional.of(LocalTime.of(Integer.parseInt(s), 0));
            }
            if (s.matches("\\d{4}")) {
                return Optional.of(LocalTime.of(Integer.parseInt(s.substring(0, 2)), Integer.parseInt(s.substring(2))));
            }
            if (s.matches("\\d{1,2}:\\d{2}")) {
                String[] parts = s.split(":");
                return Optional.of(LocalTime.of(Integer.parseInt(parts[0]), Integer.parseInt(parts[1])));
            }
        } catch (DateTimeException | NumberFormatException e) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    /** Formats as HH:mm. */
    public static String format(LocalTime time) {
        return String.format("%02d:%02d", time.getHour(), time.getMinute());
    }
}
