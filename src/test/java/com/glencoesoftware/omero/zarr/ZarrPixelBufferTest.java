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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.glencoesoftware.bioformats2raw.Converter;
import com.glencoesoftware.bioformats2raw.SupportedVersions;
import com.glencoesoftware.omero.zarr.ZarrPixelBuffer.Axis;
import dev.zarr.zarrjava.ZarrException;
import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import loci.formats.FormatTools;
import loci.formats.in.FakeReader;
import ome.io.nio.DimensionsOutOfBoundsException;
import ome.model.core.Pixels;
import ome.model.enums.DimensionOrder;
import ome.xml.model.enums.PixelType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import picocli.CommandLine;

/** Unit tests for ZarrPixelBuffer. */
public class ZarrPixelBufferTest {

    Path tmpDir;

    /** Set-up.*/
    @BeforeEach
    public void setup(@TempDir Path dir) throws Exception {
        tmpDir = dir;
    }

    /** Constructor. */
    public ZarrPixelBuffer createPixelBuffer(Pixels pixels, ZarrStore store, Integer maxPlaneWidth,
        Integer maxPlaneHeight) throws IOException {
        return new ZarrPixelBuffer(pixels, store, maxPlaneWidth, maxPlaneHeight,
            Caffeine.newBuilder().maximumSize(0).buildAsync(ZarrPixelsService::getZarrMetadata),
            Caffeine.newBuilder().maximumSize(0).buildAsync(ZarrPixelsService::getZarrArray));
    }

    /**
     * Run the bioformats2raw main method and check for success or failure.
     *
     * @param additionalArgs CLI arguments as needed beyond "input output"
     */
    void assertBioFormats2Raw(Path input, Path output, String ngffVersion, String... additionalArgs)
            throws IOException {
        List<String> args = new ArrayList<String>();
        if (ngffVersion != null) {
            args.add("--ngff-version");
            args.add(ngffVersion);
        }
        for (String arg : additionalArgs) {
            args.add(arg);
        }
        args.add(input.toString());
        args.add(output.toString());
        try {
            Converter converter = new Converter();
            CommandLine cli = new CommandLine(converter);
            cli.execute(args.toArray(new String[]{}));
            if (ngffVersion != null && ngffVersion.equals(SupportedVersions.NGFF_05.toString())) {
                assertTrue(Files.exists(output.resolve("zarr.json")));
            } else {
                assertTrue(Files.exists(output.resolve(".zattrs")));
            }
            assertTrue(Files.exists(
                    output.resolve("OME").resolve("METADATA.ome.xml")));
        } catch (RuntimeException rt) {
            throw rt;
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    static Path fake(String... args) {
        assertTrue(args.length % 2 == 0);
        Map<String, String> options = new HashMap<String, String>();
        for (int i = 0; i < args.length; i += 2) {
            options.put(args[i], args[i + 1]);
        }
        return fake(options);
    }

    static Path fake(Map<String, String> options) {
        return fake(options, null);
    }

    /**
     * Create a Bio-Formats fake INI file to use for testing.
     *
     * @param options map of the options to assign as part of the fake filename
     *     from the allowed keys
     * @param series map of the integer series index and options map (same format
     *     as <code>options</code> to add to the fake INI content
     * @see <a href="https://bio-formats.readthedocs.io/en/latest/developers/generating-test-images.html#key-value-pairs">fake file specification</a>
     * @return path to the fake INI file that has been created
     */
    static Path fake(Map<String, String> options,
            Map<Integer, Map<String, String>> series) {
        return fake(options, series, null);
    }

    static Path fake(Map<String, String> options,
            Map<Integer, Map<String, String>> series,
            Map<String, String> originalMetadata) {
        StringBuilder sb = new StringBuilder();
        sb.append("image");
        if (options != null) {
            for (Map.Entry<String, String> kv : options.entrySet()) {
                sb.append("&");
                sb.append(kv.getKey());
                sb.append("=");
                sb.append(kv.getValue());
            }
        }
        sb.append("&");
        try {
            List<String> lines = new ArrayList<String>();
            if (originalMetadata != null) {
                lines.add("[GlobalMetadata]");
                for (String key : originalMetadata.keySet()) {
                    lines.add(String.format(
                            "%s=%s", key, originalMetadata.get(key)));
                }
            }
            if (series != null) {
                for (int s : series.keySet()) {
                    Map<String, String> seriesOptions = series.get(s);
                    lines.add(String.format("[series_%d]", s));
                    for (String key : seriesOptions.keySet()) {
                        lines.add(String.format(
                                "%s=%s", key, seriesOptions.get(key)));
                    }
                }
            }
            Path ini = Files.createTempFile(sb.toString(), ".fake.ini");
            File iniAsFile = ini.toFile();
            String iniPath = iniAsFile.getAbsolutePath();
            String fakePath = iniPath.substring(0, iniPath.length() - 4);
            Path fake = Paths.get(fakePath);
            Files.write(fake, new byte[]{});
            Files.write(ini, lines);
            iniAsFile.deleteOnExit();
            File fakeAsFile = fake.toFile();
            fakeAsFile.deleteOnExit();
            return ini;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /** Write Zarr multiscales attributes. */
    public Path writeTestZarr(int sizeT, int sizeC, int sizeZ, int sizeY, int sizeX,
        String pixelType, String ngffVersion, String... options) throws IOException {
        Path output = tmpDir.resolve("output.zarr");
        writeTestZarr(output, sizeT, sizeC, sizeZ, sizeY, sizeX, pixelType, ngffVersion,
            options);
        return output;
    }

    /** Write Zarr multiscales attributes. */
    public void writeTestZarr(
        Path path,
        int sizeT,
        int sizeC,
        int sizeZ,
        int sizeY,
        int sizeX,
        String pixelType,
        String ngffVersion,
        String... options) throws IOException {

        Path input = fake(
                "sizeT", Integer.toString(sizeT),
                "sizeC", Integer.toString(sizeC),
                "sizeZ", Integer.toString(sizeZ),
                "sizeY", Integer.toString(sizeY),
                "sizeX", Integer.toString(sizeX),
                "pixelType", pixelType);
        assertBioFormats2Raw(input, path, ngffVersion, options);
    }

    private void assertPixels(
            ZarrPixelBuffer zpb, int sizeX, int sizeY, int sizeZ, int sizeC, int sizeT,
            String pixelType, String order) throws IOException {
        // Check dimensions
        assertEquals(sizeT, zpb.getSizeT());
        assertEquals(sizeC, zpb.getSizeC());
        assertEquals(sizeZ, zpb.getSizeZ());
        assertEquals(sizeY, zpb.getSizeY());
        assertEquals(sizeX, zpb.getSizeX());
        int bytesPerPixel = FormatTools.getBytesPerPixel(pixelType);
        int planeSize = zpb.getPlaneSize().intValue();
        assertEquals(sizeX * sizeY * bytesPerPixel, planeSize);
        int stackSize = zpb.getStackSize().intValue();
        assertEquals(sizeZ * sizeX * sizeY * bytesPerPixel, stackSize);
        int timepointSize = zpb.getTimepointSize().intValue();
        assertEquals(sizeC * sizeZ * sizeX * sizeY * bytesPerPixel, timepointSize);
        int totalSize = sizeT * sizeC * sizeZ * sizeX * sizeY * bytesPerPixel;
        assertEquals(totalSize, zpb.getTotalSize().intValue());
        assertEquals(sizeY * bytesPerPixel, zpb.getColSize().longValue());
        assertEquals(sizeX * bytesPerPixel, zpb.getRowSize().longValue());

        // Check pixel types
        int pixelsTypeInt = FormatTools.pixelTypeFromString(pixelType);
        assertEquals(pixelsTypeInt, zpb.getPixelsType());
        assertEquals(FormatTools.isSigned(pixelsTypeInt), zpb.isSigned());
        assertEquals(FormatTools.isFloatingPoint(pixelsTypeInt), zpb.isFloat());
        assertEquals(bytesPerPixel, zpb.getByteWidth());

        // Check column/row/tile/plane/stack/timepoint reading
        byte[] timepointFromPlanes = new byte[timepointSize];
        byte[] stackFromPlanes =  new byte[stackSize];
        List<String> channelFirstOrders = Arrays.asList("XYZCT", "XYZTC", "XYTZC");
        int timepointIndex;
        for (int t = 0; t < sizeT; t++) {
            for (int c = 0; c < sizeC; c++) {
                for (int z = 0; z < sizeZ; z++) {
                    // Check special pixels are correctly read from getTile/getPlane
                    int planeNumber = FormatTools.getIndex(
                        DimensionOrder.VALUE_XYZCT,
                        sizeZ, sizeC, sizeT, sizeZ * sizeC * sizeT,
                        z, c, t);
                    int[] specialPixels = new int[] { 0, planeNumber, z, c, t };
                    byte[] tileData = zpb.getTile(z, c, t, 0, 0, sizeX, sizeY).getData().array();
                    assertArrayEquals(specialPixels,
                        FakeReader.readSpecialPixels(
                            tileData, FormatTools.pixelTypeFromString(pixelType), false));
                    byte[] planeData = zpb.getPlane(z, c, t).getData().array();
                    assertArrayEquals(specialPixels,
                        FakeReader.readSpecialPixels(
                            planeData, FormatTools.pixelTypeFromString(pixelType), false));

                    // Check getCol / getRow match getTile
                    assertArrayEquals(
                        zpb.getTile(z, c, t, 0, 0, sizeX, 1).getData().array(),
                        zpb.getRow(0, z, c, t).getData().array());
                    assertArrayEquals(
                        zpb.getTile(z, c, t, 0, sizeY / 2, sizeX, 1).getData().array(),
                        zpb.getRow(sizeY / 2, z, c, t).getData().array());
                    assertArrayEquals(
                        zpb.getTile(z, c, t, 0, sizeY - 1, sizeX, 1).getData().array(),
                        zpb.getRow(sizeY - 1, z, c, t).getData().array());
                    assertArrayEquals(
                        zpb.getTile(z, c, t, 0, 0, 1, sizeY).getData().array(),
                        zpb.getCol(0, z, c, t).getData().array());
                    assertArrayEquals(
                        zpb.getTile(z, c, t, sizeX / 2, 0, 1, sizeY).getData().array(),
                        zpb.getCol(sizeX / 2, z, c, t).getData().array());
                    assertArrayEquals(
                        zpb.getTile(z, c, t, sizeX - 1, 0, 1, sizeY).getData().array(),
                        zpb.getCol(sizeX - 1, z, c, t).getData().array());

                    System.arraycopy(planeData, 0, stackFromPlanes, z * planeSize, planeSize);
                    if (channelFirstOrders.contains(order)) {
                        timepointIndex = c * stackSize + z * planeSize;
                    } else {
                        timepointIndex = z * sizeC * planeSize + c * planeSize;
                    }
                    System.arraycopy(planeData, 0, timepointFromPlanes, timepointIndex, planeSize);
                }
                assertArrayEquals(zpb.getStack(c, t).getData().array(), stackFromPlanes);
            }
            assertArrayEquals(zpb.getTimepoint(t).getData().array(), timepointFromPlanes);
        }
    }

    static Stream<Arguments> getDimensions() {
        return Stream.of(
            Arguments.of(128, 64, 2, 3, 4, "uint8", "0.4"),
            Arguments.of(128, 64, 1, 3, 4, "uint8", "0.4"),
            Arguments.of(128, 64, 2, 1, 4, "uint8", "0.4"),
            Arguments.of(128, 64, 2, 3, 1, "uint8", "0.4"),
            Arguments.of(128, 64, 2, 1, 1, "uint8", "0.4"),
            Arguments.of(128, 64, 1, 1, 4, "uint8", "0.4"),
            Arguments.of(128, 64, 1, 3, 1, "uint8", "0.4"),
            Arguments.of(128, 64, 2, 1, 1, "uint8", "0.4"),
            Arguments.of(128, 64, 1, 1, 1, "uint8", "0.4"),
            Arguments.of(128, 64, 2, 3, 4, "uint8", "0.5"),
            Arguments.of(128, 64, 1, 3, 4, "uint8", "0.5"),
            Arguments.of(128, 64, 2, 1, 4, "uint8", "0.5"),
            Arguments.of(128, 64, 2, 3, 1, "uint8", "0.5"),
            Arguments.of(128, 64, 2, 1, 1, "uint8", "0.5"),
            Arguments.of(128, 64, 1, 1, 4, "uint8", "0.5"),
            Arguments.of(128, 64, 1, 3, 1, "uint8", "0.5"),
            Arguments.of(128, 64, 2, 1, 1, "uint8", "0.5"),
            Arguments.of(128, 64, 1, 1, 1, "uint8", "0.5")
        );
    }

    static Stream<Arguments> getPixelTypes() {
        return Stream.of(
            Arguments.of(128, 64, 1, 1, 1, "int8", "0.4"),
            Arguments.of(128, 64, 1, 1, 1, "int16", "0.4"),
            Arguments.of(128, 64, 1, 1, 1, "int32", "0.4"),
            Arguments.of(128, 64, 1, 1, 1, "uint8", "0.4"),
            Arguments.of(128, 64, 1, 1, 1, "uint16", "0.4"),
            Arguments.of(128, 64, 1, 1, 1, "uint32", "0.4"),
            Arguments.of(128, 64, 1, 1, 1, "float", "0.4"),
            Arguments.of(128, 64, 1, 1, 1, "double", "0.4"),
            Arguments.of(128, 64, 1, 1, 1, "int8", "0.5"),
            Arguments.of(128, 64, 1, 1, 1, "int16", "0.5"),
            Arguments.of(128, 64, 1, 1, 1, "int32", "0.5"),
            Arguments.of(128, 64, 1, 1, 1, "uint8", "0.5"),
            Arguments.of(128, 64, 1, 1, 1, "uint16", "0.5"),
            Arguments.of(128, 64, 1, 1, 1, "uint32", "0.5"),
            Arguments.of(128, 64, 1, 1, 1, "float", "0.5"),
            Arguments.of(128, 64, 1, 1, 1, "double", "0.5")
        );
    }

    /**
     * Test dimensions and pixel types.
     */
    @ParameterizedTest
    @MethodSource({"getDimensions", "getPixelTypes"})
    public void testPixels(int sizeX, int sizeY, int sizeZ, int sizeC, int sizeT,
            String pixelType, String version)
            throws IOException, URISyntaxException, ZarrException {
        Path output = writeTestZarr(sizeT, sizeC, sizeZ, sizeY, sizeX, pixelType, version);
        ZarrStore store = new ZarrStore(output.resolve("0").toString());
        Pixels pixels = new Pixels(null, null, sizeX, sizeY, sizeZ, sizeC, sizeT, "", null);
        try (ZarrPixelBuffer zpb = createPixelBuffer(pixels, store, sizeX, sizeY)) {
            assertPixels(zpb, sizeX, sizeY, sizeZ, sizeC, sizeT, pixelType, "XYZCT");
        }
    }

    /**
     * Test compact dimensions.
     */
    @ParameterizedTest
    @MethodSource("getDimensions")
    public void testPixelsCompactDimensions(int sizeX, int sizeY, int sizeZ, int sizeC, int sizeT,
            String pixelType, String version)
            throws IOException, URISyntaxException, ZarrException {
        Path output = writeTestZarr(
            sizeT, sizeC, sizeZ, sizeY, sizeX, pixelType, version, "--compact");
        ZarrStore store = new ZarrStore(output.resolve("0").toString());
        Pixels pixels = new Pixels(null, null, sizeX, sizeY, sizeZ, sizeC, sizeT, "", null);
        try (ZarrPixelBuffer zpb = createPixelBuffer(pixels, store, sizeX, sizeY)) {
            assertPixels(zpb, sizeX, sizeY, sizeZ, sizeC, sizeT, pixelType, "XYZCT");
        }
    }


    static Stream<Arguments> getDimensionOrders() {
        return Stream.of(
            Arguments.of(128, 64, 2, 3, 4, "uint8", "XYZCT", "0.4"),
            Arguments.of(128, 64, 2, 3, 4, "uint8", "XYZTC", "0.4"),
            Arguments.of(128, 64, 2, 3, 4, "uint8", "XYCZT", "0.4"),
            Arguments.of(128, 64, 2, 3, 4, "uint8", "XYCTZ", "0.4"),
            Arguments.of(128, 64, 2, 3, 4, "uint8", "XYTCZ", "0.4"),
            Arguments.of(128, 64, 2, 3, 4, "uint8", "XYTZC", "0.4"),
            Arguments.of(128, 64, 2, 3, 4, "uint8", "XYZCT", "0.5"),
            Arguments.of(128, 64, 2, 3, 4, "uint8", "XYZTC", "0.5"),
            Arguments.of(128, 64, 2, 3, 4, "uint8", "XYCZT", "0.5"),
            Arguments.of(128, 64, 2, 3, 4, "uint8", "XYCTZ", "0.5"),
            Arguments.of(128, 64, 2, 3, 4, "uint8", "XYTCZ", "0.5"),
            Arguments.of(128, 64, 2, 3, 4, "uint8", "XYTZC", "0.5")
        );
    }

    /**
     * Test dimension orders.
     */
    @ParameterizedTest
    @MethodSource("getDimensionOrders")
    public void testPixelsDimensionOrder(int sizeX, int sizeY, int sizeZ, int sizeC, int sizeT,
            String pixelType, String order, String version)
            throws IOException, URISyntaxException, ZarrException {
        Path output = writeTestZarr(
            sizeT, sizeC, sizeZ, sizeY, sizeX, pixelType, version, "--dimension-order", order);
        ZarrStore store = new ZarrStore(output.resolve("0").toString());
        Pixels pixels = new Pixels(null, null, sizeX, sizeY, sizeZ, sizeC, sizeT, "", null);
        try (ZarrPixelBuffer zpb = createPixelBuffer(pixels, store, sizeX, sizeY)) {
            assertPixels(zpb, sizeX, sizeY, sizeZ, sizeC, sizeT, pixelType, order);
        }
    }


    static Stream<Arguments> getCompressions() {
        return Stream.of(
            Arguments.of(128, 64, 1, 1, 1, "uint8", "0.4", "null"),
            Arguments.of(128, 64, 1, 1, 1, "uint8", "0.4", "blosc"),
            Arguments.of(128, 64, 1, 1, 1, "uint8", "0.4", "zlib"),
            Arguments.of(128, 64, 1, 1, 1, "int32", "0.4", "null"),
            Arguments.of(128, 64, 1, 1, 1, "int32", "0.4", "blosc"),
            Arguments.of(128, 64, 1, 1, 1, "int32", "0.4", "zlib"),
            Arguments.of(128, 64, 1, 1, 1, "double", "0.4", "null"),
            Arguments.of(128, 64, 1, 1, 1, "double", "0.4", "blosc"),
            Arguments.of(128, 64, 1, 1, 1, "double", "0.4", "zlib"),
            Arguments.of(128, 64, 1, 1, 1, "uint8", "0.5", "null"),
            Arguments.of(128, 64, 1, 1, 1, "uint8", "0.5", "blosc"),
            Arguments.of(128, 64, 1, 1, 1, "uint8", "0.5", "gzip"),
            Arguments.of(128, 64, 1, 1, 1, "uint8", "0.5", "zstd"),
            Arguments.of(128, 64, 1, 1, 1, "int32", "0.5", "null"),
            Arguments.of(128, 64, 1, 1, 1, "int32", "0.5", "blosc"),
            Arguments.of(128, 64, 1, 1, 1, "int32", "0.5", "gzip"),
            Arguments.of(128, 64, 1, 1, 1, "int32", "0.5", "zstd"),
            Arguments.of(128, 64, 1, 1, 1, "double", "0.5", "null"),
            Arguments.of(128, 64, 1, 1, 1, "double", "0.5", "blosc"),
            Arguments.of(128, 64, 1, 1, 1, "double", "0.5", "gzip"),
            Arguments.of(128, 64, 1, 1, 1, "double", "0.5", "zstd")
        );
    }

    /**
     * Test compressions.
     */
    @ParameterizedTest
    @MethodSource("getCompressions")
    public void testPixelsCompression(int sizeX, int sizeY, int sizeZ, int sizeC, int sizeT,
            String pixelType, String version, String compression)
            throws IOException, URISyntaxException, ZarrException {
        Path output = writeTestZarr(
            sizeT, sizeC, sizeZ, sizeY, sizeX, pixelType, version, "--compression", compression);
        ZarrStore store = new ZarrStore(output.resolve("0").toString());
        Pixels pixels = new Pixels(null, null, sizeX, sizeY, sizeZ, sizeC, sizeT, "", null);
        try (ZarrPixelBuffer zpb = createPixelBuffer(pixels, store, sizeX, sizeY)) {
            assertPixels(zpb, sizeX, sizeY, sizeZ, sizeC, sizeT, pixelType, "XYZCT");
        }
    }

    static Stream<Arguments> getChunks() {
        return Stream.of(
            Arguments.of(1024, 1024, 1, "0.4"),
            Arguments.of(1024, 1024, 1, "0.5"),
            Arguments.of(512, 512, 4, "0.4"),
            Arguments.of(512, 512, 4, "0.5"),
            Arguments.of(256, 256, 8, "0.4"),
            Arguments.of(256, 256, 8, "0.5"),
            Arguments.of(512, 256, 8, "0.4"),
            Arguments.of(512, 256, 8, "0.5")
        );
    }

    /** Test chunk and tile sizes. */
    @ParameterizedTest
    @MethodSource("getChunks")
    public void testChunks(int tileWidth, int tileHeight, int chunkDepth, String version)
            throws IOException, URISyntaxException, ZarrException {
        int sizeX = 1024;
        int sizeY = 2048;
        int resolutions = 3;
        Path output = writeTestZarr(
            1, 1, 8, sizeY, sizeX, "uint8", version,
            "--resolutions", String.valueOf(resolutions),
            "--tile-width", String.valueOf(tileWidth),
            "--tile-height", String.valueOf(tileHeight),
            "--chunk-depth", String.valueOf(chunkDepth),
            "--shard-width", String.valueOf(1),
            "--shard-height", String.valueOf(1),
            "--shard-depth", String.valueOf(1),
            "--no-tiles");
        ZarrStore store = new ZarrStore(output.resolve("0").toString());
        Pixels pixels = new Pixels(null, null, sizeX, sizeY, 4, 1, 1, "", null);
        try (ZarrPixelBuffer zpb = createPixelBuffer(pixels, store, 1024, 1024)) {
            int[][] chunks = zpb.getChunks();
            assertEquals(chunks.length, resolutions);
            for (int resolution = 0; resolution < resolutions; resolution++) {
                int scale = (int) Math.pow(2, resolution);
                int expectedWidth = Math.min(tileWidth, sizeX / scale);
                int expectedHeight = Math.min(tileHeight, sizeY / scale);
                int[] expectedChunks = new int[] {1, 1, chunkDepth, expectedHeight, expectedWidth};
                assertArrayEquals(chunks[resolution], expectedChunks);

                zpb.setResolutionLevel(resolutions - resolution - 1);
                assertEquals(zpb.getTileSize().getWidth(), expectedWidth);
                assertEquals(zpb.getTileSize().getHeight(), expectedHeight);
            }
        }
    }

    static Stream<Arguments> getShards() {
        return Stream.of(
            Arguments.of(1024, 1024, 1, 1024, 1024, 1, "0.5"),
            Arguments.of(1024, 1024, 1, 1024, 1024, 4, "0.5"),
            Arguments.of(1024, 1024, 1, 1024, 1024, 8, "0.5"),
            Arguments.of(512, 512, 4, 512, 512, 4, "0.5"),
            Arguments.of(512, 512, 4, 512, 512, 8, "0.5"),
            Arguments.of(512, 512, 4, 1024, 1024, 4, "0.5"),
            Arguments.of(512, 512, 4, 1024, 1024, 8, "0.5"),
            Arguments.of(256, 256, 8, 256, 256, 8, "0.5"),
            Arguments.of(512, 256, 8, 1024, 1024, 8, "0.5")
        );
    }

    /** Test chunk and tile sizes. */
    @ParameterizedTest
    @MethodSource("getShards")
    public void testShards(int tileWidth, int tileHeight, int chunkDepth, int shardWidth,
            int shardHeight, int shardDepth, String version)
            throws IOException, URISyntaxException, ZarrException {
        int sizeX = 1024;
        int sizeY = 2048;
        int resolutions = 3;
        Path output = writeTestZarr(
            1, 1, 8, sizeY, sizeX, "uint8", version,
            "--resolutions", String.valueOf(resolutions),
            "--tile-width", String.valueOf(tileWidth),
            "--shard-width", String.valueOf(shardWidth),
            "--tile-height", String.valueOf(tileHeight),
            "--shard-height", String.valueOf(shardHeight),
            "--chunk-depth", String.valueOf(chunkDepth),
            "--shard-depth", String.valueOf(shardDepth),
            "--no-tiles");
        ZarrStore store = new ZarrStore(output.resolve("0").toString());
        Pixels pixels = new Pixels(null, null, sizeX, sizeY, 4, 1, 1, "", null);
        try (ZarrPixelBuffer zpb = createPixelBuffer(pixels, store, 1024, 1024)) {
            int[][] chunks = zpb.getChunks();
            assertEquals(chunks.length, resolutions);
            for (int resolution = 0; resolution < resolutions; resolution++) {
                int scale = (int) Math.pow(2, resolution);
                int expectedWidth = Math.min(shardWidth, sizeX / scale);
                int expectedHeight = Math.min(shardHeight, sizeY / scale);
                int[] expectedChunks = new int[] {1, 1, shardDepth, expectedHeight, expectedWidth};
                assertArrayEquals(chunks[resolution], expectedChunks);

                zpb.setResolutionLevel(resolutions - resolution - 1);
                assertEquals(zpb.getTileSize().getWidth(), expectedWidth);
                assertEquals(zpb.getTileSize().getHeight(), expectedHeight);
            }
        }
    }


    static Stream<Arguments> getResolutions() {
        return Stream.of(
            Arguments.of(1, "0.4"),
            Arguments.of(1, "0.5"),
            Arguments.of(5, "0.4"),
            Arguments.of(5, "0.5")
        );
    }

    /** Test resolutions API. */
    @ParameterizedTest
    @MethodSource("getResolutions")
    public void testResolutions(int resolutions, String version)
            throws IOException, URISyntaxException, ZarrException {
        Path output = writeTestZarr(
            1, 3, 1, 1024, 2048, "uint8", version,
            "--resolutions", String.valueOf(resolutions),
            "--no-tiles");
        ZarrStore store = new ZarrStore(output.resolve("0").toString());
        Pixels pixels = new Pixels(null, null, 2048, 1024, 3, 2, 1, "", null);
        try (ZarrPixelBuffer zpb = createPixelBuffer(pixels, store, 1024, 1024)) {
            List<List<Integer>> expected = new ArrayList<List<Integer>>();
            for (int resolution = 0; resolution < resolutions; resolution++) {
                int scale = (int) Math.pow(2, resolution);
                expected.add(Arrays.asList(new Integer[] {2048 / scale, 1024 / scale}));
            }
            assertEquals(expected, zpb.getResolutionDescriptions());

            List<Map<String, String>> datasets = zpb.getDatasets();
            assertEquals(datasets.size(), resolutions);
            assertEquals(zpb.getResolutionLevels(), resolutions);
            List<Map<String, Object>> transformations  = new ArrayList<Map<String, Object>>();;
            for (int resolution = 0; resolution < resolutions; resolution++) {
                transformations = new ArrayList<Map<String, Object>>();
                Map<String, Object> transformation = new HashMap<String, Object>();
                double scale = Math.pow(2, resolution);
                transformation.put("type", "scale");
                transformation.put("scale", Arrays.asList(
                    new Double[] {1.0, 1.0, 1.0, scale, scale}));
                transformations.add(transformation);
                assertEquals(
                    datasets.get(resolution).get("coordinateTransformations"), transformations);

                zpb.setResolutionLevel(resolutions - resolution - 1);
                assertEquals(zpb.getSizeT(), 1);
                assertEquals(zpb.getSizeC(), 3);
                assertEquals(zpb.getSizeZ(), 1);
                assertEquals(zpb.getSizeY(), 1024 / (int) scale);
                assertEquals(zpb.getSizeX(), 2048 / (int) scale);
            }

            assertThrows(IllegalArgumentException.class, () -> {
                zpb.setResolutionLevel(resolutions);
            });
        }
    }

    /** Test checkBounds. */
    @ParameterizedTest
    @ValueSource(strings = {"0.4", "0.5"})
    public void testCheckBounds(String version)
            throws IOException, URISyntaxException, ZarrException {
        Path output = writeTestZarr(1, 2, 3, 512, 2048, "uint8", "0.5", "--no-tiles");
        ZarrStore store = new ZarrStore(output.resolve("0").toString());
        Pixels pixels = new Pixels(null, null, 2048, 512, 3, 2, 1, "", null);
        try (ZarrPixelBuffer zpb = createPixelBuffer(pixels, store, 1024, 1024)) {
            zpb.checkBounds(0, 0, 0, 0, 0);
            zpb.checkBounds(2047, 511, 2, 1, 0);
            assertThrows(DimensionsOutOfBoundsException.class, () -> {
                zpb.checkBounds(-1, 0, 0, 0, 0);
            });
            assertThrows(DimensionsOutOfBoundsException.class, () -> {
                zpb.checkBounds(2048, 0, 0, 0, 0);
            });
            assertThrows(DimensionsOutOfBoundsException.class, () -> {
                zpb.checkBounds(0, -1, 0, 0, 0);
            });
            assertThrows(DimensionsOutOfBoundsException.class, () -> {
                zpb.checkBounds(0, 512, 0, 0, 0);
            });
            assertThrows(DimensionsOutOfBoundsException.class, () -> {
                zpb.checkBounds(0, 0, -1, 0, 0);
            });
            assertThrows(DimensionsOutOfBoundsException.class, () -> {
                zpb.checkBounds(0, 0, 3, 0, 0);
            });
            assertThrows(DimensionsOutOfBoundsException.class, () -> {
                zpb.checkBounds(0, 0, 0, -1, 0);
            });
            assertThrows(DimensionsOutOfBoundsException.class, () -> {
                zpb.checkBounds(0, 0, -1, 2, 0);
            });
            assertThrows(DimensionsOutOfBoundsException.class, () -> {
                zpb.checkBounds(0, 0, 0, 0, -1);
            });
            assertThrows(DimensionsOutOfBoundsException.class, () -> {
                zpb.checkBounds(0, 0, 0, 0, 1);
            });
        }
    }

    /** Test getTile with dimensions exceeding the image size. */
    @ParameterizedTest
    @ValueSource(strings = {"0.4", "0.5"})
    public void testGetTileLargerThanImage(String version)
            throws IOException, URISyntaxException, ZarrException {
        Path output = writeTestZarr(1, 1, 1, 10, 10, "uint8", version, "--no-tiles");
        ZarrStore store = new ZarrStore(output.resolve("0").toString());
        Pixels pixels = new Pixels(null, null, 10, 10, 1, 1, 1, "", null);
        DimensionsOutOfBoundsException exception;
        try (ZarrPixelBuffer zpb = createPixelBuffer(pixels, store, 1024, 1024)) {
            zpb.getTile(0, 0, 0, 0, 0, 10, 10);
            exception = assertThrows(DimensionsOutOfBoundsException.class, () -> {
                zpb.getTile(0, 0, 0, 0, 0, 11, 10);
            });
            assertEquals(exception.getMessage(), "X '10' greater than sizeX '10' or < '0'.");
            exception = assertThrows(DimensionsOutOfBoundsException.class, () -> {
                zpb.getTile(0, 0, 0, -1, 0, 2, 2);
            });
            assertEquals(exception.getMessage(), "X '-1' greater than sizeX '10' or < '0'.");
            exception = assertThrows(DimensionsOutOfBoundsException.class, () -> {
                zpb.getTile(0, 0, 0, 0, 0, 10, 11);
            });
            assertEquals(exception.getMessage(), "Y '10' greater than sizeY '10' or < '0'.");
            exception = assertThrows(DimensionsOutOfBoundsException.class, () -> {
                zpb.getTile(0, 0, 0, 0, -1, 2, 2);
            });
            assertEquals(exception.getMessage(), "Y '-1' greater than sizeY '10' or < '0'.");
        }
    }

    /** Test getTile with dimensions exceeding the buffer constructor limits. */
    @ParameterizedTest
    @ValueSource(strings = {"0.4", "0.5"})
    public void testGetTileExceedsMinMax(String version)
            throws IOException, URISyntaxException, ZarrException {
        Path output = writeTestZarr(1, 1, 1, 10, 10, "uint8", version, "--no-tiles");
        ZarrStore store = new ZarrStore(output.resolve("0").toString());
        Pixels pixels = new Pixels(null, null, 10, 10, 1, 1, 1, "", null);
        IllegalArgumentException exception;
        try (ZarrPixelBuffer zpb = createPixelBuffer(pixels, store, 5, 5)) {
            zpb.getTile(0, 0, 0, 0, 0, 5, 5);
            exception = assertThrows(IllegalArgumentException.class, () -> {
                zpb.getTile(0, 0, 0, 0, 0, 6, 5);
            });
            assertEquals(exception.getMessage(), "Requested shape [6, 5] > max plane size 5 * 5");
            exception = assertThrows(IllegalArgumentException.class, () -> {
                zpb.getTile(0, 0, 0, 0, 0, 5, 6);
            });
            assertEquals(exception.getMessage(), "Requested shape [5, 6] > max plane size 5 * 5");
        }
    }

    /** Test getTile with dimensions exceeding the buffer constructor limits. */
    @ParameterizedTest
    @ValueSource(strings = {"0.4", "0.5"})
    public void testGetTileIntegerOverflow(String version)
            throws IOException, URISyntaxException, ZarrException {
        Path output = writeTestZarr(1, 1, 1, 50000, 50000, "uint16", version, "--no-tiles");
        ZarrStore store = new ZarrStore(output.resolve("0").toString());
        Pixels pixels = new Pixels(null, null, 50000, 50000, 1, 1, 1, "", null);
        IllegalArgumentException exception;
        try (ZarrPixelBuffer zpb = createPixelBuffer(pixels, store, 1024, 1024)) {
            exception = assertThrows(IllegalArgumentException.class, () -> {
                zpb.getTile(0, 0, 0, 0, 0, 50000, 50000);
            });
            assertEquals(exception.getMessage(),
                "Requested shape [50000, 50000] > max plane size 1024 * 1024");
        }
    }
}
