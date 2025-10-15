/*
 * Copyright (C) 2024 Glencoe Software, Inc. All rights reserved.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, write to the Free Software Foundation, Inc.,
 * 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 */

package com.glencoesoftware.omero.zarr;

import com.amazonaws.services.s3.model.AmazonS3Exception;
import com.amazonaws.services.s3.model.GetObjectRequest;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.services.s3.model.S3ObjectInputStream;
import com.bc.zarr.Compressor;
import com.bc.zarr.DataType;
import com.bc.zarr.ZarrArray;
import com.upplication.s3fs.S3Path;
import com.upplication.s3fs.S3ReadOnlySeekableByteChannel;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.OpenOption;
import java.nio.file.ReadOnlyFileSystemException;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import org.perf4j.slf4j.Slf4JStopWatch;


/**
 * Overridden, hybrid version of the implementation from
 * {@link S3ReadOnlySeekableByteChannel}.  Due to its private visibility on
 * nearly all the important instance variables much of the implementation is
 * copied verbatim.
 */
public class OmeroS3ReadOnlySeekableByteChannel implements SeekableByteChannel {

    private Set<? extends OpenOption> options;
    private final long length;
    byte[] data;
    private final ReadableByteChannel rbc;
    private long position = 0;

    /**
     * Overridden, hybrid version of the implementation from
     * {@link S3ReadOnlySeekableByteChannel}.  Our implementation loads the
     * entire object in full from S3 without checks for length during
     * object construction.
     */
    public OmeroS3ReadOnlySeekableByteChannel(S3Path path, Set<? extends OpenOption> options)
          throws IOException {
        this.options = Collections.unmodifiableSet(new HashSet<>(options));

        if (this.options.contains(StandardOpenOption.WRITE)
                || this.options.contains(StandardOpenOption.CREATE)
                || this.options.contains(StandardOpenOption.CREATE_NEW)
                || this.options.contains(StandardOpenOption.APPEND)
            ) {
            throw new ReadOnlyFileSystemException();
        }

        String bucketName = path.getFileStore().name();
        String key = path.getKey();
        GetObjectRequest getObjectRequest =
            new GetObjectRequest(bucketName, key);

        Slf4JStopWatch t0 = new Slf4JStopWatch(
                "OmeroS3ReadOnlySeekableByteChannel.getObject",
                "s3://" + bucketName + "/" + key);
        try {
            S3Object s3Object =
                path
                    .getFileSystem()
                    .getClient()
                    .getObject(getObjectRequest);
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            // The return value of getObjectContent should be copied and
            // the stream closed as quickly as possible. See
            // https://docs.aws.amazon.com/AWSJavaSDK/latest/javadoc/com/amazonaws/services/s3/model/S3Object.html#getObjectContent--
            try (S3ObjectInputStream s3Stream = s3Object.getObjectContent()) {
                byte[] readBuf = new byte[1024 * 1024];
                int readLen = 0;
                while ((readLen = s3Stream.read(readBuf)) > 0) {
                    outputStream.write(readBuf, 0, readLen);
                }
            }
            this.data = outputStream.toByteArray();
        } catch (AmazonS3Exception e) {
            if (e.getStatusCode() != 404) {
                e.printStackTrace();
                throw e;
            }
            // Structure is .zarrayDir/t/c/z/y/x - so we need to go up 5 levels
            ZarrArray array = ZarrArray.open(
                    path.getParent().getParent().getParent().getParent().getParent());
            int[] chunkSize = array.getChunks();
            DataType dtype = array.getDataType();
            int chunkArraySize = 1;
            for (int i = 0; i < chunkSize.length; i++) {
                chunkArraySize *= chunkSize[i];
            }
            int dtypeSize = getSizeFromDtype(dtype.toString());
            ByteBuffer bbuffer = ByteBuffer.allocate(chunkArraySize * dtypeSize);
            switch (dtype) {
                case u1:
                case i1:
                    fillChunku1i1(array, bbuffer, chunkArraySize);
                    break;
                case u2:
                case i2:
                {
                    fillChunku2i2(array, bbuffer, chunkArraySize);
                    break;
                }
                case u4:
                case i4:
                {
                    fillChunku4i4(array, bbuffer, chunkArraySize);
                    break;
                }
                case i8:
                {
                    fillChunki8(array, bbuffer, chunkArraySize);
                    break;
                }
                case f4:
                {
                    fillChunkf4(array, bbuffer, chunkArraySize);
                    break;
                }
                case f8:
                {
                    fillChunkf8(array, bbuffer, chunkArraySize);
                    break;
                }
                default:
                    throw new IllegalArgumentException(
                            "Data type " + dtype.toString() + " not supported");
            }

            Compressor compressor = array.getCompressor();
            ByteArrayOutputStream fillValOutputStream = new ByteArrayOutputStream();
            compressor.compress(new ByteArrayInputStream(bbuffer.array()), fillValOutputStream);
            this.data = fillValOutputStream.toByteArray();
        } finally {
            t0.stop();
        }
        this.length = this.data.length;
        ByteArrayInputStream inputStream = new ByteArrayInputStream(data);
        rbc = Channels.newChannel(inputStream);
        this.position = 0;
    }

    private void fillChunku1i1(ZarrArray array, ByteBuffer bbuf, int chunkArraySize) {
        byte fillValue = array.getFillValue().byteValue();
        for (int i = 0; i < chunkArraySize; i++) {
            bbuf.put(fillValue);
        }
    }

    private void fillChunku2i2(ZarrArray array, ByteBuffer bbuf, int chunkArraySize) {
        short fillValue = array.getFillValue().shortValue();
        for (int i = 0; i < chunkArraySize; i++) {
            bbuf.putShort(fillValue);
        }
    }

    private void fillChunku4i4(ZarrArray array, ByteBuffer bbuf, int chunkArraySize) {
        int fillValue = array.getFillValue().intValue();
        for (int i = 0; i < chunkArraySize; i++) {
            bbuf.putInt(fillValue);
        }
    }

    private void fillChunki8(ZarrArray array, ByteBuffer bbuf, int chunkArraySize) {
        long fillValue = array.getFillValue().longValue();
        for (int i = 0; i < chunkArraySize; i++) {
            bbuf.putLong(fillValue);
        }
    }

    private void fillChunkf4(ZarrArray array, ByteBuffer bbuf, int chunkArraySize) {
        float fillValue = array.getFillValue().floatValue();
        for (int i = 0; i < chunkArraySize; i++) {
            bbuf.putFloat(fillValue);
        }
    }

    private void fillChunkf8(ZarrArray array, ByteBuffer bbuf, int chunkArraySize) {
        double fillValue = array.getFillValue().doubleValue();
        for (int i = 0; i < chunkArraySize; i++) {
            bbuf.putDouble(fillValue);
        }
    }

    private int getSizeFromDtype(String dtype) {
        return Integer.valueOf(dtype.substring(1));
    }

    /**
     * An exact copy of the implementation from
     * {@link S3ReadOnlySeekableByteChannel} due to its instance variable
     * private visibility.
     */
    @Override
    public boolean isOpen() {
        return rbc.isOpen();
    }

    /**
     * An exact copy of the implementation from
     * {@link S3ReadOnlySeekableByteChannel} due to its instance variable
     * private visibility.
     */
    @Override
    public long position() {
        return position;
    }

    /**
     * Overridden, hybrid version of the implementation from
     * {@link S3ReadOnlySeekableByteChannel}.  Our implementation does not
     * support repositioning within the channel.
     */
    @Override
    public SeekableByteChannel position(long targetPosition)
            throws IOException {
        throw new UnsupportedOperationException();
    }

    /**
     * An exact copy of the implementation from
     * {@link S3ReadOnlySeekableByteChannel} due to its instance variable
     * private visibility.
     */
    @Override
    public int read(ByteBuffer dst) throws IOException {
        int n = rbc.read(dst);
        if (n > 0) {
            position += n;
        }
        return n;
    }

    /**
     * An exact copy of the implementation from
     * {@link S3ReadOnlySeekableByteChannel} due to its instance variable
     * private visibility.
     */
    @Override
    public SeekableByteChannel truncate(long size) throws IOException {
        throw new NonWritableChannelException();
    }

    /**
     * An exact copy of the implementation from
     * {@link S3ReadOnlySeekableByteChannel} due to its instance variable
     * private visibility.
     */
    @Override
    public int write(ByteBuffer src) throws IOException {
        throw new NonWritableChannelException();
    }

    /**
     * An exact copy of the implementation from
     * {@link S3ReadOnlySeekableByteChannel} due to its instance variable
     * private visibility.
     */
    @Override
    public long size() throws IOException {
        return length;
    }

    /**
     * An exact copy of the implementation from
     * {@link S3ReadOnlySeekableByteChannel} due to its instance variable
     * private visibility.
     */
    @Override
    public void close() throws IOException {
        rbc.close();
    }
}
