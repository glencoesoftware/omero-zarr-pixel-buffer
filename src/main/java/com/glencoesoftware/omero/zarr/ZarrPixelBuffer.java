/*
 * Copyright (C) 2021 Glencoe Software, Inc. All rights reserved.
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

import java.awt.Dimension;
import java.io.IOException;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.stream.IntStream;

import org.slf4j.LoggerFactory;

import com.bc.zarr.DataType;
import com.bc.zarr.ZarrArray;
import com.github.benmanes.caffeine.cache.AsyncLoadingCache;
import com.github.benmanes.caffeine.cache.Caffeine;

import loci.formats.FormatTools;
import ome.io.nio.DimensionsOutOfBoundsException;
import ome.io.nio.PixelBuffer;
import ome.io.nio.RomioPixelBuffer;
import ome.model.core.Pixels;
import ome.util.PixelData;
import ucar.ma2.InvalidRangeException;

public class ZarrPixelBuffer implements PixelBuffer {

    private static final org.slf4j.Logger log =
            LoggerFactory.getLogger(ZarrPixelBuffer.class);

    /** Reference to the pixels. */
    private final Pixels pixels;

    /** Root of the OME-NGFF multiscale we are operating on */
    private final Path root;

    /** Requested resolution level */
    private int resolutionLevel;

    /** Total number of resolution levels */
    private final int resolutionLevels;

    /** Max Plane Width */
    private final Integer maxPlaneWidth;

    /** Max Plane Height */
    private final Integer maxPlaneHeight;

    /** Zarr attributes present on the root group */
    private final Map<String, Object> rootGroupAttributes;

    /** Zarr array corresponding to the current resolution level */
    private ZarrArray array;

    /**
     * Mapping of Z plane indexes in full resolution to
     * Z plane indexes in current resolution.
     */
    private Map<Integer, Integer> zIndexMap;

    /** { resolutionLevel, z, c, t, x, y, w, h } vs. tile byte array cache */
    private final AsyncLoadingCache<List<Integer>, byte[]> tileCache;

    /** Whether or not the Zarr is on S3 or similar */
    private final boolean isRemote;

    /** Root path vs. metadata cache */
    private final
        AsyncLoadingCache<Path, Map<String, Object>> zarrMetadataCache;

    /** Array path vs. ZarrArray cache */
    private final AsyncLoadingCache<Path, ZarrArray> zarrArrayCache;

    /**
     * Default constructor
     * @param pixels Pixels metadata for the pixel buffer
     * @param root The root of this buffer
     * @param maxTileLength Maximum tile length that can be used during
     * read operations
     * @throws IOException
     */
    public ZarrPixelBuffer(Pixels pixels, Path root, Integer maxPlaneWidth,
            Integer maxPlaneHeight,
            AsyncLoadingCache<Path, Map<String, Object>> zarrMetadataCache,
            AsyncLoadingCache<Path, ZarrArray> zarrArrayCache)
            throws IOException {
        log.info("Creating ZarrPixelBuffer");
        this.pixels = pixels;
        this.root = root;
        this.zarrMetadataCache = zarrMetadataCache;
        this.zarrArrayCache = zarrArrayCache;
        this.isRemote = root.toString().startsWith("s3://")? true : false;
        try {
            rootGroupAttributes = this.zarrMetadataCache.get(this.root).get();
        } catch (ExecutionException|InterruptedException e) {
            throw new IOException(e);
        }
        if (!rootGroupAttributes.containsKey("multiscales")) {
            throw new IllegalArgumentException("Missing multiscales metadata!");
        }
        this.resolutionLevels = this.getResolutionLevels();
        setResolutionLevel(this.resolutionLevels - 1);
        if (this.resolutionLevel < 0) {
            throw new IllegalArgumentException(
                    "This Zarr file has no pixel data");
        }
        this.maxPlaneWidth = maxPlaneWidth;
        this.maxPlaneHeight = maxPlaneHeight;

        tileCache = Caffeine.newBuilder()
                .maximumSize(getSizeC())
                .buildAsync(key -> {
                    int resolutionLevel = key.get(0);
                    int z = key.get(1);
                    int c = key.get(2);
                    int t = key.get(3);
                    int x = key.get(4);
                    int y = key.get(5);
                    int w = key.get(6);
                    int h = key.get(7);
                    int[] shape = new int[] { 1, 1, 1, h, w };
                    byte[] innerBuffer =
                            new byte[(int) length(shape) * getByteWidth()];
                    setResolutionLevel(resolutionLevel);
                    return getTileDirect(z, c, t, x, y, w, h, innerBuffer);
                });
    }

    /**
     * Get Bio-Formats/OMERO pixels type for buffer.
     * @return See above.
     */
    public int getPixelsType() {
        DataType dataType = array.getDataType();
        switch (dataType) {
            case u1:
                return FormatTools.UINT8;
            case i1:
                return FormatTools.INT8;
            case u2:
                return FormatTools.UINT16;
            case i2:
                return FormatTools.INT16;
            case u4:
                return FormatTools.UINT32;
            case i4:
                return FormatTools.INT32;
            case f4:
                return FormatTools.FLOAT;
            case f8:
                return FormatTools.DOUBLE;
            default:
                throw new IllegalArgumentException(
                        "Data type " + dataType + " not supported");
        }
    }

    /**
     * Calculates the pixel length of a given NumPy like "shape".
     * @param shape the NumPy like "shape" to calculate the length of
     * @return See above
     * @see <a href=
     * "https://numpy.org/doc/stable/reference/generated/numpy.shape.html">
     * numpy.shape</a> documentation
     */
    private long length(int[] shape) {
        return IntStream.of(shape)
                .mapToLong(a -> (long) a)
                .reduce(1, Math::multiplyExact);
    }

    private void read(byte[] buffer, int[] shape, int[] offset)
            throws IOException {
        // Check planar read size (sizeX and sizeY only)
        checkReadSize(Arrays.copyOfRange(shape, 3, 5));

        // if reading from a resolution downsampled in Z,
        // adjust the shape/offset for the Z coordinate only
        // this ensures that the correct Zs are read from the correct offsets
        // since the requested shape/offset may not match the underlying array
        int planes = 1;
        int originalZIndex = offset[2];
        if (getSizeZ() != getTrueSizeZ()) {
          offset[2] = zIndexMap.get(originalZIndex);
          planes = shape[2];
          shape[2] = 1;
        }

        try {
            ByteBuffer asByteBuffer = ByteBuffer.wrap(buffer);
            DataType dataType = array.getDataType();
            for (int z=0; z<planes; z++) {
                offset[2] = zIndexMap.get(originalZIndex + z);
                switch (dataType) {
                    case u1:
                    case i1:
                        array.read(buffer, shape, offset);
                        break;
                    case u2:
                    case i2:
                    {
                        short[] data = (short[]) array.read(shape, offset);
                        asByteBuffer.asShortBuffer().put(data);
                        break;
                    }
                    case u4:
                    case i4:
                    {
                        int[] data = (int[]) array.read(shape, offset);
                        asByteBuffer.asIntBuffer().put(data);
                        break;
                    }
                    case i8:
                    {
                        long[] data = (long[]) array.read(shape, offset);
                        asByteBuffer.asLongBuffer().put(data);
                        break;
                    }
                    case f4:
                    {
                        float[] data = (float[]) array.read(shape, offset);
                        asByteBuffer.asFloatBuffer().put(data);
                        break;
                    }
                    case f8:
                    {
                        double[] data = (double[]) array.read(shape, offset);
                        asByteBuffer.asDoubleBuffer().put(data);
                        break;
                    }
                    default:
                        throw new IllegalArgumentException(
                                "Data type " + dataType + " not supported");
                  }
            }
        } catch (InvalidRangeException e) {
            log.error("Error reading Zarr data", e);
            throw new IOException(e);
        } catch (Exception e) {
            log.error("Error reading Zarr data", e);
            throw e;
        }
    }

    private PixelData toPixelData(byte[] buffer) {
        if (buffer == null) {
            return null;
        }
        PixelData d = new PixelData(
                FormatTools.getPixelTypeString(getPixelsType()),
                ByteBuffer.wrap(buffer));
        d.setOrder(ByteOrder.BIG_ENDIAN);
        return d;
    }

    /**
     * Retrieves the array chunk sizes of all subresolutions of this multiscale
     * buffer.
     * @return See above.
     * @throws IOException
     */
    public int[][] getChunks() throws IOException {
        List<Map<String, String>> datasets = getDatasets();
        List<int[]> chunks = new ArrayList<int[]>();
        for (Map<String, String> dataset : datasets) {
            ZarrArray resolutionArray = ZarrArray.open(
                    root.resolve(dataset.get("path")));
            int[] shape = resolutionArray.getChunks();
            chunks.add(shape);
        }
        return chunks.toArray(new int[chunks.size()][]);
    }

    /**
     * Retrieves the datasets metadat of the first multiscale from the root
     * group attributes.
     * @return See above.
     * @see #getRootGroupAttributes()
     * @see #getMultiscalesMetadata()
     */
    public List<Map<String, String>> getDatasets() {
        return (List<Map<String, String>>)
                getMultiscalesMetadata().get(0).get("datasets");
    }

    /**
     * Retrieves the multiscales metadata from the root group attributes.
     * @return See above.
     * @see #getRootGroupAttributes()
     * @see #getDatasets()
     */
    public List<Map<String, Object>> getMultiscalesMetadata() {
        return (List<Map<String, Object>>)
                rootGroupAttributes.get("multiscales");
    }

    /**
     * Returns the current Zarr root group attributes for this buffer.
     * @return See above.
     * @see #getMultiscalesMetadata()
     * @see #getDatasets()
     */
    public Map<String, Object> getRootGroupAttributes() {
        return rootGroupAttributes;
    }

    /**
     * No-op.
     */
    @Override
    public void close() throws IOException {
    }

    /**
     * Implemented as specified by {@link PixelBuffer} I/F.
     * @see PixelBuffer#checkBounds(Integer, Integer, Integer, Integer, Integer)
     */
    @Override
    public void checkBounds(Integer x, Integer y, Integer z, Integer c,
            Integer t)
            throws DimensionsOutOfBoundsException {
        if (x != null && (x > getSizeX() - 1 || x < 0)) {
            throw new DimensionsOutOfBoundsException("X '" + x
                    + "' greater than sizeX '" + getSizeX() + "' or < '0'.");
        }
        if (y != null && (y > getSizeY() - 1 || y < 0)) {
            throw new DimensionsOutOfBoundsException("Y '" + y
                    + "' greater than sizeY '" + getSizeY() + "' or < '0'.");
        }

        if (z != null && (z > getSizeZ() - 1 || z < 0)) {
            throw new DimensionsOutOfBoundsException("Z '" + z
                    + "' greater than sizeZ '" + getSizeZ() + "' or < '0'.");
        }

        if (c != null && (c > getSizeC() - 1 || c < 0)) {
            throw new DimensionsOutOfBoundsException("C '" + c
                    + "' greater than sizeC '" + getSizeC() + "' or < '0'.");
        }

        if (t != null && (t > getSizeT() - 1 || t < 0)) {
            throw new DimensionsOutOfBoundsException("T '" + t
                    + "' greater than sizeT '" + getSizeT() + "' or < '0'.");
        }
    }

    public void checkReadSize(int[] shape) {
        long length = length(shape);
        long maxLength = maxPlaneWidth * maxPlaneHeight;
        if (length > maxLength) {
            throw new IllegalArgumentException(String.format(
                    "Requested shape %s > max plane size %d * %d",
                    Arrays.toString(shape), maxPlaneWidth, maxPlaneHeight));
        }
    }

    @Override
    public Long getPlaneSize() {
        return ((long) getRowSize()) * ((long) getSizeY());
    }

    @Override
    public Integer getRowSize() {
        return getSizeX() * getByteWidth();
    }

    @Override
    public Integer getColSize() {
        return getSizeY() * getByteWidth();
    }

    @Override
    public Long getStackSize() {
        return getPlaneSize() * ((long) getSizeZ());
    }

    @Override
    public Long getTimepointSize() {
        return getStackSize() * ((long) getSizeC());
    }

    @Override
    public Long getTotalSize() {
        return getTimepointSize() * ((long) getSizeT());
    }

    @Override
    public Long getHypercubeSize(List<Integer> offset, List<Integer> size, List<Integer> step)
            throws DimensionsOutOfBoundsException {
        throw new UnsupportedOperationException(
                "Zarr pixel buffer does not support Hypercube access");
    }

    @Override
    public Long getRowOffset(Integer y, Integer z, Integer c, Integer t)
            throws DimensionsOutOfBoundsException {
        throw new UnsupportedOperationException(
                "Zarr pixel buffer does not provide row offsets");
    }

    @Override
    public Long getPlaneOffset(Integer z, Integer c, Integer t)
            throws DimensionsOutOfBoundsException {
        throw new UnsupportedOperationException(
                "Zarr pixel buffer does not provide plane offsets");
    }

    @Override
    public Long getStackOffset(Integer c, Integer t)
            throws DimensionsOutOfBoundsException {
        throw new UnsupportedOperationException(
                "Zarr pixel buffer does not provide stack offsets");
    }

    @Override
    public Long getTimepointOffset(Integer t)
            throws DimensionsOutOfBoundsException {
        throw new UnsupportedOperationException(
                "Zarr pixel buffer does not provide timepoint offsets");
    }

    @Override
    public PixelData getHypercube(
            List<Integer> offset, List<Integer> size, List<Integer> step)
                    throws IOException, DimensionsOutOfBoundsException {
        throw new UnsupportedOperationException(
                "Zarr pixel buffer does not support Hypercube access");
    }

    @Override
    public byte[] getHypercubeDirect(
            List<Integer> offset, List<Integer> size, List<Integer> step,
            byte[] buffer)
                    throws IOException, DimensionsOutOfBoundsException {
        throw new UnsupportedOperationException(
                "Zarr pixel buffer does not support Hypercube access");
    }

    @Override
    public byte[] getPlaneRegionDirect(
            Integer z, Integer c, Integer t, Integer count, Integer offset,
            byte[] buffer)
                    throws IOException, DimensionsOutOfBoundsException {
        throw new UnsupportedOperationException(
                "Zarr pixel buffer does not support plane region access");
    }

    @Override
    public PixelData getTile(
            Integer z, Integer c, Integer t, Integer x, Integer y,
            Integer w, Integer h)
                    throws IOException {
        //Check origin indices > 0
        checkBounds(x, y, z, c, t);
        //Check check bottom-right of tile in bounds
        checkBounds(x + w - 1, y + h - 1, z, c, t);
        //Check planar read size (sizeX and sizeY only), essential that this
        //happens before the similar check in read().  Otherwise we will
        //potentially allocate massive inner buffers in the tile cache
        //asynchronous entry builder that will never be used.
        checkReadSize(new int[] { w, h });

        List<List<Integer>> keys = new ArrayList<List<Integer>>();
        List<Integer> key = null;
        List<Integer> channels = Arrays.asList(new Integer[] { c });
        if (getSizeC() == 3 && isRemote) {
            // Guessing that we are in RGB mode
            channels = Arrays.asList(new Integer[] { 0, 1 ,2 });
        }
        for (Integer channel : channels) {
            List<Integer> v = Arrays.asList(
                    getResolutionLevel(), z, channel, t, x, y, w, h);
            keys.add(v);
            if (channel == c) {
                key = v;
            }
        }
        if (tileCache.getIfPresent(key) == null) {
            // We want to completely invalidate the cache if our key is not
            // present as relying on Caffeine to expire the previous triplicate
            // of channels can be unpredictable.
            tileCache.synchronous().invalidateAll();
        }
        return toPixelData(tileCache.getAll(keys).join().get(key));
    }

    @Override
    public byte[] getTileDirect(
            Integer z, Integer c, Integer t, Integer x, Integer y,
            Integer w, Integer h, byte[] buffer) throws IOException {
        try {
            //Check origin indices > 0
            checkBounds(x, y, z, c, t);
            //Check check bottom-right of tile in bounds
            checkBounds(x + w - 1, y + h - 1, z, c, t);
            int[] shape = new int[] { 1, 1, 1, h, w };
            int[] offset = new int[] { t, c, z, y, x };
            read(buffer, shape, offset);
            return buffer;
        } catch (Exception e) {
            log.error("Error while retrieving pixel data", e);
            return null;
        }
    }

    @Override
    public PixelData getRegion(Integer size, Long offset) throws IOException {
        throw new UnsupportedOperationException(
                "Zarr pixel buffer does not support region access");
    }

    @Override
    public byte[] getRegionDirect(Integer size, Long offset, byte[] buffer)
            throws IOException {
        throw new UnsupportedOperationException(
                "Zarr pixel buffer does not support region access");
    }

    @Override
    public PixelData getRow(Integer y, Integer z, Integer c, Integer t)
            throws IOException, DimensionsOutOfBoundsException {
        return toPixelData(getRowDirect(y, z, c, t, new byte[getRowSize()]));
    }

    @Override
    public PixelData getCol(Integer x, Integer z, Integer c, Integer t)
            throws IOException, DimensionsOutOfBoundsException {
        return toPixelData(getColDirect(x, z, c, t, new byte[getColSize()]));
    }

    @Override
    public byte[] getRowDirect(
            Integer y, Integer z, Integer c, Integer t, byte[] buffer)
                    throws IOException, DimensionsOutOfBoundsException {
        int x = 0;
        int w = getSizeX();
        int h = 1;
        return getTileDirect(z, c, t, x, y, w, h, buffer);
    }

    @Override
    public byte[] getColDirect(
            Integer x, Integer z, Integer c, Integer t, byte[] buffer)
                    throws IOException, DimensionsOutOfBoundsException {
        int y = 0;
        int w = 1;
        int h = getSizeY();
        return getTileDirect(z, c, t, x, y, w, h, buffer);
    }

    @Override
    public PixelData getPlane(Integer z, Integer c, Integer t)
            throws IOException, DimensionsOutOfBoundsException {
        int planeSize = RomioPixelBuffer.safeLongToInteger(getPlaneSize());
        return toPixelData(getPlaneDirect(z, c, t, new byte[planeSize]));
    }

    @Override
    public PixelData getPlaneRegion(Integer x, Integer y, Integer width,
            Integer height, Integer z, Integer c, Integer t, Integer stride)
                    throws IOException, DimensionsOutOfBoundsException {
        throw new UnsupportedOperationException(
                "Zarr pixel buffer does not support plane region access");
    }

    @Override
    public byte[] getPlaneDirect(Integer z, Integer c, Integer t, byte[] buffer)
            throws IOException, DimensionsOutOfBoundsException {
        int y = 0;
        int x = 0;
        int w = getSizeX();
        int h = getSizeY();
        return getTileDirect(z, c, t, x, y, w, h, buffer);
    }

    @Override
    public PixelData getStack(Integer c, Integer t)
            throws IOException, DimensionsOutOfBoundsException {
        int stackSize = RomioPixelBuffer.safeLongToInteger(getStackSize());
        byte[] buffer = new byte[stackSize];
        return toPixelData(getStackDirect(c, t, buffer));
    }

    @Override
    public byte[] getStackDirect(Integer c, Integer t, byte[] buffer)
            throws IOException, DimensionsOutOfBoundsException {
        int x = 0;
        int y = 0;
        int z = 0;
        int w = getSizeX();
        int h = getSizeY();

        //Check origin indices > 0
        checkBounds(x, y, z, c, t);
        //Check check bottom-right of tile in bounds
        checkBounds(x + w - 1, y + h - 1, z, c, t);
        int[] shape = new int[] { 1, 1, getSizeZ(), h, w };
        int[] offset = new int[] { t, c, z, y, x };
        read(buffer, shape, offset);
        return buffer;
    }

    @Override
    public PixelData getTimepoint(Integer t)
            throws IOException, DimensionsOutOfBoundsException {
        int timepointSize =
                RomioPixelBuffer.safeLongToInteger(getTimepointSize());
        byte[] buffer = new byte[timepointSize];
        return toPixelData(getTimepointDirect(t, buffer));
    }

    @Override
    public byte[] getTimepointDirect(Integer t, byte[] buffer)
            throws IOException, DimensionsOutOfBoundsException {
        int x = 0;
        int y = 0;
        int z = 0;
        int c = 0;
        int w = getSizeX();
        int h = getSizeY();

        //Check origin indices > 0
        checkBounds(x, y, z, c, t);
        //Check check bottom-right of tile in bounds
        checkBounds(x + w - 1, y + h - 1, z, c, t);
        int[] shape = new int[] { 1, getSizeC(), getSizeZ(), h, w };
        int[] offset = new int[] { t, c, z, y, x };
        read(buffer, shape, offset);
        return buffer;
    }

    @Override
    public void setTile(
            byte[] buffer, Integer z, Integer c, Integer t,
            Integer x, Integer y, Integer w, Integer h)
                    throws IOException, BufferOverflowException {
        throw new UnsupportedOperationException("Cannot write to Zarr");
    }

    @Override
    public void setRegion(Integer size, Long offset, byte[] buffer)
            throws IOException, BufferOverflowException {
        throw new UnsupportedOperationException("Cannot write to Zarr");
    }

    @Override
    public void setRegion(Integer size, Long offset, ByteBuffer buffer)
            throws IOException, BufferOverflowException {
        throw new UnsupportedOperationException("Cannot write to Zarr");
    }

    @Override
    public void setRow(
            ByteBuffer buffer, Integer y, Integer z, Integer c, Integer t)
                    throws IOException, DimensionsOutOfBoundsException,
                            BufferOverflowException {
        throw new UnsupportedOperationException("Cannot write to Zarr");
    }

    @Override
    public void setPlane(ByteBuffer buffer, Integer z, Integer c, Integer t)
            throws IOException, DimensionsOutOfBoundsException,
                    BufferOverflowException {
        throw new UnsupportedOperationException("Cannot write to Zarr");
    }

    @Override
    public void setPlane(byte[] buffer, Integer z, Integer c, Integer t)
            throws IOException, DimensionsOutOfBoundsException,
                    BufferOverflowException {
        throw new UnsupportedOperationException("Cannot write to Zarr");
    }

    @Override
    public void setStack(ByteBuffer buffer, Integer z, Integer c, Integer t)
            throws IOException, DimensionsOutOfBoundsException,
                    BufferOverflowException {
        throw new UnsupportedOperationException("Cannot write to Zarr");
    }

    @Override
    public void setStack(byte[] buffer, Integer z, Integer c, Integer t)
            throws IOException, DimensionsOutOfBoundsException,
                    BufferOverflowException {
        throw new UnsupportedOperationException("Cannot write to Zarr");
    }

    @Override
    public void setTimepoint(ByteBuffer buffer, Integer t)
            throws IOException, DimensionsOutOfBoundsException,
                    BufferOverflowException {
        throw new UnsupportedOperationException("Cannot write to Zarr");
    }

    @Override
    public void setTimepoint(byte[] buffer, Integer t)
            throws IOException, DimensionsOutOfBoundsException,
                    BufferOverflowException {
        throw new UnsupportedOperationException("Cannot write to Zarr");
    }

    @Override
    public byte[] calculateMessageDigest() throws IOException {
        throw new UnsupportedOperationException(
                "Zarr pixel buffer does not support message digest " +
                "calculation");
    }

    @Override
    public int getByteWidth() {
        return FormatTools.getBytesPerPixel(getPixelsType());
    }

    @Override
    public boolean isSigned() {
        return FormatTools.isSigned(getPixelsType());
    }

    @Override
    public boolean isFloat() {
        return FormatTools.isFloatingPoint(getPixelsType());
    }

    @Override
    public String getPath() {
        return root.toString();
    }

    @Override
    public long getId() {
        return 0;
    }

    @Override
    public int getSizeX() {
        return array.getShape()[4];
    }

    @Override
    public int getSizeY() {
        return array.getShape()[3];
    }

    @Override
    public int getSizeZ() {
        // this is expected to be the Z size of the full resolution array
        return zIndexMap.size();
    }

    /**
     * @return Z size of the current underlying Zarr array
     */
    private int getTrueSizeZ() {
        return array.getShape()[2];
    }

    @Override
    public int getSizeC() {
        return array.getShape()[1];
    }

    @Override
    public int getSizeT() {
        return array.getShape()[0];
    }

    @Override
    public int getResolutionLevels() {
        return getDatasets().size();
    }

    @Override
    public int getResolutionLevel() {
        // The pixel buffer API reverses the resolution level (0 is smallest)
        return Math.abs(
                resolutionLevel - (resolutionLevels - 1));
    }

    @Override
    public void setResolutionLevel(int resolutionLevel) {
        if (resolutionLevel >= resolutionLevels) {
            throw new IllegalArgumentException(
                    "Resolution level out of bounds!");
        }
        // The pixel buffer API reverses the resolution level (0 is smallest)
        this.resolutionLevel = Math.abs(
                resolutionLevel - (resolutionLevels - 1));
        if (this.resolutionLevel < 0) {
            throw new IllegalArgumentException(
                    "This Zarr file has no pixel data");
        }
        if (zIndexMap == null) {
            zIndexMap = new HashMap<Integer, Integer>();
        }
        else {
            zIndexMap.clear();
        }
        try {
            array = zarrArrayCache.get(
                    root.resolve(Integer.toString(this.resolutionLevel))).get();

            ZarrArray fullResolutionArray = zarrArrayCache.get(
                    root.resolve("0")).get();

            // map each Z index in the full resolution array
            // to a Z index in the subresolution array
            // if no Z downsampling, this is just an identity map
            int fullResZ = fullResolutionArray.getShape()[2];
            int arrayZ = array.getShape()[2];
            int zStep = fullResZ / arrayZ;
            for (int z=0; z<fullResZ; z++) {
                zIndexMap.put(z, z * zStep);
            }
        } catch (Exception e) {
            // FIXME: Throw the right exception
            throw new RuntimeException(e);
        }
    }

    @Override
    public Dimension getTileSize() {
        try {
            int[] chunks = getChunks()[resolutionLevel];
            return new Dimension(chunks[4], chunks[3]);
        } catch (Exception e) {
            // FIXME: Throw the right exception
            throw new RuntimeException(e);
        }
    }

    @Override
    public List<List<Integer>> getResolutionDescriptions() {
        try {
            int resolutionLevels = getResolutionLevels();
            List<List<Integer>> resolutionDescriptions =
                    new ArrayList<List<Integer>>();
            int sizeX = pixels.getSizeX();
            int sizeY = pixels.getSizeY();
            for (int i = 0; i < resolutionLevels; i++) {
                double scale = Math.pow(2, i);
                resolutionDescriptions.add(Arrays.asList(
                                (int) (sizeX / scale), (int) (sizeY / scale)));
            }
            return resolutionDescriptions;
        } catch (Exception e) {
            // FIXME: Throw the right exception
            throw new RuntimeException(e);
        }
    }

}
