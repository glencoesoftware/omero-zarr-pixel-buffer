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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.glencoesoftware.bioformats2raw.Converter;
import com.glencoesoftware.bioformats2raw.SupportedVersions;
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
import loci.formats.in.FakeReader;
import ome.model.core.Pixels;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import picocli.CommandLine;

/** Unit tests for ZarrPixelBuffer. */
public class ZarrPixelBufferTest {

    static final int SIZE_C = 2;
    static final int SIZE_Z = 3;
    static final int SIZE_T = 4;
    static final int SIZE_X = 256;
    static final int SIZE_Y = 128;
    static final String PIXEL_TYPE = "uint8";
    Path tmpDir;

    /** Set-up.*/
    @BeforeEach
    public void setup(@TempDir Path dir) throws Exception {
        tmpDir = dir;
    }

    static Stream<Arguments> getNGFFVersions() {
        return Stream.of(
            Arguments.of("0.4"),
            Arguments.of("0.5")
        );
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
        List<String> args = new ArrayList<String>(
                Arrays.asList(new String[] { "--compression", "null" }));
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

    /**
     * Checks that the pixels in the zarr array match the expected special pixels.
     */
    @ParameterizedTest
    @MethodSource("getNGFFVersions")
    public void testSpecialPixels(String version) throws Exception {
        Path output = writeTestZarr(
            SIZE_T, SIZE_C, SIZE_Z, SIZE_Y, SIZE_X, PIXEL_TYPE, version);
        ZarrStore store = new ZarrStore(output.resolve("0").toString());
        Pixels pixels = new Pixels(null, null, SIZE_X, SIZE_Y, SIZE_Z, SIZE_C, SIZE_T, "", null);
        try (ZarrPixelBuffer zpb = createPixelBuffer(pixels, store, SIZE_X, SIZE_Y)) {
            assertEquals(SIZE_T, zpb.getSizeT());
            assertEquals(SIZE_C, zpb.getSizeC());
            assertEquals(SIZE_Z, zpb.getSizeZ());
            assertEquals(SIZE_Y, zpb.getSizeY());
            assertEquals(SIZE_X, zpb.getSizeX());

            int plane = 0;
            for (int t = 0; t < SIZE_T; t++) {
                for (int c = 0; c < SIZE_C; c++) {
                    for (int z = 0; z < SIZE_Z; z++) {
                        System.out.println("Testing plane " + plane + " (z=" + z + ", "
                            + "c=" + c + ", t=" + t + ")");
                        byte[] tileData = zpb.getTile(z, c, t, 0, 0, SIZE_X, SIZE_Y)
                            .getData().array();
                        int[] specialPixels = FakeReader.readSpecialPixels(tileData);
                        assertArrayEquals(new int[] { 0, plane, z, c, t }, specialPixels);
                        plane++;
                    }
                }
            }
        }
    }
}
