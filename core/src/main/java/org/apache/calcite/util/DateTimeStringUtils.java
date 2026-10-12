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

import java.text.SimpleDateFormat;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility methods to manipulate String representation of DateTime values.
 *
 * @see DateTimeUtils
 */
public class DateTimeStringUtils {

  private DateTimeStringUtils() {}

  /** The SimpleDateFormat string for ISO timestamps,
   * "yyyy-MM-dd'T'HH:mm:ss'Z'". */
  public static final String ISO_DATETIME_FORMAT =
      "yyyy-MM-dd'T'HH:mm:ss'Z'";


  /** The SimpleDateFormat string for ISO timestamps with precisions, "yyyy-MM-dd'T'HH:mm:ss
   * .SSS'Z'"*/
  public static final String ISO_DATETIME_FRACTIONAL_SECOND_FORMAT =
      "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'";

  static String pad(int length, long v) {
    StringBuilder s = new StringBuilder(Long.toString(v));
    while (s.length() < length) {
      s.insert(0, "0");
    }
    return s.toString();
  }

  /** Appends hour:minute:second to a buffer; assumes they are valid. */
  static StringBuilder hms(StringBuilder b, int h, int m, int s) {
    int2(b, h);
    b.append(':');
    int2(b, m);
    b.append(':');
    int2(b, s);
    return b;
  }

  /** Appends year-month-day and hour:minute:second to a buffer; assumes they
   * are valid. */
  static StringBuilder ymdhms(StringBuilder b, int year, int month, int day,
      int h, int m, int s) {
    ymd(b, year, month, day);
    b.append(' ');
    hms(b, h, m, s);
    return b;
  }

  /** Appends year-month-day to a buffer; assumes they are valid. */
  static StringBuilder ymd(StringBuilder b, int year, int month, int day) {
    int4(b, year);
    b.append('-');
    int2(b, month);
    b.append('-');
    int2(b, day);
    return b;
  }

  private static void int4(StringBuilder buf, int i) {
    buf.append((char) ('0' + (i / 1000) % 10));
    buf.append((char) ('0' + (i / 100) % 10));
    buf.append((char) ('0' + (i / 10) % 10));
    buf.append((char) ('0' + i % 10));
  }

  private static void int2(StringBuilder buf, int i) {
    buf.append((char) ('0' + (i / 10) % 10));
    buf.append((char) ('0' + i % 10));
  }

  /** Largest time zone displacement allowed in a literal, in seconds
   * (14 hours, as in SQL Server and most other databases). */
  public static final int MAX_OFFSET_SECONDS = 14 * 60 * 60;

  /** Pattern that splits a date-time string with a trailing time zone into
   * the date-time and the zone. The zone may follow a space or be attached
   * directly to the time, as in {@code 2020-01-01 10:00:00+05:30}. */
  private static final Pattern DATE_TIME_ZONE_PATTERN =
      Pattern.compile("^(\\S+ [0-9:.]*[0-9])\\s*([+-]\\d.*|[A-Za-z].*)$");

  /** Pattern that splits a time string with a trailing time zone into
   * the time and the zone. */
  private static final Pattern TIME_ZONE_PATTERN =
      Pattern.compile("^([0-9:.]*[0-9])\\s*([+-]\\d.*|[A-Za-z].*)$");

  /** Splits a string of the form "yyyy-MM-dd HH:mm:ss[.f] zone" into its
   * date-time part and its zone part; the space before the zone is
   * optional if the zone starts with a sign. Returns null if the string
   * does not end with a zone. */
  public static String @Nullable [] splitDateTimeZone(String s) {
    return split(DATE_TIME_ZONE_PATTERN, s.trim());
  }

  /** Splits a string of the form "HH:mm:ss[.f] zone" into its time part
   * and its zone part. Returns null if the string does not end with a
   * zone. */
  public static String @Nullable [] splitTimeZone(String s) {
    return split(TIME_ZONE_PATTERN, s.trim());
  }

  private static String @Nullable [] split(Pattern pattern, String s) {
    final Matcher m = pattern.matcher(s);
    if (!m.matches()) {
      return null;
    }
    return new String[] {m.group(1), m.group(2)};
  }

  /** Parses a time zone, which may be an offset ({@code +05:30},
   * {@code -0800}, {@code +5}, {@code Z}) or a zone ID ({@code UTC},
   * {@code GMT+08:00}, {@code America/New_York}, or an abbreviation such as
   * {@code PST}).
   *
   * <p>Offsets are returned as {@link ZoneOffset}; region names are kept as
   * given, not resolved to an offset.
   *
   * @throws DateTimeException if the zone is not valid, or is an offset
   * larger than {@link #MAX_OFFSET_SECONDS}
   */
  public static ZoneId parseZone(String zone) {
    final String z = zone.trim();
    if (z.equals("Z") || z.equals("z")) {
      return ZoneOffset.UTC;
    }
    if (z.startsWith("+") || z.startsWith("-")) {
      final ZoneOffset offset = ZoneOffset.of(z);
      checkOffset(offset);
      return offset;
    }
    ZoneId zoneId;
    try {
      zoneId = ZoneId.of(z);
    } catch (DateTimeException e) {
      // Allow abbreviations such as "PST", as java.util.TimeZone does.
      if (!ZoneId.SHORT_IDS.containsKey(z)) {
        throw e;
      }
      zoneId = ZoneId.of(z, ZoneId.SHORT_IDS);
    }
    if (zoneId instanceof ZoneOffset) {
      checkOffset((ZoneOffset) zoneId);
    } else if (zoneId.getRules().isFixedOffset()) {
      // "GMT+15:00", "UTC-14:30"
      checkOffset(zoneId.getRules().getOffset(Instant.EPOCH));
    }
    return zoneId;
  }

  private static void checkOffset(ZoneOffset offset) {
    if (Math.abs(offset.getTotalSeconds()) > MAX_OFFSET_SECONDS) {
      throw new DateTimeException("Time zone offset " + offset.getId()
          + " is out of range; it must be between -14:00 and +14:00");
    }
  }

  /** Converts a {@link TimeZone} to a {@link ZoneId}, keeping the zone's ID
   * where possible (for example, {@code PST} becomes
   * {@code America/Los_Angeles}, but {@code GMT+05:45} stays as is). */
  public static ZoneId toZoneId(TimeZone timeZone) {
    final String id = timeZone.getID();
    try {
      return ZoneId.of(id);
    } catch (DateTimeException e) {
      return timeZone.toZoneId();
    }
  }

  /** Returns the canonical text of a zone: a zone ID such as
   * {@code America/New_York}, or an offset such as {@code +05:30};
   * a zero offset is written {@code +00:00}, not {@code Z}. */
  public static String zoneToString(ZoneId zone) {
    if (zone instanceof ZoneOffset && ((ZoneOffset) zone).getTotalSeconds() == 0) {
      return "+00:00";
    }
    return zone.getId();
  }

  static boolean isValidTimeZone(final String timeZone) {
    if (timeZone.equals("GMT")) {
      return true;
    } else {
      String id = TimeZone.getTimeZone(timeZone).getID();
      if (!id.equals("GMT")) {
        return true;
      }
    }
    return false;
  }

  /**
   * Create a SimpleDateFormat with format string with default time zone UTC.
   */
  public static SimpleDateFormat getDateFormatter(String format) {
    return getDateFormatter(format, DateTimeUtils.UTC_ZONE);
  }

  /**
   * Create a SimpleDateFormat with format string and time zone.
   */
  public static SimpleDateFormat getDateFormatter(String format, TimeZone timeZone) {
    final SimpleDateFormat dateFormatter =
        new SimpleDateFormat(format, Locale.ROOT);
    dateFormatter.setTimeZone(timeZone);
    return dateFormatter;
  }
}
