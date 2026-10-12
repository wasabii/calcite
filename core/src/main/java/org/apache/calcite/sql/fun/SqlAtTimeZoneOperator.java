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
package org.apache.calcite.sql.fun;

import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.sql.SqlBinaryOperator;
import org.apache.calcite.sql.SqlCallBinding;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlOperatorBinding;
import org.apache.calcite.sql.type.OperandTypes;
import org.apache.calcite.sql.type.ReturnTypes;
import org.apache.calcite.sql.type.SqlTypeFamily;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.sql.type.SqlTypeTransforms;

/**
 * The SQL {@code AT TIME ZONE} operator.
 *
 * <p>{@code timestamp AT TIME ZONE zone} returns a TIMESTAMP WITH TIME ZONE
 * value that denotes the same instant as {@code timestamp}, in a different
 * zone. If {@code timestamp} has no time zone (TIMESTAMP), it is assumed to
 * be in the session time zone. The zone is an interval, such as
 * {@code INTERVAL '05:30' HOUR TO MINUTE}, as in the SQL standard; or, as a
 * Calcite extension, a string, such as {@code '+05:30'} or
 * {@code 'America/New_York'}.
 */
public class SqlAtTimeZoneOperator extends SqlBinaryOperator {
  SqlAtTimeZoneOperator() {
    // Binds more tightly than arithmetic, so that
    // "t AT TIME ZONE 'UTC' + INTERVAL '1' HOUR" adds to the converted value
    super("AT TIME ZONE", SqlKind.OTHER, 70, true,
        ReturnTypes.ARG0.andThen(SqlAtTimeZoneOperator::toZoned)
            .andThen(SqlTypeTransforms.TO_NULLABLE),
        null,
        OperandTypes.family(SqlTypeFamily.TIMESTAMP, SqlTypeFamily.CHARACTER)
            .or(
                OperandTypes.family(SqlTypeFamily.TIMESTAMP,
                    SqlTypeFamily.INTERVAL_DAY_TIME)));
  }

  /** Converts a TIMESTAMP or TIMESTAMP WITH LOCAL TIME ZONE type to
   * TIMESTAMP WITH TIME ZONE of the same precision. */
  static RelDataType toZoned(SqlOperatorBinding opBinding, RelDataType type) {
    switch (type.getSqlTypeName()) {
    case TIMESTAMP:
    case TIMESTAMP_WITH_LOCAL_TIME_ZONE:
      final RelDataTypeFactory typeFactory = opBinding.getTypeFactory();
      return typeFactory.createTypeWithNullability(
          typeFactory.createSqlType(SqlTypeName.TIMESTAMP_TZ,
              type.getPrecision()),
          type.isNullable());
    default:
      return type;
    }
  }

  /** Checks that an operand is TIMESTAMP WITH TIME ZONE (or, if
   * {@code allowTime}, TIME WITH TIME ZONE). */
  static boolean checkZoned(SqlCallBinding callBinding, int i,
      boolean allowTime, boolean throwOnFailure) {
    final RelDataType type = callBinding.getOperandType(i);
    switch (type.getSqlTypeName()) {
    case TIMESTAMP_TZ:
    case NULL:
      return true;
    case TIME_TZ:
      if (allowTime) {
        return true;
      }
      break;
    default:
      break;
    }
    if (throwOnFailure) {
      throw callBinding.newValidationSignatureError();
    }
    return false;
  }
}
