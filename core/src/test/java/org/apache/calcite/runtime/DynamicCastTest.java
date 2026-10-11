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

import org.apache.calcite.runtime.rtti.BasicSqlTypeRtti;
import org.apache.calcite.runtime.rtti.GenericSqlTypeRtti;
import org.apache.calcite.runtime.rtti.RuntimeTypeInformation;
import org.apache.calcite.runtime.rtti.RuntimeTypeInformation.RuntimeSqlTypeName;
import org.apache.calcite.runtime.variant.VariantNull;
import org.apache.calcite.runtime.variant.VariantSqlValue;
import org.apache.calcite.runtime.variant.VariantValue;
import org.apache.calcite.sql.type.SqlTypeName;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.CoreMatchers.sameInstance;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasToString;
import static org.junit.jupiter.api.Assertions.assertThrows;

import static java.util.Objects.requireNonNull;

/**
 * Tests {@link JsonFunctions#toVariant}, which turns a JSON document into a
 * tree of {@code VARIANT} values, and {@link VariantValue#dynamicCast}, which converts such
 * a tree to the type a {@link CastSpec} describes.
 *
 * <p>Test case for
 * <a href="https://issues.apache.org/jira/browse/CALCITE-7801">[CALCITE-7801]
 * JSON_VALUE(..., RETURNING DOUBLE) throws ClassCastException when the JSON
 * number is an integer</a>.
 */
class DynamicCastTest {
  private static final RoundingMode DOWN = RoundingMode.DOWN;

  private static final CastSpec INTEGER = basic(SqlTypeName.INTEGER);
  private static final CastSpec VARCHAR = basic(SqlTypeName.VARCHAR);
  private static final CastSpec DOUBLE = basic(SqlTypeName.DOUBLE);
  private static final CastSpec DECIMAL = basic(SqlTypeName.DECIMAL);
  private static final CastSpec VARIANT = basic(SqlTypeName.VARIANT);

  /** Tests that each JSON value becomes a VARIANT of its natural type. */
  @Test void testNaturalTypes() {
    assertType("5", "INTEGER");
    assertType("-2147483648", "INTEGER");
    assertType("2147483648", "BIGINT");
    assertType("9223372036854775807", "BIGINT");
    assertType("\"a\"", "VARCHAR");
    assertType("true", "BOOLEAN");
    assertType("[1]", "ARRAY");
    assertType("{\"a\":1}", "MAP");

    // A number with a fraction keeps the digits of its literal.
    assertType("1.50", "DECIMAL");
    assertThat(cast("1.50", DECIMAL), is(new BigDecimal("1.50")));
    assertThat(cast("0.1", DECIMAL), is(new BigDecimal("0.1")));

    // An exponent is applied, rather than giving a negative scale.
    assertType("1e2", "DECIMAL");
    assertThat(cast("1e2", DECIMAL), is(new BigDecimal("100")));
    assertThat(cast("1.5e-3", DECIMAL), is(new BigDecimal("0.0015")));

    // An integer beyond BIGINT, and a fraction with more digits than the
    // default maximum DECIMAL precision (19), are DECIMALs that keep every
    // digit.
    assertType("92233720368547758070", "DECIMAL");
    assertThat(cast("92233720368547758070", DECIMAL),
        is(new BigDecimal("92233720368547758070")));
    assertThat(cast("1.2345678901234567890123", DECIMAL),
        is(new BigDecimal("1.2345678901234567890123")));

    // JSON null is the VARIANT null.
    assertThat(variant("null"), sameInstance(VariantNull.getInstance()));
  }

  /** Tests that the elements of an array and the members of an object are
   * VARIANTs of their own natural types, wrapped once, and that JSON null
   * among them is the VARIANT null. */
  @Test void testChildrenWrappedOnce() {
    final VariantValue array = variant("[1, \"a\", null, [2]]");
    assertThat(typeString(array.item(1)), is("INTEGER"));
    assertThat(typeString(array.item(2)), is("VARCHAR"));
    assertThat(array.item(3), sameInstance(VariantNull.getInstance()));
    assertThat(typeString(array.item(4)), is("ARRAY"));

    final VariantValue object = variant("{\"a\":1.5, \"b\":null}");
    assertThat(typeString(object.item("a")), is("DECIMAL"));
    assertThat(object.item("b"), sameInstance(VariantNull.getInstance()));
    assertThat(object.item("c"), nullValue());
  }

  /** Tests the same conversion from a document parsed with doubles, as a
   * {@code FORMAT JSON} value is. */
  @Test void testDoubleBecomesDecimal() {
    final VariantValue v = JsonFunctions.toVariant(1.5d, DOWN);
    assertThat(v.getTypeString(), is("DECIMAL"));
    assertThat(v.dynamicCast(DECIMAL, DOWN),
        is(new BigDecimal("1.5")));
  }

  @Test void testScalar() {
    assertThat(cast("1", DOUBLE), is(1.0d));
    assertThat(cast("1.5", INTEGER), is(1));
    assertThat(cast("\"100\"", INTEGER), is(100));
    assertThat(cast("1.5", VARCHAR), is("1.5"));
    // To a character type, a value is spelled as CAST spells it.
    assertThat(cast("true", VARCHAR), is("TRUE"));
    assertThat(cast("0.5", VARCHAR), is(".5"));
    assertThat(cast("1.005", basic(SqlTypeName.DECIMAL, 5, 2)),
        hasToString("1.00"));
    assertThat(cast("\"ab\"", basic(SqlTypeName.CHAR, 4, -1)),
        is("ab  "));
    assertThat(cast("\"abcdef\"", basic(SqlTypeName.VARCHAR, 3, -1)),
        is("abc"));
    assertThat(cast("null", INTEGER), nullValue());

    // A failed conversion is an error, not NULL.
    assertThrows(NumberFormatException.class,
        () -> cast("\"abc\"", INTEGER));
    assertThrows(ArithmeticException.class,
        () -> cast("1000", basic(SqlTypeName.TINYINT)));
    assertThrows(CalciteException.class, () -> cast("[1]", INTEGER));
    assertThrows(CalciteException.class, () -> cast("{\"a\":1}", VARCHAR));
  }

  @Test void testArray() {
    assertThat(cast("[1, \"2\", 3.5, null]", array(INTEGER)),
        is(Arrays.asList(1, 2, 3, null)));
    assertThat(cast("[[1, 2], [3]]", array(array(DOUBLE))),
        is(Arrays.asList(Arrays.asList(1.0d, 2.0d), Arrays.asList(3.0d))));

    // An ARRAY of VARIANT keeps the elements, VARIANT null included.
    final List<?> variants = (List<?>) cast("[1, null]", array(VARIANT));
    assertThat(requireNonNull(variants, "variants").get(0), instanceOf(VariantValue.class));
    assertThat(variants.get(1), sameInstance(VariantNull.getInstance()));

    // The shape of the value must match the type.
    assertThrows(CalciteException.class,
        () -> cast("{\"a\":1}", array(INTEGER)));
    assertThrows(CalciteException.class,
        () -> cast("[1, [2]]", array(INTEGER)));
    assertThrows(CalciteException.class,
        () -> cast("[1, 2]", array(array(INTEGER))));
  }

  @Test void testMap() {
    final Map<Object, Object> expected = new LinkedHashMap<>();
    expected.put("a", 1.0d);
    expected.put("b", 2.5d);
    expected.put("c", null);
    assertThat(cast("{\"a\":1, \"b\":2.5, \"c\":null}", map(VARCHAR, DOUBLE)),
        is(expected));

    // Keys convert too.
    final Map<Object, Object> intKeys = new LinkedHashMap<>();
    intKeys.put(1, "x");
    assertThat(cast("{\"1\":\"x\"}", map(INTEGER, VARCHAR)), is(intKeys));

    assertThrows(CalciteException.class,
        () -> cast("[1]", map(VARCHAR, INTEGER)));
  }

  @Test void testRow() {
    // A field is matched by its exact name, as VariantValue.item looks it
    // up; a field the object lacks is NULL, and a member the row lacks is
    // ignored.
    final CastSpec row =
        row("A", INTEGER, "b", VARCHAR, "c", DOUBLE);
    final Object[] result =
        (Object[]) requireNonNull(
            cast("{\"a\":1, \"b\":\"x\", \"d\":true}", row));
    assertThat(Arrays.asList(result), is(Arrays.asList(null, "x", null)));

    // A row nests other types.
    final Object[] nested =
        (Object[]) requireNonNull(
            cast("{\"x\":[{\"y\":\"1\"}]}",
                row("x", array(row("y", INTEGER)))));
    final List<?> xs = (List<?>) requireNonNull(nested[0]);
    assertThat(Arrays.asList((Object[]) requireNonNull(xs.get(0))),
        is(Arrays.asList(1)));

    assertThrows(CalciteException.class, () -> cast("[1]", row("a", INTEGER)));
  }

  /** Tests a VARIANT built from a SQL value rather than JSON: a value held
   * in an internal representation (a DATE is an int) converts only to its
   * own type. */
  @Test void testSqlVariant() {
    final VariantValue v =
        VariantSqlValue.create(DOWN, 18262,
            new BasicSqlTypeRtti(RuntimeSqlTypeName.DATE));
    assertThat(v.dynamicCast(basic(SqlTypeName.DATE), DOWN),
        is(18262));
    assertThrows(CalciteException.class,
        () -> v.dynamicCast(VARCHAR, DOWN));
  }

  /** Tests that {@link VariantValue#cast}, which implements
   * {@code CAST(variant AS type)}, still returns NULL on a mismatch, and
   * that it converts the values of a MAP to the value type (it used the key
   * type). */
  @Test void testVariantCast() {
    final RuntimeTypeInformation integer =
        new BasicSqlTypeRtti(RuntimeSqlTypeName.INTEGER);
    final RuntimeTypeInformation varchar =
        new BasicSqlTypeRtti(RuntimeSqlTypeName.VARCHAR);
    assertThat(variant("\"5\"").cast(integer), nullValue());
    assertThat(variant("5").cast(integer), is(5));

    final Map<Object, Object> expected = new LinkedHashMap<>();
    expected.put("a", 1);
    assertThat(
        variant("{\"a\":1}")
            .cast(
                new GenericSqlTypeRtti(RuntimeSqlTypeName.MAP, varchar,
                    integer)),
        is(expected));

    // An array holding a VARIANT null converts it to NULL.
    assertThat(
        variant("[1, null]")
            .cast(new GenericSqlTypeRtti(RuntimeSqlTypeName.ARRAY, integer)),
        is(Arrays.asList(1, null)));
  }

  private static VariantValue variant(String json) {
    final JsonFunctions.JsonValueContext context =
        JsonFunctions.jsonValueExpressionExact(json);
    assertThat(context.exc, nullValue());
    return JsonFunctions.toVariant(context.obj, DOWN);
  }

  private static @Nullable Object cast(String json, CastSpec target) {
    return variant(json).dynamicCast(target, DOWN);
  }

  private static void assertType(String json, String typeString) {
    assertThat(variant(json).getTypeString(), is(typeString));
  }

  private static @Nullable String typeString(@Nullable Object o) {
    return VariantValue.getTypeString(requireNonNull(o, "o"));
  }

  private static CastSpec basic(SqlTypeName typeName) {
    return new CastSpec(typeName, -1, -1);
  }

  private static CastSpec basic(SqlTypeName typeName, int precision,
      int scale) {
    return new CastSpec(typeName, precision, scale);
  }

  private static CastSpec array(CastSpec element) {
    return new CastSpec(SqlTypeName.ARRAY, element);
  }

  private static CastSpec map(CastSpec key, CastSpec value) {
    return new CastSpec(SqlTypeName.MAP, key, value);
  }

  private static CastSpec row(Object... namesAndTypes) {
    final String[] names = new String[namesAndTypes.length / 2];
    final CastSpec[] specs = new CastSpec[names.length];
    for (int i = 0; i < names.length; i++) {
      names[i] = (String) namesAndTypes[2 * i];
      specs[i] = (CastSpec) namesAndTypes[2 * i + 1];
    }
    return new CastSpec(names, specs);
  }
}
