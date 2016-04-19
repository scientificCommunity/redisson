/**
 * Copyright 2014 Nikita Koksharov, Nickolay Borbit
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.redisson;

import java.net.InetSocketAddress;
import java.util.AbstractMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.NoSuchElementException;

import org.redisson.client.protocol.decoder.MapScanResult;
import org.redisson.client.protocol.decoder.ScanObjectEntry;

import io.netty.buffer.ByteBuf;

abstract class RedissonBaseMapIterator<K, V, M> implements Iterator<M> {

    private Map<ByteBuf, ByteBuf> firstValues;
    private Map<ByteBuf, ByteBuf> lastValues;
    private Iterator<Map.Entry<ScanObjectEntry, ScanObjectEntry>> lastIter;
    protected long nextIterPos;
    protected long startPos = -1;
    protected InetSocketAddress client;

    private boolean finished;
    private boolean currentElementRemoved;
    private boolean removeExecuted;
    protected Map.Entry<ScanObjectEntry, ScanObjectEntry> entry;

    @Override
    public boolean hasNext() {
        if (finished) {
            return false;
        }
        
        if (lastIter == null || !lastIter.hasNext()) {
            if (nextIterPos == -1) {
                return false;
            }
            long prevIterPos;
            do {
                prevIterPos = nextIterPos;
                MapScanResult<ScanObjectEntry, ScanObjectEntry> res = iterator();
                if (lastValues != null) {
                    free(lastValues);
                }
                lastValues = convert(res.getMap());
                client = res.getRedisClient();
                if (startPos == -1) {
                    startPos = res.getPos();
                }
                if (nextIterPos == 0 && firstValues == null) {
                    firstValues = lastValues;
                    lastValues = null;
                } else {
                    if (firstValues.isEmpty()) {
                        firstValues = lastValues;
                        lastValues = null;
                    } else if (lastValues.keySet().removeAll(firstValues.keySet())) {
                        finished = true;
                        free(firstValues);
                        free(lastValues);
                        firstValues = null;
                        lastValues = null;
                        return false;
                    }
                }
                lastIter = res.getMap().entrySet().iterator();
                nextIterPos = res.getPos();
            } while (!lastIter.hasNext() && nextIterPos != prevIterPos);
            if (prevIterPos == nextIterPos && !removeExecuted) {
                nextIterPos = -1;
            }
        }
        return lastIter.hasNext();
        
    }

    protected abstract MapScanResult<ScanObjectEntry, ScanObjectEntry> iterator();

    private void free(Map<ByteBuf, ByteBuf> map) {
        for (Entry<ByteBuf, ByteBuf> entry : map.entrySet()) {
            entry.getKey().release();
            entry.getValue().release();
        }
    }

    private Map<ByteBuf, ByteBuf> convert(Map<ScanObjectEntry, ScanObjectEntry> map) {
        Map<ByteBuf, ByteBuf> result = new HashMap<ByteBuf, ByteBuf>(map.size());
        for (Entry<ScanObjectEntry, ScanObjectEntry> entry : map.entrySet()) {
            result.put(entry.getKey().getBuf(), entry.getValue().getBuf());
        }
        return result;
    }

    @Override
    public M next() {
        if (!hasNext()) {
            throw new NoSuchElementException("No such element at index");
        }

        entry = lastIter.next();
        currentElementRemoved = false;
        return getValue(entry);
    }

    @SuppressWarnings("unchecked")
    M getValue(final Entry<ScanObjectEntry, ScanObjectEntry> entry) {
        return (M)new AbstractMap.SimpleEntry<K, V>((K)entry.getKey().getObj(), (V)entry.getValue().getObj()) {

            @Override
            public V setValue(V value) {
                return put(entry, value);
            }

        };
    }

    @Override
    public void remove() {
        if (currentElementRemoved) {
            throw new IllegalStateException("Element been already deleted");
        }
        if (lastIter == null) {
            throw new IllegalStateException();
        }

        firstValues.remove(entry.getKey().getBuf());
        lastIter.remove();
        removeKey();
        currentElementRemoved = true;
        removeExecuted = true;
    }

    protected abstract void removeKey();

    protected abstract V put(Entry<ScanObjectEntry, ScanObjectEntry> entry, V value);

}
