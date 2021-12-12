/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.table.planner.factories;

import org.apache.flink.api.common.io.RichOutputFormat;
import org.apache.flink.api.common.state.ListState;
import org.apache.flink.api.common.state.ListStateDescriptor;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.runtime.state.FunctionInitializationContext;
import org.apache.flink.runtime.state.FunctionSnapshotContext;
import org.apache.flink.streaming.api.checkpoint.CheckpointedFunction;
import org.apache.flink.streaming.api.functions.sink.RichSinkFunction;
import org.apache.flink.table.connector.sink.DynamicTableSink.DataStructureConverter;
import org.apache.flink.table.data.RowData;
import org.apache.flink.table.functions.AsyncTableFunction;
import org.apache.flink.table.functions.FunctionContext;
import org.apache.flink.table.functions.TableFunction;
import org.apache.flink.test.util.SuccessException;
import org.apache.flink.types.Row;
import org.apache.flink.types.RowKind;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.apache.flink.table.planner.factories.TestValuesTableFactory.RESOURCE_COUNTER;
import static org.apache.flink.util.Preconditions.checkArgument;

/** Runtime function implementations for {@link TestValuesTableFactory}. */
final class TestValuesRuntimeFunctions {

    // [table_name, [task_id, List[value]]]
    private static final Map<String, Map<Integer, List<String>>> globalRawResult = new HashMap<>();
    // [table_name, [task_id, Map[key, value]]]
    private static final Map<String, Map<Integer, Map<String, String>>> globalUpsertResult =
            new HashMap<>();
    // [table_name, [task_id, List[value]]]
    private static final Map<String, Map<Integer, List<String>>> globalRetractResult =
            new HashMap<>();

    static List<String> getRawResults(String tableName) {
        List<String> result = new ArrayList<>();
        synchronized (TestValuesTableFactory.class) {
            if (globalRawResult.containsKey(tableName)) {
                globalRawResult.get(tableName).values().forEach(result::addAll);
            }
        }
        return result;
    }

    static List<String> getResults(String tableName) {
        List<String> result = new ArrayList<>();
        synchronized (TestValuesTableFactory.class) {
            if (globalUpsertResult.containsKey(tableName)) {
                globalUpsertResult
                        .get(tableName)
                        .values()
                        .forEach(map -> result.addAll(map.values()));
            } else if (globalRetractResult.containsKey(tableName)) {
                globalRetractResult.get(tableName).values().forEach(result::addAll);
            } else if (globalRawResult.containsKey(tableName)) {
                getRawResults(tableName).stream()
                        .map(s -> s.substring(3, s.length() - 1)) // removes the +I(...) wrapper
                        .forEach(result::add);
            }
        }
        return result;
    }

    static void clearResults() {
        synchronized (TestValuesTableFactory.class) {
            globalRawResult.clear();
            globalUpsertResult.clear();
            globalRetractResult.clear();
        }
    }

    // ------------------------------------------------------------------------------------------
    // Sink Function implementations
    // ------------------------------------------------------------------------------------------

    /**
     * The sink implementation is end-to-end exactly once, so that it can be used to check the state
     * restoring in streaming sql.
     */
    private abstract static class AbstractExactlyOnceSink extends RichSinkFunction<RowData>
            implements CheckpointedFunction {
        private static final long serialVersionUID = 1L;

        protected final String tableName;

        protected transient ListState<String> rawResultState;
        protected transient List<String> localRawResult;

        protected AbstractExactlyOnceSink(String tableName) {
            this.tableName = tableName;
        }

        @Override
        public void initializeState(FunctionInitializationContext context) throws Exception {
            this.rawResultState =
                    context.getOperatorStateStore()
                            .getListState(new ListStateDescriptor<>("sink-results", Types.STRING));
            this.localRawResult = new ArrayList<>();
            if (context.isRestored()) {
                for (String value : rawResultState.get()) {
                    localRawResult.add(value);
                }
            }
            int taskId = getRuntimeContext().getIndexOfThisSubtask();
            synchronized (TestValuesTableFactory.class) {
                globalRawResult
                        .computeIfAbsent(tableName, k -> new HashMap<>())
                        .put(taskId, localRawResult);
            }
        }

        @Override
        public void snapshotState(FunctionSnapshotContext context) throws Exception {
            rawResultState.clear();
            rawResultState.addAll(localRawResult);
        }
    }

    static class AppendingSinkFunction extends AbstractExactlyOnceSink {

        private static final long serialVersionUID = 1L;
        private final DataStructureConverter converter;

        protected AppendingSinkFunction(String tableName, DataStructureConverter converter) {
            super(tableName);
            this.converter = converter;
        }

        @SuppressWarnings("rawtypes")
        @Override
        public void invoke(RowData value, Context context) throws Exception {
            RowKind kind = value.getRowKind();
            if (value.getRowKind() == RowKind.INSERT) {
                Row row = (Row) converter.toExternal(value);
                assert row != null;
                localRawResult.add(kind.shortString() + "(" + row.toString() + ")");
            } else {
                throw new RuntimeException(
                        "AppendingSinkFunction received " + value.getRowKind() + " messages.");
            }
        }
    }

    static class KeyedUpsertingSinkFunction extends AbstractExactlyOnceSink {
        private static final long serialVersionUID = 1L;
        private final DataStructureConverter converter;
        private final int[] keyIndices;
        private final int expectedSize;

        // we store key and value as adjacent elements in the ListState
        private transient ListState<String> upsertResultState;
        // [key, value] map result
        private transient Map<String, String> localUpsertResult;

        // received count state
        private transient ListState<Integer> receivedNumState;
        private transient int receivedNum;

        protected KeyedUpsertingSinkFunction(
                String tableName,
                DataStructureConverter converter,
                int[] keyIndices,
                int expectedSize) {
            super(tableName);
            this.converter = converter;
            this.keyIndices = keyIndices;
            this.expectedSize = expectedSize;
        }

        @Override
        public void initializeState(FunctionInitializationContext context) throws Exception {
            super.initializeState(context);
            this.upsertResultState =
                    context.getOperatorStateStore()
                            .getListState(
                                    new ListStateDescriptor<>("sink-upsert-results", Types.STRING));
            this.localUpsertResult = new HashMap<>();
            this.receivedNumState =
                    context.getOperatorStateStore()
                            .getListState(
                                    new ListStateDescriptor<>("sink-received-num", Types.INT));

            if (context.isRestored()) {
                String key = null;
                String value;
                for (String entry : upsertResultState.get()) {
                    if (key == null) {
                        key = entry;
                    } else {
                        value = entry;
                        localUpsertResult.put(key, value);
                        // reset
                        key = null;
                    }
                }
                if (key != null) {
                    throw new RuntimeException("The upsertResultState is corrupt.");
                }
                for (int num : receivedNumState.get()) {
                    // should only be single element
                    this.receivedNum = num;
                }
            }

            int taskId = getRuntimeContext().getIndexOfThisSubtask();
            synchronized (TestValuesTableFactory.class) {
                globalUpsertResult
                        .computeIfAbsent(tableName, k -> new HashMap<>())
                        .put(taskId, localUpsertResult);
            }
        }

        @Override
        public void snapshotState(FunctionSnapshotContext context) throws Exception {
            super.snapshotState(context);
            upsertResultState.clear();
            for (Map.Entry<String, String> entry : localUpsertResult.entrySet()) {
                upsertResultState.add(entry.getKey());
                upsertResultState.add(entry.getValue());
            }
            receivedNumState.update(Collections.singletonList(receivedNum));
        }

        @SuppressWarnings("rawtypes")
        @Override
        public void invoke(RowData value, Context context) throws Exception {
            RowKind kind = value.getRowKind();
            Row row = (Row) converter.toExternal(value);
            assert row != null;
            Row key = Row.project(row, keyIndices);
            localRawResult.add(kind.shortString() + "(" + row.toString() + ")");
            if (kind == RowKind.INSERT || kind == RowKind.UPDATE_AFTER) {
                localUpsertResult.put(key.toString(), row.toString());
            } else {
                String oldValue = localUpsertResult.remove(key.toString());
                if (oldValue == null) {
                    throw new RuntimeException(
                            "Tried to delete a value that wasn't inserted first. "
                                    + "This is probably an incorrectly implemented test.");
                }
            }
            receivedNum++;
            if (expectedSize != -1 && receivedNum == expectedSize) {
                // some sources are infinite (e.g. kafka),
                // we throw a SuccessException to indicate job is finished.
                throw new SuccessException();
            }
        }
    }

    static class RetractingSinkFunction extends AbstractExactlyOnceSink {
        private static final long serialVersionUID = 1L;

        private final DataStructureConverter converter;

        protected transient ListState<String> retractResultState;
        protected transient List<String> localRetractResult;

        protected RetractingSinkFunction(String tableName, DataStructureConverter converter) {
            super(tableName);
            this.converter = converter;
        }

        @Override
        public void initializeState(FunctionInitializationContext context) throws Exception {
            super.initializeState(context);
            this.retractResultState =
                    context.getOperatorStateStore()
                            .getListState(
                                    new ListStateDescriptor<>(
                                            "sink-retract-results", Types.STRING));
            this.localRetractResult = new ArrayList<>();

            if (context.isRestored()) {
                for (String value : retractResultState.get()) {
                    localRetractResult.add(value);
                }
            }

            int taskId = getRuntimeContext().getIndexOfThisSubtask();
            synchronized (TestValuesTableFactory.class) {
                globalRetractResult
                        .computeIfAbsent(tableName, k -> new HashMap<>())
                        .put(taskId, localRetractResult);
            }
        }

        @Override
        public void snapshotState(FunctionSnapshotContext context) throws Exception {
            super.snapshotState(context);
            retractResultState.clear();
            retractResultState.addAll(localRetractResult);
        }

        @SuppressWarnings("rawtypes")
        @Override
        public void invoke(RowData value, Context context) throws Exception {
            RowKind kind = value.getRowKind();
            Row row = (Row) converter.toExternal(value);
            assert row != null;
            localRawResult.add(kind.shortString() + "(" + row.toString() + ")");
            if (kind == RowKind.INSERT || kind == RowKind.UPDATE_AFTER) {
                localRetractResult.add(row.toString());
            } else {
                boolean contains = localRetractResult.remove(row.toString());
                if (!contains) {
                    throw new RuntimeException(
                            "Tried to retract a value that wasn't inserted first. "
                                    + "This is probably an incorrectly implemented test.");
                }
            }
        }
    }

    static class AppendingOutputFormat extends RichOutputFormat<RowData> {

        private static final long serialVersionUID = 1L;
        private final String tableName;
        private final DataStructureConverter converter;

        protected transient List<String> localRawResult;

        protected AppendingOutputFormat(String tableName, DataStructureConverter converter) {
            this.tableName = tableName;
            this.converter = converter;
        }

        @Override
        public void configure(Configuration parameters) {
            // nothing to do
        }

        @Override
        public void open(int taskNumber, int numTasks) throws IOException {
            this.localRawResult = new ArrayList<>();
            synchronized (TestValuesTableFactory.class) {
                globalRawResult
                        .computeIfAbsent(tableName, k -> new HashMap<>())
                        .put(taskNumber, localRawResult);
            }
        }

        @Override
        public void writeRecord(RowData value) throws IOException {
            RowKind kind = value.getRowKind();
            if (value.getRowKind() == RowKind.INSERT) {
                Row row = (Row) converter.toExternal(value);
                assert row != null;
                localRawResult.add(kind.shortString() + "(" + row.toString() + ")");
            } else {
                throw new RuntimeException(
                        "AppendingOutputFormat received " + value.getRowKind() + " messages.");
            }
        }

        @Override
        public void close() throws IOException {
            // nothing to do
        }
    }

    // ------------------------------------------------------------------------------------------
    // Lookup Function implementations
    // ------------------------------------------------------------------------------------------

    /**
     * A lookup function which find matched rows with the given fields. NOTE: We have to declare it
     * as public because it will be used in code generation.
     */
    public static class TestValuesLookupFunction extends TableFunction<Row> {

        private static final long serialVersionUID = 1L;
        private final Map<Row, List<Row>> data;
        private transient boolean isOpenCalled = false;

        protected TestValuesLookupFunction(Map<Row, List<Row>> data) {
            this.data = data;
        }

        @Override
        public void open(FunctionContext context) throws Exception {
            RESOURCE_COUNTER.incrementAndGet();
            isOpenCalled = true;
        }

        public void eval(Object... inputs) {
            checkArgument(isOpenCalled, "open() is not called.");
            Row key = Row.of(inputs);
            if (Arrays.asList(inputs).contains(null)) {
                throw new IllegalArgumentException(
                        String.format(
                                "Lookup key %s contains null value, which should not happen.",
                                key));
            }
            List<Row> list = data.get(key);
            if (list != null) {
                list.forEach(this::collect);
            }
        }

        @Override
        public void close() throws Exception {
            RESOURCE_COUNTER.decrementAndGet();
        }
    }

    /**
     * An async lookup function which find matched rows with the given fields. NOTE: We have to
     * declare it as public because it will be used in code generation.
     */
    public static class AsyncTestValueLookupFunction extends AsyncTableFunction<Row> {

        private static final long serialVersionUID = 1L;
        private final Map<Row, List<Row>> mapping;
        private transient boolean isOpenCalled = false;
        private transient ExecutorService executor;

        protected AsyncTestValueLookupFunction(Map<Row, List<Row>> mapping) {
            this.mapping = mapping;
        }

        @Override
        public void open(FunctionContext context) throws Exception {
            RESOURCE_COUNTER.incrementAndGet();
            isOpenCalled = true;
            executor = Executors.newSingleThreadExecutor();
        }

        public void eval(CompletableFuture<Collection<Row>> resultFuture, Object... inputs) {
            checkArgument(isOpenCalled, "open() is not called.");
            final Row key = Row.of(inputs);
            if (Arrays.asList(inputs).contains(null)) {
                throw new IllegalArgumentException(
                        String.format(
                                "Lookup key %s contains null value, which should not happen.",
                                key));
            }
            CompletableFuture.supplyAsync(
                            () -> {
                                List<Row> list = mapping.get(key);
                                if (list == null) {
                                    return Collections.<Row>emptyList();
                                } else {
                                    return list;
                                }
                            },
                            executor)
                    .thenAccept(resultFuture::complete);
        }

        @Override
        public void close() throws Exception {
            RESOURCE_COUNTER.decrementAndGet();
            if (executor != null && !executor.isShutdown()) {
                executor.shutdown();
            }
        }
    }
}