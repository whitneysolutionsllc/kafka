/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.kafka.streams.kstream.internals;

import org.apache.kafka.streams.kstream.Aggregator;
import org.apache.kafka.streams.kstream.Initializer;
import org.apache.kafka.streams.processor.AbstractProcessor;
import org.apache.kafka.streams.processor.Processor;
import org.apache.kafka.streams.processor.ProcessorContext;
import org.apache.kafka.streams.state.KeyValueStore;

public class KStreamAggregate<K, V, T> implements KStreamAggProcessorSupplier<K, K, V, T> {

    private final String storeName;
    private final Initializer<T> initializer;
    private final Aggregator<? super K, ? super V, T> aggregator;


    private boolean sendOldValues = false;

    public KStreamAggregate(String storeName, Initializer<T> initializer, Aggregator<? super K, ? super V, T> aggregator) {
        this.storeName = storeName;
        this.initializer = initializer;
        this.aggregator = aggregator;
    }

    @Override
    public Processor<K, V> get() {
        return new KStreamAggregateProcessor();
    }

    @Override
    public void enableSendingOldValues() {
        sendOldValues = true;
    }

    private class KStreamAggregateProcessor extends AbstractProcessor<K, V> {

        private KeyValueStore<K, T> store;
        private TupleForwarder<K, T> tupleForwarder;

        @SuppressWarnings("unchecked")
        @Override
        public void init(ProcessorContext context) {
            super.init(context);
            store = (KeyValueStore<K, T>) context.getStateStore(storeName);
            tupleForwarder = new TupleForwarder<>(store, context, new ForwardingCacheFlushListener<K, V>(context, sendOldValues), sendOldValues);
        }


        @Override
        public void process(K key, V value) {
            if (key == null)
                return;

            T oldAgg = store.get(key);

            if (oldAgg == null)
                oldAgg = initializer.apply();

            T newAgg = oldAgg;

            // try to add the new value
            if (value != null) {
                newAgg = aggregator.apply(key, value, newAgg);
            }

            // update the store with the new value
            store.put(key, newAgg);
            tupleForwarder.maybeForward(key, newAgg, oldAgg);
        }
    }

    @Override
    public KTableValueGetterSupplier<K, T> view() {

        return new KTableValueGetterSupplier<K, T>() {

            public KTableValueGetter<K, T> get() {
                return new KStreamAggregateValueGetter();
            }

            @Override
            public String[] storeNames() {
                return new String[]{storeName};
            }
        };
    }

    private class KStreamAggregateValueGetter implements KTableValueGetter<K, T> {

        private KeyValueStore<K, T> store;

        @SuppressWarnings("unchecked")
        @Override
        public void init(ProcessorContext context) {
            store = (KeyValueStore<K, T>) context.getStateStore(storeName);
        }

        @Override
        public T get(K key) {
            return store.get(key);
        }
    }
}
