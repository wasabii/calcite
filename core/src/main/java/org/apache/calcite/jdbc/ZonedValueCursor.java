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
package org.apache.calcite.jdbc;

import org.apache.calcite.avatica.ColumnMetaData;
import org.apache.calcite.avatica.util.ArrayImpl;
import org.apache.calcite.avatica.util.Cursor;
import org.apache.calcite.runtime.SqlFunctions;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.joou.UByte;
import org.joou.UInteger;
import org.joou.ULong;
import org.joou.UShort;

import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.URL;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.NClob;
import java.sql.Ref;
import java.sql.SQLException;
import java.sql.SQLXML;
import java.sql.Struct;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Map;

/**
 * Cursor that wraps another cursor and provides accessors for columns of
 * type TIMESTAMP WITH TIME ZONE and TIME WITH TIME ZONE.
 *
 * <p>At runtime, values of those types are {@link ZonedDateTime} and
 * {@link OffsetTime}. Avatica can only access such columns if their values
 * are strings, so this cursor accesses them as objects and converts them:
 *
 * <ul>
 * <li>{@code getObject()} returns an {@link OffsetDateTime} (or
 *   {@link OffsetTime}), the standard JDBC mapping;
 * <li>{@code getObject(ZonedDateTime.class)} returns the value with its zone,
 *   which may be a region such as {@code America/New_York};
 * <li>{@code getString()} returns the value in its own zone, for example
 *   {@code 2020-01-01 10:00:00 +05:30};
 * <li>{@code getTimestamp()} returns the instant.
 * </ul>
 */
class ZonedValueCursor implements Cursor {
  private final Cursor cursor;

  ZonedValueCursor(Cursor cursor) {
    this.cursor = cursor;
  }

  /** Returns whether a column contains zoned values. */
  static boolean isZoned(ColumnMetaData column) {
    switch (column.type.id) {
    case Types.TIMESTAMP_WITH_TIMEZONE:
    case Types.TIME_WITH_TIMEZONE:
      return column.type.rep == ColumnMetaData.Rep.OBJECT;
    default:
      return false;
    }
  }

  /** Returns whether any column contains zoned values. */
  static boolean anyZoned(List<ColumnMetaData> columns) {
    return columns.stream().anyMatch(ZonedValueCursor::isZoned);
  }

  @Override public List<Accessor> createAccessors(List<ColumnMetaData> types,
      Calendar localCalendar, ArrayImpl.Factory factory) {
    // Ask the underlying cursor for plain object accessors for the zoned
    // columns, then wrap them.
    final List<ColumnMetaData> types2 = new ArrayList<>();
    for (ColumnMetaData column : types) {
      types2.add(isZoned(column)
          ? ColumnMetaData.dummy(
              ColumnMetaData.scalar(Types.JAVA_OBJECT, column.type.name,
                  ColumnMetaData.Rep.OBJECT),
              column.nullable != 0)
          : column);
    }
    final List<Accessor> accessors =
        cursor.createAccessors(types2, localCalendar, factory);
    final List<Accessor> accessors2 = new ArrayList<>();
    for (int i = 0; i < types.size(); i++) {
      final ColumnMetaData column = types.get(i);
      final Accessor accessor = accessors.get(i);
      accessors2.add(isZoned(column)
          ? new ZonedAccessor(accessor, column.precision)
          : accessor);
    }
    return accessors2;
  }

  @Override public boolean next() throws SQLException {
    return cursor.next();
  }

  @Override public void close() {
    cursor.close();
  }

  @Override public boolean wasNull() throws SQLException {
    return cursor.wasNull();
  }

  /** Accessor for a TIMESTAMP WITH TIME ZONE or TIME WITH TIME ZONE
   * column. */
  private static class ZonedAccessor implements Accessor {
    private final Accessor accessor;
    private final int precision;

    ZonedAccessor(Accessor accessor, int precision) {
      this.accessor = accessor;
      this.precision = precision;
    }

    /** Returns the runtime value, a ZonedDateTime or OffsetTime. */
    private @Nullable Object value() throws SQLException {
      return accessor.getObject();
    }

    private static SQLException cannotConvert(Object o, Class<?> type) {
      return new SQLException("cannot convert " + o.getClass().getName()
          + " to " + type.getName());
    }

    private @Nullable Instant instant() throws SQLException {
      final Object o = value();
      if (o == null) {
        return null;
      }
      if (o instanceof ZonedDateTime) {
        return ((ZonedDateTime) o).toInstant();
      }
      throw cannotConvert(o, Instant.class);
    }

    @Override public boolean wasNull() throws SQLException {
      return accessor.wasNull();
    }

    @SuppressWarnings("override.return")
    @Override public @Nullable String getString() throws SQLException {
      final Object o = value();
      if (o instanceof ZonedDateTime) {
        return SqlFunctions.timestampTzToString((ZonedDateTime) o, precision);
      }
      if (o instanceof OffsetTime) {
        return SqlFunctions.timeTzToString((OffsetTime) o, precision);
      }
      return o == null ? null : o.toString();
    }

    @SuppressWarnings("override.return")
    @Override public @Nullable Object getObject() throws SQLException {
      final Object o = value();
      if (o instanceof ZonedDateTime) {
        return ((ZonedDateTime) o).toOffsetDateTime();
      }
      return o;
    }

    @SuppressWarnings("override.return")
    @Override public <T> @Nullable T getObject(Class<T> type)
        throws SQLException {
      final Object o = value();
      if (o == null) {
        return null;
      }
      if (type.isInstance(o)) {
        return type.cast(o);
      }
      if (type == String.class) {
        return type.cast(getString());
      }
      if (o instanceof ZonedDateTime) {
        final ZonedDateTime z = (ZonedDateTime) o;
        if (type == OffsetDateTime.class) {
          return type.cast(z.toOffsetDateTime());
        } else if (type == Instant.class) {
          return type.cast(z.toInstant());
        } else if (type == Timestamp.class) {
          return type.cast(Timestamp.from(z.toInstant()));
        } else if (type == OffsetTime.class) {
          return type.cast(z.toOffsetDateTime().toOffsetTime());
        }
      }
      throw cannotConvert(o, type);
    }

    @SuppressWarnings("override.return")
    @Override public @Nullable Object getObject(
        @Nullable Map<String, Class<?>> map) throws SQLException {
      return getObject();
    }

    @SuppressWarnings("override.return")
    @Override public @Nullable Timestamp getTimestamp(
        @Nullable Calendar calendar) throws SQLException {
      final Instant instant = instant();
      return instant == null ? null : Timestamp.from(instant);
    }

    @SuppressWarnings("override.return")
    @Override public @Nullable Date getDate(@Nullable Calendar calendar)
        throws SQLException {
      final Instant instant = instant();
      return instant == null ? null : new Date(instant.toEpochMilli());
    }

    @SuppressWarnings("override.return")
    @Override public @Nullable Time getTime(@Nullable Calendar calendar)
        throws SQLException {
      final Instant instant = instant();
      return instant == null ? null : new Time(instant.toEpochMilli());
    }

    // The remaining methods are not supported for these types; the
    // underlying accessor throws.

    @Override public boolean getBoolean() throws SQLException {
      return accessor.getBoolean();
    }

    @Override public byte getByte() throws SQLException {
      return accessor.getByte();
    }

    @Override public UByte getUByte() throws SQLException {
      return accessor.getUByte();
    }

    @Override public short getShort() throws SQLException {
      return accessor.getShort();
    }

    @Override public UShort getUShort() throws SQLException {
      return accessor.getUShort();
    }

    @Override public int getInt() throws SQLException {
      return accessor.getInt();
    }

    @Override public UInteger getUInt() throws SQLException {
      return accessor.getUInt();
    }

    @Override public long getLong() throws SQLException {
      return accessor.getLong();
    }

    @Override public ULong getULong() throws SQLException {
      return accessor.getULong();
    }

    @Override public float getFloat() throws SQLException {
      return accessor.getFloat();
    }

    @Override public double getDouble() throws SQLException {
      return accessor.getDouble();
    }

    @Override public BigDecimal getBigDecimal() throws SQLException {
      return accessor.getBigDecimal();
    }

    @Override public BigDecimal getBigDecimal(int scale) throws SQLException {
      return accessor.getBigDecimal(scale);
    }

    @Override public byte[] getBytes() throws SQLException {
      return accessor.getBytes();
    }

    @Override public InputStream getAsciiStream() throws SQLException {
      return accessor.getAsciiStream();
    }

    @Override public InputStream getUnicodeStream() throws SQLException {
      return accessor.getUnicodeStream();
    }

    @Override public InputStream getBinaryStream() throws SQLException {
      return accessor.getBinaryStream();
    }

    @Override public Reader getCharacterStream() throws SQLException {
      return accessor.getCharacterStream();
    }

    @Override public Ref getRef() throws SQLException {
      return accessor.getRef();
    }

    @Override public Blob getBlob() throws SQLException {
      return accessor.getBlob();
    }

    @Override public Clob getClob() throws SQLException {
      return accessor.getClob();
    }

    @Override public Array getArray() throws SQLException {
      return accessor.getArray();
    }

    @Override public Struct getStruct() throws SQLException {
      return accessor.getStruct();
    }

    @Override public URL getURL() throws SQLException {
      return accessor.getURL();
    }

    @Override public NClob getNClob() throws SQLException {
      return accessor.getNClob();
    }

    @Override public SQLXML getSQLXML() throws SQLException {
      return accessor.getSQLXML();
    }

    @Override public String getNString() throws SQLException {
      return accessor.getNString();
    }

    @Override public Reader getNCharacterStream() throws SQLException {
      return accessor.getNCharacterStream();
    }
  }
}
