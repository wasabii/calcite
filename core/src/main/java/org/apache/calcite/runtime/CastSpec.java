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
package org.apache.calcite.runtime;

import org.apache.calcite.sql.type.SqlTypeName;

import com.google.common.collect.ImmutableList;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;
import java.util.Objects;

import static java.util.Objects.requireNonNull;

/**
 * The target of a runtime conversion: the type that
 * {@link org.apache.calcite.runtime.variant.VariantValue#dynamicCast}
 * converts a value to when the type of the value is not known until run
 * time (used, for example, by {@code JSON_VALUE} and {@code JSON_QUERY}).
 *
 * <p>It is a lightweight, {@code org.apache.calcite.rel}-free stand-in for
 * the parts of a {@code RelDataType} that a conversion reads: the
 * {@link SqlTypeName}, the precision and scale, and, for a composite type,
 * the specs of its components. Unlike
 * {@link org.apache.calcite.runtime.rtti.RuntimeTypeInformation}, which
 * describes the type of the value a {@code VARIANT} holds and so
 * deliberately merges types ({@code CHAR} with {@code VARCHAR}, every
 * {@code DECIMAL} precision), it keeps the distinctions a conversion depends
 * on: {@code CHAR(n)} pads and {@code DECIMAL(p, s)} rounds.
 *
 * <p>The components are: the element of an {@code ARRAY} or
 * {@code MULTISET}; the key and value of a {@code MAP}; the fields of a
 * {@code ROW}, with their names.
 *
 * <p>A negative {@link #getPrecision() precision} or
 * {@link #getScale() scale} means the type does not specify one.
 */
public class CastSpec {
  private final SqlTypeName typeName;
  private final int precision;
  private final int scale;
  private final ImmutableList<CastSpec> components;
  private final ImmutableList<String> fieldNames;

  private CastSpec(SqlTypeName typeName, int precision, int scale,
      List<CastSpec> components, List<String> fieldNames) {
    this.typeName = requireNonNull(typeName, "typeName");
    this.precision = precision < 0 ? -1 : precision;
    this.scale = scale < 0 ? -1 : scale;
    this.components = ImmutableList.copyOf(components);
    this.fieldNames = ImmutableList.copyOf(fieldNames);
  }

  /** Creates a spec for a scalar type; pass a negative precision or scale
   * that the type does not specify. */
  public CastSpec(SqlTypeName typeName, int precision, int scale) {
    this(typeName, precision, scale, ImmutableList.of(), ImmutableList.of());
  }

  /** Creates a spec for an {@code ARRAY} or {@code MULTISET} (one component,
   * the element) or a {@code MAP} (two components, the key and the
   * value). */
  public CastSpec(SqlTypeName typeName, CastSpec... components) {
    this(typeName, -1, -1, ImmutableList.copyOf(components),
        ImmutableList.of());
    switch (typeName) {
    case ARRAY:
    case MULTISET:
      checkArgument(components.length == 1, typeName);
      break;
    case MAP:
      checkArgument(components.length == 2, typeName);
      break;
    default:
      throw new IllegalArgumentException("not a collection type: " + typeName);
    }
  }

  /** Creates a spec for a {@code ROW} with the given field names and field
   * specs. */
  public CastSpec(String[] fieldNames, CastSpec[] fieldSpecs) {
    this(SqlTypeName.ROW, -1, -1, ImmutableList.copyOf(fieldSpecs),
        ImmutableList.copyOf(fieldNames));
    checkArgument(fieldNames.length == fieldSpecs.length, SqlTypeName.ROW);
  }

  private static void checkArgument(boolean b, SqlTypeName typeName) {
    if (!b) {
      throw new IllegalArgumentException("wrong number of components for "
          + typeName);
    }
  }

  public SqlTypeName getTypeName() {
    return typeName;
  }

  /** Returns the precision, or -1 if the type does not specify one. */
  public int getPrecision() {
    return precision;
  }

  /** Returns the scale, or -1 if the type does not specify one. */
  public int getScale() {
    return scale;
  }

  /** Returns the element spec of an {@code ARRAY} or {@code MULTISET}. */
  public CastSpec getComponent() {
    return components.get(0);
  }

  /** Returns the key spec of a {@code MAP}. */
  public CastSpec getKey() {
    return components.get(0);
  }

  /** Returns the value spec of a {@code MAP}. */
  public CastSpec getValue() {
    return components.get(1);
  }

  /** Returns the number of fields of a {@code ROW}. */
  public int getFieldCount() {
    return fieldNames.size();
  }

  /** Returns the name of the {@code i}th field of a {@code ROW}. */
  public String getFieldName(int i) {
    return fieldNames.get(i);
  }

  /** Returns the spec of the {@code i}th field of a {@code ROW}. */
  public CastSpec getField(int i) {
    return components.get(i);
  }

  @Override public boolean equals(@Nullable Object o) {
    return this == o
        || o instanceof CastSpec
        && typeName == ((CastSpec) o).typeName
        && precision == ((CastSpec) o).precision
        && scale == ((CastSpec) o).scale
        && components.equals(((CastSpec) o).components)
        && fieldNames.equals(((CastSpec) o).fieldNames);
  }

  @Override public int hashCode() {
    return Objects.hash(typeName, precision, scale, components, fieldNames);
  }

  @Override public String toString() {
    switch (typeName) {
    case ARRAY:
    case MULTISET:
      return getComponent() + " " + typeName;
    case MAP:
      return "MAP<" + getKey() + ", " + getValue() + ">";
    case ROW:
      final StringBuilder b = new StringBuilder("ROW(");
      for (int i = 0; i < getFieldCount(); i++) {
        if (i > 0) {
          b.append(", ");
        }
        b.append(getFieldName(i)).append(' ').append(getField(i));
      }
      return b.append(')').toString();
    default:
      if (precision < 0) {
        return typeName.getName();
      }
      return typeName.getName() + "(" + precision
          + (scale < 0 ? "" : ", " + scale) + ")";
    }
  }
}
