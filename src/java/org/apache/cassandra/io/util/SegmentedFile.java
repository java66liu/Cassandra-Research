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
package org.apache.cassandra.io.util;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.File;
import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.util.Iterator;
import java.util.NoSuchElementException;

import org.apache.cassandra.config.Config;
import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.io.FSReadError;
import org.apache.cassandra.utils.Pair;

/**
 * Abstracts a read-only file that has been split into segments, each of which can be represented by an independent
 * FileDataInput. Allows for iteration over the FileDataInputs, or random access to the FileDataInput for a given
 * position.
 *
 * The JVM can only map up to 2GB at a time, so each segment is at most that size when using mmap i/o. If a segment
 * would need to be longer than 2GB, that segment will not be mmap'd, and a new RandomAccessFile will be created for
 * each access to that segment.
 */
//这个类表示一个大文件的一个片段，大文件在内存中装不下，所以要一段一段地读。
//子类如下:
//BufferedSegmentedFile
//CompressedSegmentedFile
//MmappedSegmentedFile
//PoolingSegmentedFile (抽象类)
//    BufferedPoolingSegmentedFile
//    CompressedPoolingSegmentedFile
//其中BufferedSegmentedFile和CompressedSegmentedFile在SSTableReader.openForBatch中使用
//其余的都在此类中使用，
//在cassandra.yaml中配置disk_access_mode为mmap时使用MmappedSegmentedFile，其他的使用BufferedPoolingSegmentedFile，
//如果使用了压缩，那么使用CompressedPoolingSegmentedFile(通过调用getCompressedBuilder()，而不管disk_access_mode参数)

//子类要实现的抽象方法有两个: getSegment、cleanup
public abstract class SegmentedFile
{
    public final String path; //要读的文件
    public final long length; //未压缩的长度，length与onDiskLength只有在未压缩时才相等

    // This differs from length for compressed files (but we still need length for
    // SegmentIterator because offsets in the file are relative to the uncompressed size)
    public final long onDiskLength;

    /**
     * Use getBuilder to get a Builder to construct a SegmentedFile.
     */
    SegmentedFile(String path, long length)
    {
        this(path, length, length);
    }

    protected SegmentedFile(String path, long length, long onDiskLength)
    {
        this.path = new File(path).getAbsolutePath();
        this.length = length;
        this.onDiskLength = onDiskLength;
    }

    /**
     * @return A SegmentedFile.Builder.
     */
    public static Builder getBuilder(Config.DiskAccessMode mode)
    {
        return mode == Config.DiskAccessMode.mmap
               ? new MmappedSegmentedFile.Builder()
               : new BufferedPoolingSegmentedFile.Builder();
    }

    public static Builder getCompressedBuilder()
    {
        return new CompressedPoolingSegmentedFile.Builder();
    }

    //注意并不是返回Segment类的实例，而是FileDataInput
    //方法名起得并不那么直观
    public abstract FileDataInput getSegment(long position);

    /**
     * @return An Iterator over segments, beginning with the segment containing the given position: each segment must be closed after use.
     */
    public Iterator<FileDataInput> iterator(long position)
    {
        return new SegmentIterator(position);
    }

    /**
     * Do whatever action is needed to reclaim ressources used by this SegmentedFile.
     */
    public abstract void cleanup();

    /**
     * Collects potential segmentation points in an underlying file, and builds a SegmentedFile to represent it.
     */
    public static abstract class Builder
    {
        /**
         * Adds a position that would be a safe place for a segment boundary in the file. For a block/row based file
         * format, safe boundaries are block/row edges.
         * @param boundary The absolute position of the potential boundary in the file.
         */
        public abstract void addPotentialBoundary(long boundary);

        /**
         * Called after all potential boundaries have been added to apply this Builder to a concrete file on disk.
         * @param path The file on disk.
         */
        public abstract SegmentedFile complete(String path);

        public void serializeBounds(DataOutput out) throws IOException
        {
            out.writeUTF(DatabaseDescriptor.getDiskAccessMode().name());
        }

        public void deserializeBounds(DataInput in) throws IOException
        {
            if (!in.readUTF().equals(DatabaseDescriptor.getDiskAccessMode().name()))
                throw new IOException("Cannot deserialize SSTable Summary component because the DiskAccessMode was changed!");
        }
    }

    //只在MmappedSegmentedFile中使用
    static final class Segment extends Pair<Long, MappedByteBuffer> implements Comparable<Segment>
    {
        public Segment(long offset, MappedByteBuffer segment)
        {
            super(offset, segment);
        }

        public final int compareTo(Segment that)
        {
            return (int)Math.signum(this.left - that.left); //返回0、1或-1(表示=、>、<)
        }
    }

    /**
     * A lazy Iterator over segments in forward order from the given position.  It is caller's responsibility
     * to close the FileDataIntputs when finished.
     */
    final class SegmentIterator implements Iterator<FileDataInput>
    {
        private long nextpos;
        public SegmentIterator(long position)
        {
            this.nextpos = position;
        }

        public boolean hasNext()
        {
            return nextpos < length;
        }

        public FileDataInput next()
        {
            long position = nextpos;
            if (position >= length)
                throw new NoSuchElementException();

            FileDataInput segment = getSegment(nextpos);
            try
            {
                nextpos = nextpos + segment.bytesRemaining();
            }
            catch (IOException e)
            {
                throw new FSReadError(e, path);
            }
            return segment;
        }

        public void remove() { throw new UnsupportedOperationException(); }
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "(path='" + path + "'" +
               ", length=" + length +
               ")";
}
}
