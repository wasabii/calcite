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
import org.apache.calcite.linq4j.Enumerable;
import org.apache.calcite.linq4j.Enumerator;
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
import java.util.ListIterator;

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
      // For UPDATE, the child produces rows with tableFieldCount + M fields:
      // [originalField_0, ..., originalField_N-1, newValue_0, ..., newValue_M-1]
      // where M = updateColumnList.size().
      final List<String> updateCols = requireNonNull(getUpdateColumnList());
      final List<RelDataTypeField> tableFields = table.getRowType().getFieldList();
      final int tableFieldCount = tableFields.size();
      final int[] updateColumnIndices = new int[updateCols.size()];
      for (int i = 0; i < updateCols.size(); i++) {
        final String colName = updateCols.get(i);
        for (int j = 0; j < tableFields.size(); j++) {
          if (tableFields.get(j).getName().equals(colName)) {
            updateColumnIndices[i] = j;
            break;
          }
        }
      }
      final Expression updateCountExp =
          builder.append(
              "updateCount",
              Expressions.call(
                  EnumerableTableModify.class,
                  "update",
                  childExp,
                  collectionParameter,
                  Expressions.constant(tableFieldCount),
                  Expressions.constant(updateColumnIndices)));
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
   * Updates rows in a collection based on the source enumerable.
   *
   * <p>Each row in {@code source} has the structure:
   * {@code [originalField_0, ..., originalField_N-1, newValue_0, ..., newValue_M-1]}
   * where {@code N = tableFieldCount} and {@code M = updateColumnIndices.length}.
   * The original row (first N fields) is located in the collection by value
   * equality and replaced with the updated row.
   *
   * @param source            Source enumerable with original + new values
   * @param collection        Collection to update (must be a {@link List} for
   *                          in-place modification)
   * @param tableFieldCount   Number of fields in the original table row
   * @param updateColumnIndices 0-based indices of the columns being updated
   * @return Count of updated rows
   */
  @SuppressWarnings({"rawtypes", "unchecked"})
  public static long update(
      Enumerable<Object[]> source,
      Collection collection,
      int tableFieldCount,
      int[] updateColumnIndices) {
    final List<Object[]> oldRows = new ArrayList<>();
    final List<Object[]> newRows = new ArrayList<>();
    try (Enumerator<Object[]> e = source.enumerator()) {
      while (e.moveNext()) {
        final Object[] row = e.current();
        final Object[] oldRow = Arrays.copyOf(row, tableFieldCount);
        final Object[] newRow = oldRow.clone();
        for (int i = 0; i < updateColumnIndices.length; i++) {
          newRow[updateColumnIndices[i]] = row[tableFieldCount + i];
        }
        oldRows.add(oldRow);
        newRows.add(newRow);
      }
    }
    final List<Object[]> list = (List<Object[]>) collection;
    int updateCount = 0;
    for (int u = 0; u < oldRows.size(); u++) {
      final Object[] oldRow = oldRows.get(u);
      final ListIterator<Object[]> it = list.listIterator();
      while (it.hasNext()) {
        if (Arrays.equals(it.next(), oldRow)) {
          it.set(newRows.get(u));
          updateCount++;
          break;
        }
      }
    }
    return updateCount;
  }

}
