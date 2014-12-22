/*
 * Copyright 2014 Goldman Sachs.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.gs.collections.impl.set.sorted.immutable;

import java.io.Serializable;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.Set;
import java.util.SortedSet;
import java.util.concurrent.ExecutorService;

import com.gs.collections.api.LazyIterable;
import com.gs.collections.api.block.function.Function;
import com.gs.collections.api.block.predicate.Predicate;
import com.gs.collections.api.block.procedure.Procedure;
import com.gs.collections.api.block.procedure.Procedure2;
import com.gs.collections.api.block.procedure.primitive.ObjectIntProcedure;
import com.gs.collections.api.list.ParallelListIterable;
import com.gs.collections.api.map.MapIterable;
import com.gs.collections.api.multimap.sortedset.ImmutableSortedSetMultimap;
import com.gs.collections.api.set.sorted.ImmutableSortedSet;
import com.gs.collections.api.set.sorted.ParallelSortedSetIterable;
import com.gs.collections.api.set.sorted.SortedSetIterable;
import com.gs.collections.impl.lazy.AbstractLazyIterable;
import com.gs.collections.impl.lazy.parallel.AbstractBatch;
import com.gs.collections.impl.lazy.parallel.AbstractParallelIterable;
import com.gs.collections.impl.lazy.parallel.list.ListBatch;
import com.gs.collections.impl.lazy.parallel.set.sorted.AbstractParallelSortedSetIterable;
import com.gs.collections.impl.lazy.parallel.set.sorted.CollectSortedSetBatch;
import com.gs.collections.impl.lazy.parallel.set.sorted.RootSortedSetBatch;
import com.gs.collections.impl.lazy.parallel.set.sorted.SelectSortedSetBatch;
import com.gs.collections.impl.lazy.parallel.set.sorted.SortedSetBatch;
import com.gs.collections.impl.list.mutable.FastList;
import com.gs.collections.impl.map.mutable.ConcurrentHashMap;
import com.gs.collections.impl.set.sorted.mutable.TreeSortedSet;
import com.gs.collections.impl.utility.ArrayIterate;
import com.gs.collections.impl.utility.ListIterate;
import net.jcip.annotations.Immutable;

@Immutable
final class ImmutableTreeSet<T>
        extends AbstractImmutableSortedSet<T>
        implements Serializable
{
    private static final long serialVersionUID = 2L;
    private final T[] delegate;
    private final Comparator<? super T> comparator;

    private ImmutableTreeSet(SortedSet<T> sortedSet)
    {
        this.delegate = (T[]) sortedSet.toArray();
        this.comparator = sortedSet.comparator();
    }

    public static <T> ImmutableSortedSet<T> newSetWith(T... elements)
    {
        return new ImmutableTreeSet<T>(TreeSortedSet.newSetWith(elements));
    }

    public static <T> ImmutableSortedSet<T> newSetWith(Comparator<? super T> comparator, T... elements)
    {
        return new ImmutableTreeSet<T>(TreeSortedSet.newSetWith(comparator, elements));
    }

    public static <T> ImmutableSortedSet<T> newSet(SortedSet<T> set)
    {
        return new ImmutableTreeSet<T>(TreeSortedSet.newSet(set));
    }

    public int size()
    {
        return this.delegate.length;
    }

    private Object writeReplace()
    {
        return new ImmutableSortedSetSerializationProxy<T>(this);
    }

    @Override
    public boolean equals(Object obj)
    {
        if (obj == this)
        {
            return true;
        }

        if (!(obj instanceof Set))
        {
            return false;
        }
        Set<?> otherSet = (Set<?>) obj;
        if (otherSet.size() != this.size())
        {
            return false;
        }
        try
        {
            return this.containsAll(otherSet);
        }
        catch (ClassCastException ignored)
        {
            return false;
        }
    }

    @Override
    public int hashCode()
    {
        int result = 0;
        for (T each : this.delegate)
        {
            result += each.hashCode();
        }

        return result;
    }

    @Override
    public boolean contains(Object object)
    {
        return Arrays.binarySearch(this.delegate, (T) object, this.comparator) >= 0;
    }

    public Iterator<T> iterator()
    {
        return FastList.newListWith(this.delegate).asUnmodifiable().iterator();
    }

    public void forEach(Procedure<? super T> procedure)
    {
        this.each(procedure);
    }

    public void each(Procedure<? super T> procedure)
    {
        for (T t : this.delegate)
        {
            procedure.value(t);
        }
    }

    public T first()
    {
        return this.delegate[0];
    }

    public T last()
    {
        return this.delegate[this.delegate.length - 1];
    }

    public Comparator<? super T> comparator()
    {
        return this.comparator;
    }

    public int compareTo(SortedSetIterable<T> otherSet)
    {
        Iterator<T> iterator = otherSet.iterator();

        for (T eachInThis : this.delegate)
        {
            if (!iterator.hasNext())
            {
                return 1;
            }

            T eachInOther = iterator.next();

            int compare = this.compare(eachInThis, eachInOther);
            if (compare != 0)
            {
                return compare;
            }
        }

        return iterator.hasNext() ? -1 : 0;
    }

    private int compare(T o1, T o2)
    {
        return this.comparator == null
                ? ((Comparable<T>) o1).compareTo(o2)
                : this.comparator.compare(o1, o2);
    }

    @Override
    public ParallelSortedSetIterable<T> asParallel(ExecutorService executorService, int batchSize)
    {
        return new SortedSetIterableParallelIterable(executorService, batchSize);
    }

    private final class SortedSetIterableParallelIterable extends AbstractParallelSortedSetIterable<T, RootSortedSetBatch<T>>
    {
        private final ExecutorService executorService;
        private final int batchSize;

        private SortedSetIterableParallelIterable(ExecutorService executorService, int batchSize)
        {
            if (executorService == null)
            {
                throw new NullPointerException();
            }
            if (batchSize < 1)
            {
                throw new IllegalArgumentException();
            }
            this.executorService = executorService;
            this.batchSize = batchSize;
        }

        public Comparator<? super T> comparator()
        {
            return ImmutableTreeSet.this.comparator;
        }

        @Override
        public ExecutorService getExecutorService()
        {
            return this.executorService;
        }

        @Override
        public LazyIterable<RootSortedSetBatch<T>> split()
        {
            return new SortedSetIterableParallelBatchLazyIterable();
        }

        public void forEach(Procedure<? super T> procedure)
        {
            AbstractParallelIterable.forEach(this, procedure);
        }

        public boolean anySatisfy(Predicate<? super T> predicate)
        {
            return AbstractParallelIterable.anySatisfy(this, predicate);
        }

        public boolean allSatisfy(Predicate<? super T> predicate)
        {
            return AbstractParallelIterable.allSatisfy(this, predicate);
        }

        public T detect(Predicate<? super T> predicate)
        {
            return AbstractParallelIterable.detect(this, predicate);
        }

        @Override
        public <V> ParallelListIterable<V> flatCollect(Function<? super T, ? extends Iterable<V>> function)
        {
            // TODO: Implement in parallel
            return ImmutableTreeSet.this.flatCollect(function).asParallel(this.executorService, this.batchSize);
        }

        @Override
        public Object[] toArray()
        {
            // TODO: Implement in parallel
            return ImmutableTreeSet.this.toArray();
        }

        @Override
        public <E> E[] toArray(E[] array)
        {
            // TODO: Implement in parallel
            return ImmutableTreeSet.this.toArray(array);
        }

        @Override
        public <V> ImmutableSortedSetMultimap<V, T> groupBy(Function<? super T, ? extends V> function)
        {
            // TODO: Implement in parallel
            return ImmutableTreeSet.this.groupBy(function);
        }

        @Override
        public <V> ImmutableSortedSetMultimap<V, T> groupByEach(Function<? super T, ? extends Iterable<V>> function)
        {
            // TODO: Implement in parallel
            return ImmutableTreeSet.this.groupByEach(function);
        }

        @Override
        public <V> MapIterable<V, T> groupByUniqueKey(Function<? super T, ? extends V> function)
        {
            // TODO: Implement in parallel
            return ImmutableTreeSet.this.groupByUniqueKey(function);
        }

        @Override
        public int getBatchSize()
        {
            return this.batchSize;
        }

        private class SortedSetIterableParallelBatchIterator implements Iterator<RootSortedSetBatch<T>>
        {
            protected int chunkIndex;

            public boolean hasNext()
            {
                return this.chunkIndex * SortedSetIterableParallelIterable.this.getBatchSize() < ImmutableTreeSet.this.size();
            }

            public RootSortedSetBatch<T> next()
            {
                int chunkStartIndex = this.chunkIndex * SortedSetIterableParallelIterable.this.getBatchSize();
                int chunkEndIndex = (this.chunkIndex + 1) * SortedSetIterableParallelIterable.this.getBatchSize();
                int truncatedChunkEndIndex = Math.min(chunkEndIndex, ImmutableTreeSet.this.size());
                this.chunkIndex++;
                return new ImmutableTreeSetBatch(chunkStartIndex, truncatedChunkEndIndex);
            }

            public void remove()
            {
                throw new UnsupportedOperationException("Cannot call remove() on " + ImmutableTreeSet.this.getClass().getSimpleName());
            }
        }

        private class SortedSetIterableParallelBatchLazyIterable
                extends AbstractLazyIterable<RootSortedSetBatch<T>>
        {
            public void forEach(Procedure<? super RootSortedSetBatch<T>> procedure)
            {
                this.each(procedure);
            }

            public void each(Procedure<? super RootSortedSetBatch<T>> procedure)
            {
                for (RootSortedSetBatch<T> chunk : this)
                {
                    procedure.value(chunk);
                }
            }

            public <P> void forEachWith(Procedure2<? super RootSortedSetBatch<T>, ? super P> procedure, P parameter)
            {
                for (RootSortedSetBatch<T> chunk : this)
                {
                    procedure.value(chunk, parameter);
                }
            }

            public void forEachWithIndex(ObjectIntProcedure<? super RootSortedSetBatch<T>> objectIntProcedure)
            {
                throw new UnsupportedOperationException(this.getClass().getSimpleName() + ".forEachWithIndex() not implemented yet");
            }

            public Iterator<RootSortedSetBatch<T>> iterator()
            {
                return new SortedSetIterableParallelBatchIterator();
            }
        }
    }

    public class ImmutableTreeSetBatch extends AbstractBatch<T> implements RootSortedSetBatch<T>
    {
        private final int chunkStartIndex;
        private final int chunkEndIndex;

        public ImmutableTreeSetBatch(int chunkStartIndex, int chunkEndIndex)
        {
            this.chunkStartIndex = chunkStartIndex;
            this.chunkEndIndex = chunkEndIndex;
        }

        public void forEach(Procedure<? super T> procedure)
        {
            for (int i = this.chunkStartIndex; i < this.chunkEndIndex; i++)
            {
                procedure.value(ImmutableTreeSet.this.delegate[i]);
            }
        }

        @Override
        public int count(Predicate<? super T> predicate)
        {
            int count = 0;
            for (int i = this.chunkStartIndex; i < this.chunkEndIndex; i++)
            {
                if (predicate.accept(ImmutableTreeSet.this.delegate[i]))
                {
                    count++;
                }
            }
            return count;
        }

        public boolean anySatisfy(Predicate<? super T> predicate)
        {
            for (int i = this.chunkStartIndex; i < this.chunkEndIndex; i++)
            {
                if (predicate.accept(ImmutableTreeSet.this.delegate[i]))
                {
                    return true;
                }
            }
            return false;
        }

        public boolean allSatisfy(Predicate<? super T> predicate)
        {
            for (int i = this.chunkStartIndex; i < this.chunkEndIndex; i++)
            {
                if (!predicate.accept(ImmutableTreeSet.this.delegate[i]))
                {
                    return false;
                }
            }
            return true;
        }

        public T detect(Predicate<? super T> predicate)
        {
            for (int i = this.chunkStartIndex; i < this.chunkEndIndex; i++)
            {
                if (predicate.accept(ImmutableTreeSet.this.delegate[i]))
                {
                    return ImmutableTreeSet.this.delegate[i];
                }
            }
            return null;
        }

        public SortedSetBatch<T> select(Predicate<? super T> predicate)
        {
            return new SelectSortedSetBatch<T>(this, predicate);
        }

        public <V> ListBatch<V> collect(Function<? super T, ? extends V> function)
        {
            return new CollectSortedSetBatch<T, V>(this, function);
        }

        public SortedSetBatch<T> distinct(ConcurrentHashMap<T, Boolean> distinct)
        {
            return this;
        }
    }

    public void forEach(int fromIndex, int toIndex, Procedure<? super T> procedure)
    {
        ListIterate.rangeCheck(fromIndex, toIndex, this.size());

        if (fromIndex > toIndex)
        {
            throw new IllegalArgumentException("fromIndex must not be greater than toIndex");
        }

        for (int i = fromIndex; i <= toIndex; i++)
        {
            procedure.value(this.delegate[i]);
        }
    }

    public void forEachWithIndex(int fromIndex, int toIndex, ObjectIntProcedure<? super T> objectIntProcedure)
    {
        ListIterate.rangeCheck(fromIndex, toIndex, this.size());

        if (fromIndex > toIndex)
        {
            throw new IllegalArgumentException("fromIndex must not be greater than toIndex");
        }

        for (int i = fromIndex; i <= toIndex; i++)
        {
            objectIntProcedure.value(this.delegate[i], i);
        }
    }

    public int indexOf(Object object)
    {
        return ArrayIterate.indexOf(this.delegate, object);
    }
}
