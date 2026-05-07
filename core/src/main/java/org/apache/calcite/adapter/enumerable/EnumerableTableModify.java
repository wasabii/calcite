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
package org.apache.calcite.adapter.enumerable;

import org.apache.calcite.adapter.java.JavaTypeFactory;
import org.apache.calcite.linq4j.function.Function1;
import org.apache.calcite.linq4j.tree.BlockBuilder;
import org.apache.calcite.linq4j.tree.Expression;
import org.apache.calcite.linq4j.tree.Expressions;
import org.apache.calcite.linq4j.tree.ParameterExpression;
import org.apache.calcite.linq4j.tree.Types;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptTable;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.prepare.Prepare;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.TableModify;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.schema.ModifiableTable;
import org.apache.calcite.util.BuiltInMethod;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import static com.google.common.base.Preconditions.checkArgument;

import static java.util.Objects.requireNonNull;

/** Implementation of {@link org.apache.calcite.rel.core.TableModify} in
 * {@link org.apache.calcite.adapter.enumerable.EnumerableConvention enumerable calling convention}. */
public class EnumerableTableModify extends TableModify
    implements EnumerableRel {
  public EnumerableTableModify(RelOptCluster cluster, RelTraitSet traits,
      RelOptTable table, Prepare.CatalogReader catalogReader, RelNode child,
      Operation operation, @Nullable List<String> updateColumnList,
      @Nullable List<RexNode> sourceExpressionList, boolean flattened) {
    super(cluster, traits, table, catalogReader, child, operation,
        updateColumnList, sourceExpressionList, flattened);
    assert child.getConvention() instanceof EnumerableConvention;
    assert getConvention() instanceof EnumerableConvention;
    final ModifiableTable modifiableTable =
        table.unwrap(ModifiableTable.class);
    if (modifiableTable == null) {
      throw new AssertionError(); // TODO: user error in validator
    }
  }

  @Override public RelNode copy(RelTraitSet traitSet, List<RelNode> inputs) {
    return new EnumerableTableModify(
        getCluster(),
        traitSet,
        getTable(),
        getCatalogReader(),
        sole(inputs),
        getOperation(),
        getUpdateColumnList(),
        getSourceExpressionList(),
        isFlattened());
  }

  @Override public Result implement(EnumerableRelImplementor implementor, Prefer pref) {
    final BlockBuilder builder = new BlockBuilder();
    final Result result =
        implementor.visitChild(this, 0, (EnumerableRel) getInput(), pref);
    Expression childExp =
        builder.append(
            "child", result.block);
    final ParameterExpression collectionParameter =
        Expressions.parameter(Collection.class,
            builder.newName("collection"));
    final Expression expression = table.getExpression(ModifiableTable.class);
    requireNonNull(expression, "expression"); // TODO: user error in validator
    checkArgument(
        ModifiableTable.class.isAssignableFrom(
            Types.toClass(expression.getType())),
        "not assignable from type %s", expression.getType());
    builder.add(
        Expressions.declare(
            Modifier.FINAL,
            collectionParameter,
            Expressions.call(
                expression,
                BuiltInMethod.MODIFIABLE_TABLE_GET_MODIFIABLE_COLLECTION
                    .method)));
    final PhysType physType =
        PhysTypeImpl.of(
            implementor.getTypeFactory(),
            getRowType(),
            pref == Prefer.ARRAY
                ? JavaRowFormat.ARRAY : JavaRowFormat.SCALAR);

    if (getOperation() == Operation.UPDATE) {
      // For UPDATE, the child produces, for each row matched by the WHERE
      // clause, a row of tableFieldCount + M fields:
      //   [originalField_0, ..., originalField_N-1, newValue_0, ..., newValue_M-1]
      // The first N fields are the *entire* original table row (all columns,
      // not just those being updated); the trailing M = updateColumnList.size()
      // fields are the new values, one per column named in the SET clause.
      // Filtering by WHERE has already been applied upstream, so every source
      // row corresponds to an existing row in the modifiable collection and
      // can be located by full-row content equality.
      final List<String> updateCols = requireNonNull(getUpdateColumnList());
      final List<RelDataTypeField> tableFields = table.getRowType().getFieldList();
      final int tableFieldCount = tableFields.size();
      final int[] updateColumnIndices = new int[updateCols.size()];
      for (int i = 0; i < updateCols.size(); i++) {
        final String colName = updateCols.get(i);
        int found = -1;
        for (int j = 0; j < tableFields.size(); j++) {
          if (tableFields.get(j).getName().equals(colName)) {
            found = j;
            break;
          }
        }
        if (found < 0) {
          throw new AssertionError("column '" + colName + "' not found in table");
        }
        updateColumnIndices[i] = found;
      }

      // Build the three lambdas required by ExtendedEnumerable.update:
      //   sinkKeySelector:   row -> Arrays.asList(row)
      //   sourceKeySelector: row -> Arrays.asList(Arrays.copyOf(row, N))
      //   sourceTransform:   row -> applyUpdate(row, N, updateColumnIndices)
      final ParameterExpression sinkRow =
          Expressions.parameter(Object[].class, "sinkRow");
      final Expression sinkKeySelector =
          Expressions.lambda(Function1.class,
              Expressions.call(Arrays.class, "asList", sinkRow),
              sinkRow);

      final ParameterExpression srcKeyRow =
          Expressions.parameter(Object[].class, "row");
      final Expression sourceKeySelector =
          Expressions.lambda(Function1.class,
              Expressions.call(Arrays.class, "asList",
                  Expressions.call(Arrays.class, "copyOf",
                      srcKeyRow, Expressions.constant(tableFieldCount))),
              srcKeyRow);

      final ParameterExpression srcXformRow =
          Expressions.parameter(Object[].class, "row");
      final Expression sourceTransform =
          Expressions.lambda(Function1.class,
              Expressions.call(EnumerableTableModify.class, "applyUpdate",
                  srcXformRow,
                  Expressions.constant(tableFieldCount),
                  Expressions.constant(updateColumnIndices)),
              srcXformRow);

      final Expression updateCountExp =
          builder.append(
              "updateCount",
              Expressions.call(
                  childExp,
                  BuiltInMethod.UPDATE.method,
                  Expressions.convert_(collectionParameter, List.class),
                  sinkKeySelector,
                  sourceKeySelector,
                  sourceTransform));
      builder.add(
          Expressions.return_(
              null,
              Expressions.call(
                  BuiltInMethod.SINGLETON_ENUMERABLE.method,
                  Expressions.convert_(updateCountExp, long.class))));
      return implementor.result(physType, builder.toBlock());
    }

    final Expression countParameter =
        builder.append(
            "count",
            Expressions.call(collectionParameter, "size"),
            false);
    Expression convertedChildExp;
    if (!getInput().getRowType().equals(getRowType())) {
      final JavaTypeFactory typeFactory =
          (JavaTypeFactory) getCluster().getTypeFactory();
      final JavaRowFormat format = EnumerableTableScan.deduceFormat(table);
      PhysType tablePhysType =
          PhysTypeImpl.of(typeFactory, table.getRowType(), format);
      List<Expression> expressionList = new ArrayList<>();
      final PhysType childPhysType = result.physType;
      final ParameterExpression o_ =
          Expressions.parameter(childPhysType.getJavaRowType(), "o");
      final int fieldCount =
          childPhysType.getRowType().getFieldCount();
      for (int i = 0; i < fieldCount; i++) {
        expressionList.add(
            childPhysType.fieldReference(o_, i, tablePhysType.getJavaFieldType(i)));
      }
      convertedChildExp =
          builder.append(
              "convertedChild",
              Expressions.call(
                  childExp,
                  BuiltInMethod.SELECT.method,
                  Expressions.lambda(
                      tablePhysType.record(expressionList), o_)));
    } else {
      convertedChildExp = childExp;
    }
    final Method method;
    switch (getOperation()) {
    case INSERT:
      method = BuiltInMethod.INTO.method;
      break;
    case DELETE:
      method = BuiltInMethod.REMOVE_ALL.method;
      break;
    default:
      throw new AssertionError(getOperation());
    }
    builder.add(
        Expressions.statement(
            Expressions.call(
                convertedChildExp, method, collectionParameter)));
    final Expression updatedCountParameter =
        builder.append(
            "updatedCount",
            Expressions.call(collectionParameter, "size"),
            false);
    builder.add(
        Expressions.return_(
            null,
            Expressions.call(
                BuiltInMethod.SINGLETON_ENUMERABLE.method,
                Expressions.convert_(
                    Expressions.condition(
                        Expressions.greaterThanOrEqual(
                            updatedCountParameter, countParameter),
                        Expressions.subtract(
                            updatedCountParameter, countParameter),
                        Expressions.subtract(
                            countParameter, updatedCountParameter)),
                    long.class))));
    return implementor.result(physType, builder.toBlock());
  }

  /**
   * Builds the replacement row for an UPDATE source row.
   *
   * <p>The source row layout is:
   * {@code [originalField_0, ..., originalField_N-1, newValue_0, ..., newValue_M-1]}
   * where {@code N = tableFieldCount} is the full width of the table row
   * (i.e. <em>all</em> original columns, not just those being updated) and
   * {@code M = updateColumnIndices.length}. The result is a copy of the first
   * {@code N} fields with the trailing new values substituted at the indicated
   * column positions; columns not named in the SET clause therefore retain
   * their original values.
   *
   * @param row                 Source row (full original row followed by new
   *                            values for the SET columns)
   * @param tableFieldCount     Number of fields in the original table row
   * @param updateColumnIndices 0-based indices of the columns being updated
   * @return The replacement row
   */
  public static Object[] applyUpdate(
      Object[] row,
      int tableFieldCount,
      int[] updateColumnIndices) {
    final Object[] newRow = Arrays.copyOf(row, tableFieldCount);
    for (int i = 0; i < updateColumnIndices.length; i++) {
      newRow[updateColumnIndices[i]] = row[tableFieldCount + i];
    }
    return newRow;
  }

}
