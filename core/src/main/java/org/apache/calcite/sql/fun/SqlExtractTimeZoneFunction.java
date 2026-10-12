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

import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlCallBinding;
import org.apache.calcite.sql.SqlFunction;
import org.apache.calcite.sql.SqlFunctionCategory;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlWriter;
import org.apache.calcite.sql.type.OperandTypes;
import org.apache.calcite.sql.type.ReturnTypes;

/**
 * The SQL {@code EXTRACT(TIMEZONE_HOUR FROM ...)} and
 * {@code EXTRACT(TIMEZONE_MINUTE FROM ...)} operators, which return the
 * components of the zone displacement (offset) of a TIMESTAMP WITH TIME
 * ZONE or TIME WITH TIME ZONE value.
 *
 * <p>They are separate from {@link SqlExtractFunction} because Avatica's
 * {@link org.apache.calcite.avatica.util.TimeUnitRange} has no
 * {@code TIMEZONE_HOUR} or {@code TIMEZONE_MINUTE} value.
 */
public class SqlExtractTimeZoneFunction extends SqlFunction {
  /** The field, "TIMEZONE_HOUR" or "TIMEZONE_MINUTE". */
  public final String field;

  SqlExtractTimeZoneFunction(String field) {
    // The name contains a space, so that it cannot be called as a regular
    // function
    super("EXTRACT " + field, SqlKind.OTHER_FUNCTION,
        ReturnTypes.INTEGER_NULLABLE, null, OperandTypes.DATETIME,
        SqlFunctionCategory.TIMEDATE);
    this.field = field;
  }

  @Override public boolean checkOperandTypes(SqlCallBinding callBinding,
      boolean throwOnFailure) {
    if (!super.checkOperandTypes(callBinding, throwOnFailure)) {
      return false;
    }
    return SqlAtTimeZoneOperator.checkZoned(callBinding, 0, true,
        throwOnFailure);
  }

  @Override public String getSignatureTemplate(int operandsCount) {
    return "EXTRACT(" + field + " FROM {1})";
  }

  @Override public void unparse(SqlWriter writer, SqlCall call, int leftPrec,
      int rightPrec) {
    final SqlWriter.Frame frame = writer.startFunCall("EXTRACT");
    writer.keyword(field);
    writer.sep("FROM");
    call.operand(0).unparse(writer, 0, 0);
    writer.endFunCall(frame);
  }
}
