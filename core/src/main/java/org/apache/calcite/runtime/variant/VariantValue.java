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
package org.apache.calcite.runtime.variant;

import org.apache.calcite.runtime.CastSpec;
import org.apache.calcite.runtime.rtti.RuntimeTypeInformation;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.math.RoundingMode;

/** Base class for the runtime support for values of the VARIANT SQL type. */
public abstract class VariantValue {
  // We made this an abstract class rather than an interface,
  // because Janino does not like static methods in interfaces.
  public static @Nullable String getTypeString(Object object) {
    if (object instanceof VariantValue) {
      return ((VariantValue) object).getTypeString();
    }
    return null;
  }

  /** A string describing the runtime type information of this value. */
  public abstract String getTypeString();

  /** Cast this value to the specified type.  Currently, the rule is:
   * if the value has the specified type, the value field is returned, otherwise a SQL
   * NULL is returned. */
  public abstract @Nullable Object cast(RuntimeTypeInformation type);

  /**
   * Converts this value to the type {@code target}, applying the rules of
   * {@code CAST} to each scalar it contains, and throwing if the value does
   * not fit the target.
   *
   * <p>The conversion walks the value and the target type together, recursing
   * through {@code ARRAY}, {@code MULTISET}, {@code MAP} and {@code ROW}
   * targets, and converts each scalar it reaches with
   * {@link org.apache.calcite.runtime.SqlFunctions#cast}. A value that does
   * not fit -- a scalar where an array is wanted, a string that is not a
   * number, a number out of range -- is an error, so that a caller such as
   * {@code JSON_VALUE} can apply its {@code ON ERROR} clause.
   *
   * <p>This differs from {@link #cast}, which implements
   * {@code CAST(variant AS type)} and returns SQL {@code NULL} when the value
   * does not have the requested type.
   *
   * <p>Conventions:
   *
   * <ul>
   * <li>A {@code VARIANT} target returns the value unchanged, at any level.
   * <li>A {@code VARIANT} null, or a {@code VARIANT} holding SQL
   *   {@code NULL}, converts to SQL {@code NULL}.
   * <li>A {@code ROW} target is read from a {@code MAP} (such as a JSON
   *   object) or {@code ROW} value by field name, as {@link #item} looks it
   *   up (so a field named {@code a} needs to be quoted). A field the value
   *   lacks is {@code NULL}; an entry the row type lacks is ignored.
   * <li>A scalar of type {@code BOOLEAN}, a numeric type, or a character type
   *   converts as {@code CAST} converts it. A scalar of another type (such as
   *   a {@code DATE}, held as an {@code int}) converts only to its own type.
   * </ul>
   *
   * @param target       Type to convert to
   * @param roundingMode Rounding mode of a numeric conversion
   * @return The value in the representation generated code uses for
   *         {@code target}, or null
   */
  public abstract @Nullable Object dynamicCast(CastSpec target,
      RoundingMode roundingMode);

  // Implementation of the array index operator for VARIANT values
  public abstract @Nullable Object item(Object index);
}
