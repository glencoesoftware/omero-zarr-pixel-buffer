/*
 * Copyright (C) 2025 University of Dundee & Open Microscopy Environment.
 * All rights reserved.
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

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

import com.bc.zarr.ArrayParams;
import com.bc.zarr.DataType;
import com.bc.zarr.DimensionSeparator;
import com.bc.zarr.ZarrArray;
import com.bc.zarr.ZarrGroup;

import ucar.ma2.InvalidRangeException;

/**
 * TestZarr is a utility class for creating and populating a Zarr with different number and order of dimensions.
 */
public class TestZarr {

    private int sizeX = 512;
    private int sizeY = 256;
    private int sizeZ = 5;
    private int sizeT = 10;
    private int sizeC = 3;
    private String order = "TCZYX";
    private Path path = Path.of("./test.zarr");
    private DataType dataType = DataType.u1;
    private boolean overwrite = false;
    
    // Text which is displayed on each plane
    private String text = "Channel <C>, Timepoint <T>, Z-plane <Z>";
    // If not specified then the position will be random
    private OptionalInt textX = OptionalInt.empty();
    private OptionalInt textY = OptionalInt.empty();

    private ZarrArray array;
    
    /**
     * Create a new TestZarr object with default values.
     * @throws IOException
     */
    public TestZarr() throws IOException {
    }

    /**
     * Create a new TestZarr object with custom values.
     * @param sizeX
     * @param sizeY
     * @param sizeZ
     * @param sizeT
     * @param sizeC
     * @param order
     * @param path
     * @param dataType
     * @param overwrite
     * @throws IOException
     */
    public TestZarr(int sizeX, int sizeY, int sizeZ, int sizeT, int sizeC, 
                    String order, String path, DataType dataType, boolean overwrite) throws IOException {
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.sizeT = sizeT;
        this.sizeC = sizeC;
        this.order = order;
        this.path = Path.of(path);
        this.dataType = dataType;
        this.overwrite = overwrite;
    }

    /**
     * Initialize the Zarr array.
     * @return
     * @throws IOException
     */
    public TestZarr init() throws IOException {
        if (order == null || order.isEmpty()) {
            throw new IllegalArgumentException("Order must specified.");
        }
        this.order = this.order.toUpperCase();
        if (!order.contains("X") || !order.contains("Y") ) {
            throw new IllegalArgumentException("Order must contain X and Y");
        }
        if (!order.contains("C")) {
            this.sizeC = 0;
        }
        if (!order.contains("Z")) {
            this.sizeZ = 0;
        }
        if (!order.contains("T")) {
            this.sizeT = 0;
        }
        if (sizeC == 0) {
            this.order = order.replace("C", "");
        }
        if (sizeZ == 0) {
            this.order = order.replace("Z", "");
        }
        if (sizeT == 0) {
            this.order = order.replace("T", "");
        }
       int[] shape = new int[order.length()];
        if (order.contains("C"))    
            shape[order.indexOf("C")] = sizeC;
        if (order.contains("T"))
            shape[order.indexOf("T")] = sizeT;
        if (order.contains("Z"))
            shape[order.indexOf("Z")] = sizeZ;
        shape[order.indexOf("Y")] = sizeY;
        shape[order.indexOf("X")] = sizeX;

        if (Files.exists(path)) {
            if (overwrite) {
                Files.walk(path)
      		    .sorted(Comparator.reverseOrder())
      		    .map(Path::toFile)
      		    .forEach(File::delete);
            } else {
                throw new IOException("Path already exists");
            }
        }

        Path series_path = path.resolve("0"); // image 0 (one image)
        Path img_path = series_path.resolve("0"); // resolution 0 (one resolution)
        
        // Create parent directories if they don't exist
        Files.createDirectories(img_path.getParent());
        
        array = ZarrArray.create(img_path, new ArrayParams()
        .shape(shape)
        .dataType(dataType)
        .dimensionSeparator(DimensionSeparator.SLASH)
        );
        return this;
    }

    /**
     * Fill the Zarr array with pixel data
     * @return
     * @throws IOException
     * @throws InvalidRangeException
     */
    public TestZarr createImage() throws IOException, InvalidRangeException {
        for (int i = 0; i <= sizeC; i++) {
            if (i == sizeC && sizeC > 0)
                break;
            for (int j = 0; j <= sizeT; j++) {
                if (j == sizeT && sizeT > 0)
                    break;
                for (int k = 0; k <= sizeZ; k++) {
                    if (j == sizeZ && sizeZ > 0)
                        break;
                    String txt = text.replace("<C>", String.valueOf(i)).replace("<T>", String.valueOf(j)).replace("<Z>", String.valueOf(k));
                    byte[] plane = Utils.generateGreyscaleImageWithText(sizeX, sizeY, txt, textX, textY);
                    int[] sh = new int[order.length()];
                    int[] off = new int[order.length()];
                    if (order.contains("C"))
                        sh[order.indexOf("C")] = 1;
                    if (order.contains("T"))
                        sh[order.indexOf("T")] = 1;
                    if (order.contains("Z"))
                        sh[order.indexOf("Z")] = 1;
                    sh[order.indexOf("Y")] = sizeY;
                    sh[order.indexOf("X")] = sizeX;
                    if (order.contains("C"))
                        off[order.indexOf("C")] = i;
                    if (order.contains("T"))
                        off[order.indexOf("T")] = j;
                    if (order.contains("Z"))
                        off[order.indexOf("Z")] = k;
                    off[order.indexOf("Y")] = 0;
                    off[order.indexOf("X")] = 0;
                    array.write(plane, sh, off);
                }
            }
        }
        return this;
    }

    /**
     * Save the image metadata to the Zarr file.
     * @return
     * @throws IOException
     */
    public TestZarr createMetadata() throws IOException {
        List<Map<String, String>> axes = new ArrayList<>();
        for (int i = 0; i < order.length(); i++) {
            Map<String, String> axisObj = new HashMap<>();
            String axe = Character.toString(order.charAt(i)).toLowerCase();
            axisObj.put("name", axe);
            if (axe.equals("c")) {
                axisObj.put("type", "channel");
            } else if (axe.equals("t")) {
                axisObj.put("type", "time");
            } else if (axe.equals("z")) {
                axisObj.put("type", "space");
            } else if (axe.equals("y")) {
                axisObj.put("type", "space");
            } else if (axe.equals("x")) {
                axisObj.put("type", "space");
            }
            axes.add(axisObj);
        }

        List<Map<String, Object>> datasets = new ArrayList<>();
        Map<String, Object> resObj = new HashMap<>();
        resObj.put("path", "0");
        List<Map<String, Object>> transforms = new ArrayList<>();
        Map<String, Object> trans = new HashMap<>();
        trans.put("type", "scale");
        trans.put("scale", new int[] {1, 1, 1, 1, 1});
        transforms.add(trans);
        resObj.put("coordinateTransformations", transforms);
        datasets.add(resObj);

        List<Object> msArray = new ArrayList<>();
        Map<String, Object> msData = new HashMap<>();
        msData.put("axes", axes);
        msData.put("datasets", datasets);
        msArray.add(msData);
        Path series_path = path.resolve("0"); // image 0 (one image)
        
        // Create the series directory if it doesn't exist
        Files.createDirectories(series_path);
        
        // Create a new ZarrGroup instead of trying to open an existing one
        ZarrGroup z = ZarrGroup.create(series_path);
        Map<String,Object> attrs = new HashMap<String, Object>();
        attrs.put("multiscales", msArray);
        z.writeAttributes(attrs);

        return this;
    }

    public int getSizeX() {
        return sizeX;
    }

    public TestZarr setSizeX(int sizeX) {
        this.sizeX = sizeX;
        return this;
    }

    public int getSizeY() {
        return sizeY;
    }

    public TestZarr setSizeY(int sizeY) {
        this.sizeY = sizeY;
        return this;
    }

    public int getSizeZ() {
        return sizeZ;
    }

    public TestZarr setSizeZ(int sizeZ) {
        this.sizeZ = sizeZ;
        return this;
    }

    public int getSizeT() {
        return sizeT;
    }

    public TestZarr setSizeT(int sizeT) {
        this.sizeT = sizeT;
        return this;
    }

    public int getSizeC() {
        return sizeC;
    }

    public TestZarr setSizeC(int sizeC) {
        this.sizeC = sizeC;
        return this;
    }

    public String getOrder() {
        return order;
    }

    public TestZarr setOrder(String order) {
        this.order = order;
        return this;
    }

    public Path getPath() {
        return path;
    }

    public TestZarr setPath(Path path) {
        this.path = path;
        return this;
    }

    public DataType getDataType() {
        return dataType;
    }

    public TestZarr setDataType(DataType dataType) {
        this.dataType = dataType;
        return this;
    }

    public ZarrArray getArray() {
        return array;
    }

    public TestZarr setOverwrite(boolean b) {
        this.overwrite = b;
        return this;
    }

    public boolean getOverwrite() {
        return overwrite;
    }

    public String getText() {
        return text;
    }

    public TestZarr setText(String text) {
        this.text = text;
        return this;
    }

    public TestZarr setTextX(int textX) {
        this.textX = OptionalInt.of(textX);
        return this;
    }

    public TestZarr setTextY(int textY) {
        this.textY = OptionalInt.of(textY);
        return this;
    }
}
