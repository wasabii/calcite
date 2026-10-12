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
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Calendar;
import java.util.TimeZone;

import static com.google.common.base.Preconditions.checkArgument;

import static org.apache.calcite.avatica.util.DateTimeUtils.TIMESTAMP_FORMAT_STRING;
import static org.apache.calcite.util.Static.RESOURCE;

import static java.lang.Math.floorMod;

/**
 * Timestamp with time-zone literal.
 *
 * <p>Immutable. The value consists of a local date-time and a zone; the zone
 * is either an offset from UTC (such as {@code +05:30}, as in the SQL
 * standard) or, as a Calcite extension, a region (such as
 * {@code America/New_York}), which is preserved as given.
 *
 * <p>The canonical string form is
 * {@code yyyy-MM-dd HH:mm:ss[.fraction] zone}, and supports unlimited
 * precision (milliseconds, nanoseconds).
 *
 * <p>{@link #equals} and {@link #hashCode} are based on the canonical string,
 * so two values that denote the same instant in different zones are not
 * equal. {@link #compareTo} compares instants only, as SQL does, and is
 * therefore inconsistent with {@code equals} (as is
 * {@link java.math.BigDecimal}). Use {@link #isSameInstant} for SQL
 * equality.
 */
public class TimestampWithTimeZoneString
    implements Comparable<TimestampWithTimeZoneString> {

  final TimestampString localDateTime;
  final ZoneId zone;
  final String v;

  /** Creates a TimestampWithTimeZoneString. */
  public TimestampWithTimeZoneString(TimestampString localDateTime,
      ZoneId zone) {
    this.localDateTime = localDateTime;
    this.zone = zone;
    this.v = localDateTime + " " + DateTimeStringUtils.zoneToString(zone);
  }

  /** Creates a TimestampWithTimeZoneString. */
  public TimestampWithTimeZoneString(TimestampString localDateTime,
      TimeZone timeZone) {
    this(localDateTime, DateTimeStringUtils.toZoneId(timeZone));
  }

  /** Creates a TimestampWithTimeZoneString from a string such as
   * {@code 2020-01-01 10:00:00 America/New_York} or
   * {@code 2020-01-01 10:00:00.123+05:30}. */
  public TimestampWithTimeZoneString(String v) {
    this(parse(v));
  }

  private TimestampWithTimeZoneString(TimestampWithTimeZoneString t) {
    this(t.localDateTime, t.zone);
  }

  /** Creates a TimestampWithTimeZoneString for year, month, day, hour, minute,
   * second, millisecond values in the given time-zone. */
  public TimestampWithTimeZoneString(int year, int month, int day, int h, int m, int s,
      String timeZone) {
    this(DateTimeStringUtils.ymdhms(new StringBuilder(), year, month, day, h, m, s).toString()
        + " " + timeZone);
  }

  private static TimestampWithTimeZoneString parse(String v) {
    final String[] parts = DateTimeStringUtils.splitDateTimeZone(v);
    if (parts == null) {
      throw RESOURCE.illegalLiteral("TIMESTAMP WITH TIME ZONE", v,
          RESOURCE.badFormat(TIMESTAMP_FORMAT_STRING + " zone").str()).ex();
    }
    final ZoneId zone;
    try {
      zone = DateTimeStringUtils.parseZone(parts[1]);
    } catch (DateTimeException e) {
      throw new IllegalArgumentException(e.getMessage(), e);
    }
    if (!isValidDateTime(parts[0])) {
      throw RESOURCE.illegalLiteral("TIMESTAMP WITH TIME ZONE", v,
          RESOURCE.badFormat(TIMESTAMP_FORMAT_STRING + " zone").str()).ex();
    }
    return new TimestampWithTimeZoneString(new TimestampString(parts[0]),
        zone);
  }

  /** Returns whether a string such as "2020-01-01 10:00:00.123" is a valid
   * date and time between years 1 and 9999. */
  private static boolean isValidDateTime(String s) {
    // java.time parses at most 9 fractional digits
    final int dot = s.indexOf('.');
    final String s2 = dot >= 0 && s.length() > dot + 10
        ? s.substring(0, dot + 10)
        : s;
    try {
      final LocalDateTime t = LocalDateTime.parse(s2.replace(' ', 'T'));
      return t.getYear() >= 1 && t.getYear() <= 9999;
    } catch (DateTimeException e) {
      return false;
    }
  }

  /** Sets the fraction field of a {@code TimestampWithTimeZoneString} to a given number
   * of milliseconds. Nukes the value set via {@link #withNanos}.
   *
   * <p>For example,
   * {@code new TimestampWithTimeZoneString(1970, 1, 1, 2, 3, 4, "GMT").withMillis(56)}
   * yields {@code TIMESTAMP WITH TIME ZONE '1970-01-01 02:03:04.056 GMT'}. */
  public TimestampWithTimeZoneString withMillis(int millis) {
    checkArgument(millis >= 0 && millis < 1000);
    return withFraction(DateTimeStringUtils.pad(3, millis));
  }

  /** Sets the fraction field of a {@code TimestampWithTimeZoneString} to a given number
   * of nanoseconds. Nukes the value set via {@link #withMillis(int)}.
   *
   * <p>For example,
   * {@code new TimestampWithTimeZoneString(1970, 1, 1, 2, 3, 4, "GMT").withNanos(56789)}
   * yields {@code TIMESTAMP WITH TIME ZONE '1970-01-01 02:03:04.000056789 GMT'}. */
  public TimestampWithTimeZoneString withNanos(int nanos) {
    checkArgument(nanos >= 0 && nanos < 1000000000);
    return withFraction(DateTimeStringUtils.pad(9, nanos));
  }

  /** Sets the fraction field of a {@code TimestampString}.
   * The precision is determined by the number of leading zeros.
   * Trailing zeros are stripped.
   *
   * <p>For example, {@code
   * new TimestampWithTimeZoneString(1970, 1, 1, 2, 3, 4, "GMT").withFraction("00506000")}
   * yields {@code TIMESTAMP WITH TIME ZONE '1970-01-01 02:03:04.00506 GMT'}. */
  public TimestampWithTimeZoneString withFraction(String fraction) {
    return new TimestampWithTimeZoneString(
        localDateTime.withFraction(fraction), zone);
  }

  /** Creates a TimestampWithTimeZoneString from a Calendar. */
  public static TimestampWithTimeZoneString fromCalendarFields(Calendar calendar) {
    TimestampString ts = TimestampString.fromCalendarFields(calendar);
    return new TimestampWithTimeZoneString(ts, calendar.getTimeZone());
  }

  /** Creates a TimestampWithTimeZoneString from a {@link ZonedDateTime},
   * keeping its zone. */
  public static TimestampWithTimeZoneString fromZonedDateTime(
      ZonedDateTime zonedDateTime) {
    return new TimestampWithTimeZoneString(
        toTimestampString(zonedDateTime.toLocalDateTime()),
        zonedDateTime.getZone());
  }

  /** Converts a {@link LocalDateTime} to a {@link TimestampString}. */
  static TimestampString toTimestampString(LocalDateTime t) {
    return new TimestampString(t.getYear(), t.getMonthValue(),
        t.getDayOfMonth(), t.getHour(), t.getMinute(), t.getSecond())
        .withNanos(t.getNano());
  }

  /** Converts a {@link TimestampString} to a {@link LocalDateTime}. */
  static LocalDateTime toLocalDateTime(TimestampString t) {
    return LocalDateTime.parse(t.toString().replace(' ', 'T'));
  }

  /** Returns a value that denotes the same instant in a different time
   * zone. */
  public TimestampWithTimeZoneString withTimeZone(TimeZone timeZone) {
    return withZone(DateTimeStringUtils.toZoneId(timeZone));
  }

  /** Returns a value that denotes the same instant in a different zone. */
  public TimestampWithTimeZoneString withZone(ZoneId zone) {
    if (this.zone.equals(zone)) {
      return this;
    }
    return fromZonedDateTime(toZonedDateTime().withZoneSameInstant(zone));
  }

  /** Converts this value to a {@link ZonedDateTime}.
   *
   * <p>If the local date-time falls in a gap in the zone (for example,
   * during a daylight-saving transition), it is moved later by the length of
   * the gap; if it falls in an overlap, the earlier offset is used. */
  public ZonedDateTime toZonedDateTime() {
    return ZonedDateTime.of(toLocalDateTime(localDateTime), zone);
  }

  /** Returns the instant this value denotes. */
  public Instant toInstant() {
    return toZonedDateTime().toInstant();
  }

  /** Returns the number of milliseconds between the UNIX epoch
   * (1970-01-01 00:00:00 UTC) and the instant this value denotes. */
  public long getMillisSinceEpoch() {
    return toInstant().toEpochMilli();
  }

  /** Returns the instant this value denotes, as a timestamp in UTC. */
  public TimestampString getUtcTimestampString() {
    return withZone(ZoneOffset.UTC).localDateTime;
  }

  /** Returns whether this value denotes the same instant as another,
   * regardless of zone. This is the SQL notion of equality. */
  public boolean isSameInstant(TimestampWithTimeZoneString o) {
    return toInstant().equals(o.toInstant());
  }

  @Override public String toString() {
    return v;
  }

  @Override public boolean equals(@Nullable Object o) {
    // The value is in canonical form (no trailing zeros).
    return o == this
        || o instanceof TimestampWithTimeZoneString
        && ((TimestampWithTimeZoneString) o).v.equals(v);
  }

  @Override public int hashCode() {
    return v.hashCode();
  }

  @Override public int compareTo(TimestampWithTimeZoneString o) {
    return toInstant().compareTo(o.toInstant());
  }

  public TimestampWithTimeZoneString round(int precision) {
    checkArgument(precision >= 0);
    return new TimestampWithTimeZoneString(
        localDateTime.round(precision), zone);
  }

  /** Creates a TimestampWithTimeZoneString that is a given number of milliseconds since
   * the epoch UTC. */
  public static TimestampWithTimeZoneString fromMillisSinceEpoch(long millis) {
    return new TimestampWithTimeZoneString(
        new TimestampString(DateTimeUtils.unixTimestampToString(millis))
            .withMillis((int) floorMod(millis, 1000L)),
        DateTimeUtils.UTC_ZONE);
  }

  /** Converts this TimestampWithTimeZoneString to a string, truncated or padded with
   * zeros to a given precision. */
  public String toString(int precision) {
    checkArgument(precision >= 0);
    return localDateTime.toString(precision) + " "
        + DateTimeStringUtils.zoneToString(zone);
  }

  public DateString getLocalDateString() {
    return new DateString(localDateTime.toString().substring(0, 10));
  }

  public TimeString getLocalTimeString() {
    return new TimeString(localDateTime.toString().substring(11));
  }

  public TimestampString getLocalTimestampString() {
    return localDateTime;
  }

  public TimeZone getTimeZone() {
    return TimeZone.getTimeZone(zone);
  }

  /** Returns the zone: a {@link ZoneOffset} or a region. */
  public ZoneId getZone() {
    return zone;
  }
}
