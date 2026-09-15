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
package org.apache.calcite.benchmarks;

import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptTable;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.plan.hep.HepPlanner;
import org.apache.calcite.plan.hep.HepProgram;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.logical.LogicalTableScan;
import org.apache.calcite.rel.logical.LogicalUnion;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.schema.impl.AbstractTable;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.tools.Frameworks;
import org.apache.calcite.tools.RelBuilder;

import com.google.common.collect.ImmutableList;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Benchmarks {@code HepPlanner}'s digest-based deduplication for
 * {@code TableScan} leaves. The rule program is empty, so no rules run;
 * every leaf insert runs a real {@code deepHashCode} and, on a digest-map
 * hit, a real {@code deepEquals}.
 *
 * <p>All {@code scanCount} leaves are direct inputs of a single
 * {@code LogicalUnion}, so the plan has exactly one non-scan node no matter
 * how many leaves there are; that one node's cost is a fixed addend in both
 * the before and after measurement rather than something that scales with
 * {@code scanCount}.
 */
@Fork(value = 1, jvmArgsPrepend = {"-Xss200m"})
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
@Threads(1)
public class TableScanDigestBenchmark {

  private static final HepProgram EMPTY_PROGRAM = HepProgram.builder().build();

  /** Number of {@code TableScan} leaves in the plan. */
  @Param({"2", "5", "10", "100", "1000", "10000"})
  int scanCount;

  /**
   * 0 = every leaf scans a distinct table, so every digest lookup misses.
   * 100 = every leaf scans the same table, so lookups hit and
   * {@code deepEquals} runs.
   */
  @Param({"0", "50", "100"})
  int duplicatePercent;

  private RelOptCluster cluster;
  private List<RelOptTable> templateTables;
  private List<RelTraitSet> templateTraitSets;
  private RelNode root;

  @Setup(Level.Trial)
  public void setupCluster() {
    int templateCount = templateCount();
    SchemaPlus rootSchema = Frameworks.createRootSchema(true);
    for (int i = 0; i < templateCount; i++) {
      rootSchema.add("EMP" + i, new AbstractTable() {
        @Override public RelDataType getRowType(RelDataTypeFactory typeFactory) {
          return typeFactory.builder()
              .add("EMPNO", SqlTypeName.INTEGER)
              .build();
        }
      });
    }

    RelBuilder builder =
        RelBuilder.create(Frameworks.newConfigBuilder()
            .defaultSchema(rootSchema)
            .build());
    cluster = builder.getCluster();

    // Resolved once per trial: the by-name schema lookup and the trait-set
    // canonicalization that LogicalTableScan.create performs are otherwise
    // repeated, unaltered, on every leaf of every invocation.
    templateTables = new ArrayList<>(templateCount);
    templateTraitSets = new ArrayList<>(templateCount);
    for (int i = 0; i < templateCount; i++) {
      RelOptTable table =
          builder.getRelOptSchema().getTableForMember(ImmutableList.of("EMP" + i));
      templateTables.add(table);
      templateTraitSets.add(
          LogicalTableScan.create(cluster, table, ImmutableList.of()).getTraitSet());
    }
  }

  private int templateCount() {
    return Math.max(1, scanCount * (100 - duplicatePercent) / 100);
  }

  private RelNode makeScan(int templateIndex) {
    return new LogicalTableScan(cluster, templateTraitSets.get(templateIndex),
        ImmutableList.of(), templateTables.get(templateIndex));
  }

  /** Builds a fresh plan; {@code duplicatePercent} controls the tables scanned. */
  private RelNode newPlan() {
    int templateCount = templateCount();
    List<RelNode> scans = new ArrayList<>(scanCount);
    for (int i = 0; i < scanCount; i++) {
      scans.add(makeScan(i % templateCount));
    }
    return LogicalUnion.create(scans, true);
  }

  /** Builds a fresh plan so {@link #dedupOnInsert} always sees no cached digest hash. */
  @Setup(Level.Invocation)
  public void prepareInvocation() {
    root = newPlan();
  }

  @Benchmark
  public RelNode dedupOnInsert() {
    HepPlanner planner = new HepPlanner(EMPTY_PROGRAM);
    planner.setLargePlanMode(true);
    planner.setRoot(root);
    return planner.getRoot();
  }
}
