/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.calcite.util;

import org.apache.calcite.avatica.util.DateTimeUtils;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalTime;
import java.time.OffsetTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Calendar;
import java.util.TimeZone;

import static com.google.common.base.Preconditions.checkArgument;

import static org.apache.calcite.avatica.util.DateTimeUtils.TIME_FORMAT_STRING;
import static org.apache.calcite.util.Static.RESOURCE;

import static java.lang.Math.floorMod;

/**
 * Time with time-zone literal.
 *
 * <p>Immutable. The value consists of a local time and a zone, and can
 * support unlimited precision (milliseconds, nanoseconds).
 *
 * <p>A time has no date, so a region zone (such as {@code America/New_York})
 * has no well-defined offset; {@link #toOffsetTime()} uses the zone's offset
 * on 1970-01-01. Values with an explicit offset (such as {@code +05:30}) are
 * not affected.
 */
public class TimeWithTimeZoneString implements Comparable<TimeWithTimeZoneString> {

  final TimeString localTime;
  final ZoneId zone;
  final String v;

  /** Creates a TimeWithTimeZoneString. */
  public TimeWithTimeZoneString(TimeString localTime, ZoneId zone) {
    this.localTime = localTime;
    this.zone = zone;
    this.v = localTime + " " + DateTimeStringUtils.zoneToString(zone);
  }

  /** Creates a TimeWithTimeZoneString. */
  public TimeWithTimeZoneString(TimeString localTime, TimeZone timeZone) {
    this(localTime, DateTimeStringUtils.toZoneId(timeZone));
  }

  /** Creates a TimeWithTimeZoneString from a string such as
   * {@code 10:00:00 UTC} or {@code 10:00:00.5+05:30}. */
  public TimeWithTimeZoneString(String v) {
    this(parse(v));
  }

  private TimeWithTimeZoneString(TimeWithTimeZoneString t) {
    this(t.localTime, t.zone);
  }

  /** Creates a TimeWithTimeZoneString for hour, minute, second and millisecond values
   * in the given time-zone. */
  public TimeWithTimeZoneString(int h, int m, int s, String timeZone) {
    this(DateTimeStringUtils.hms(new StringBuilder(), h, m, s).toString() + " " + timeZone);
  }

  private static TimeWithTimeZoneString parse(String v) {
    final String[] parts = DateTimeStringUtils.splitTimeZone(v);
    if (parts == null) {
      throw RESOURCE.illegalLiteral("TIME WITH TIME ZONE", v,
          RESOURCE.badFormat(TIME_FORMAT_STRING + " zone").str()).ex();
    }
    final ZoneId zone;
    try {
      zone = DateTimeStringUtils.parseZone(parts[1]);
    } catch (DateTimeException e) {
      throw new IllegalArgumentException(e.getMessage(), e);
    }
    return new TimeWithTimeZoneString(new TimeString(parts[0]), zone);
  }

  /** Sets the fraction field of a {@code TimeWithTimeZoneString} to a given number
   * of milliseconds. Nukes the value set via {@link #withNanos}.
   *
   * <p>For example,
   * {@code new TimeWithTimeZoneString(2, 3, 4, "UTC").withMillis(56)}
   * yields {@code TIME WITH TIME ZONE '02:03:04.056 UTC'}. */
  public TimeWithTimeZoneString withMillis(int millis) {
    checkArgument(millis >= 0 && millis < 1000);
    return withFraction(DateTimeStringUtils.pad(3, millis));
  }

  /** Creates a TimeWithTimeZoneString from a Calendar. */
  public static TimeWithTimeZoneString fromCalendarFields(Calendar calendar) {
    TimeString ts = TimeString.fromCalendarFields(calendar);
    return new TimeWithTimeZoneString(ts, calendar.getTimeZone());
  }

  /** Creates a TimeWithTimeZoneString from an {@link OffsetTime}. */
  public static TimeWithTimeZoneString fromOffsetTime(OffsetTime t) {
    return new TimeWithTimeZoneString(
        new TimeString(t.getHour(), t.getMinute(), t.getSecond())
            .withNanos(t.getNano()),
        t.getOffset());
  }

  /** Sets the fraction field of a {@code TimeString} to a given number
   * of nanoseconds. Nukes the value set via {@link #withMillis(int)}.
   *
   * <p>For example,
   * {@code new TimeWithTimeZoneString(2, 3, 4, "UTC").withNanos(56789)}
   * yields {@code TIME WITH TIME ZONE '02:03:04.000056789 UTC'}. */
  public TimeWithTimeZoneString withNanos(int nanos) {
    checkArgument(nanos >= 0 && nanos < 1000000000);
    return withFraction(DateTimeStringUtils.pad(9, nanos));
  }

  /** Sets the fraction field of a {@code TimeWithTimeZoneString}.
   * The precision is determined by the number of leading zeros.
   * Trailing zeros are stripped.
   *
   * <p>For example,
   * {@code new TimeWithTimeZoneString(2, 3, 4, "UTC").withFraction("00506000")}
   * yields {@code TIME WITH TIME ZONE '02:03:04.00506 UTC'}. */
  public TimeWithTimeZoneString withFraction(String fraction) {
    return new TimeWithTimeZoneString(localTime.withFraction(fraction), zone);
  }

  /** Returns a value that denotes the same time in a different time zone. */
  public TimeWithTimeZoneString withTimeZone(TimeZone timeZone) {
    return withZone(DateTimeStringUtils.toZoneId(timeZone));
  }

  /** Returns a value that denotes the same time in a different zone;
   * the zone is kept as given. */
  public TimeWithTimeZoneString withZone(ZoneId zone) {
    if (this.zone.equals(zone)) {
      return this;
    }
    final LocalTime t =
        toOffsetTime().withOffsetSameInstant(offset(zone)).toLocalTime();
    return new TimeWithTimeZoneString(
        new TimeString(t.getHour(), t.getMinute(), t.getSecond())
            .withNanos(t.getNano()),
        zone);
  }

  /** Returns the offset of a zone; for a region, the offset on
   * 1970-01-01. */
  static ZoneOffset offset(ZoneId zone) {
    if (zone instanceof ZoneOffset) {
      return (ZoneOffset) zone;
    }
    return zone.getRules().getOffset(Instant.EPOCH);
  }

  /** Converts this value to an {@link OffsetTime}. */
  public OffsetTime toOffsetTime() {
    return OffsetTime.of(LocalTime.parse(localTime.toString()), offset(zone));
  }

  @Override public String toString() {
    return v;
  }

  @Override public boolean equals(@Nullable Object o) {
    // The value is in canonical form (no trailing zeros).
    return o == this
        || o instanceof TimeWithTimeZoneString
        && ((TimeWithTimeZoneString) o).v.equals(v);
  }

  @Override public int hashCode() {
    return v.hashCode();
  }

  @Override public int compareTo(TimeWithTimeZoneString o) {
    return Long.compare(utcNanos(toOffsetTime()), utcNanos(o.toOffsetTime()));
  }

  /** Returns the nanoseconds since midnight UTC of a time with offset;
   * may be negative or exceed a day. */
  private static long utcNanos(OffsetTime t) {
    return t.toLocalTime().toNanoOfDay()
        - t.getOffset().getTotalSeconds() * 1_000_000_000L;
  }

  public TimeWithTimeZoneString round(int precision) {
    checkArgument(precision >= 0);
    return new TimeWithTimeZoneString(localTime.round(precision), zone);
  }

  public static TimeWithTimeZoneString fromMillisOfDay(int i) {
    return new TimeWithTimeZoneString(
        new TimeString(DateTimeUtils.unixTimeToString(i))
            .withMillis((int) floorMod(i, 1000L)),
        DateTimeUtils.UTC_ZONE);
  }

  /** Converts this TimeWithTimeZoneString to a string, truncated or padded with
   * zeros to a given precision. */
  public String toString(int precision) {
    checkArgument(precision >= 0);
    return localTime.toString(precision) + " "
        + DateTimeStringUtils.zoneToString(zone);
  }

  public TimeString getLocalTimeString() {
    return localTime;
  }

  /** Returns the zone: a {@link ZoneOffset} or a region. */
  public ZoneId getZone() {
    return zone;
  }
}
