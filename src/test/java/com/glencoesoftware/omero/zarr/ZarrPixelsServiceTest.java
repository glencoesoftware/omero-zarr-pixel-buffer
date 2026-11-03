/*
 * Copyright (C) 2023 Glencoe Software, Inc. All rights reserved.
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

import static omero.rtypes.rlong;
import static omero.rtypes.rstring;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.File;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import omero.ApiUsageException;
import omero.model.ExternalInfo;
import omero.model.ExternalInfoI;
import omero.model.IObject;
import omero.model.Image;
import omero.model.ImageI;
import omero.model.Mask;
import omero.model.MaskI;
import omero.util.IceMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit test for ZarrPixelsService. */
public class ZarrPixelsServiceTest {

    private ZarrPixelsService pixelsService;
    private String uuid = UUID.randomUUID().toString();
    private String imageUri = "/data/ngff/image.zarr";
    private String labelUri = imageUri + "/0/labels/" + uuid;
    private String urlUnsafeBucketAndKey =
            "data/ngff dir/image&()*?%20test.zarr?anonymous=true";
    private Image image;
    private Mask mask;
    private ome.model.IObject object;
    private static final String ENTITY_TYPE = "com.glencoesoftware.ngff:multiscales";
    private static final long ENTITY_ID = 3;

    private String quoteFullPath(String path) {
        String[] segments = path.split("/");
        List<String> quotedSegments = new ArrayList<String>();
        for (String s : segments) {
            if (!s.isEmpty()) {
                // URLEncoder.encode will % encode all the characters which cause
                // a URISyntaxException if present in the URI constructor except for " " which is
                // encoded as "+". Because "+" IS % encoded, we can safely assume that any
                // remaining "+" characters were formerly spaces and replace them. Space is the
                // ONLY character which is changed but not % encoded. See
                // https://docs.oracle.com/javase%2F7%2Fdocs%2Fapi%2F%2F/java/net/URLEncoder.html
                quotedSegments.add(URLEncoder.encode(s, StandardCharsets.UTF_8)
                        .replaceAll("\\+", "%20"));
            }
        }
        return String.join("/", quotedSegments);
    }

    /** Initializes a ZarrPixelsService with temporary directories. */
    @BeforeEach
    public void setUp() throws IOException {
        File pixelsDir = Files.createTempDirectory("pixels").toFile();
        pixelsDir.deleteOnExit();
        File memoDir = Files.createTempDirectory("memoizer").toFile();
        memoDir.deleteOnExit();
        try {
            pixelsService = new ZarrPixelsService(
                pixelsDir.getAbsolutePath(), false, memoDir, 0L, null, null, null, null, 0, 0, 0);
        } catch (Exception e) {
            throw new IOException(e);
        }
        mask = new MaskI();
        image = new ImageI();
    }

    private void addExternalInfo(
            IObject object, Long entityId, String entityType, String lsid, String uuid) {
        ExternalInfo externalInfo = new ExternalInfoI();
        externalInfo.setEntityId(rlong(entityId));
        externalInfo.setEntityType(rstring(entityType));
        if (lsid != null) {
            externalInfo.setLsid(rstring(lsid));
        }
        if (uuid != null) {
            externalInfo.setUuid(rstring(uuid));
        }
        object.getDetails().setExternalInfo(externalInfo);
    }

    @Test
    public void testDefault() throws ApiUsageException, IOException {
        addExternalInfo(mask, ENTITY_ID, ENTITY_TYPE, labelUri, uuid);
        object = (ome.model.roi.Mask) new IceMapper().reverse(mask);
        assertEquals(pixelsService.getUri(object), labelUri);
        assertEquals(pixelsService.getUri(object, ENTITY_TYPE, ENTITY_ID), labelUri);

        addExternalInfo(image, ENTITY_ID, ENTITY_TYPE, imageUri, uuid);
        object = (ome.model.core.Image) new IceMapper().reverse(image);
        assertEquals(pixelsService.getUri(object), imageUri);
        assertEquals(pixelsService.getUri(object, ENTITY_TYPE, ENTITY_ID), imageUri);
    }

    @Test
    public void testEncoded() throws ApiUsageException, IOException {
        String protocolAndHost = "s3://s3.us-east-1.amazonaws.com/";
        String encodedUnsafeUri = quoteFullPath(urlUnsafeBucketAndKey);
        addExternalInfo(image, ENTITY_ID, ENTITY_TYPE, protocolAndHost + encodedUnsafeUri, uuid);
        object = (ome.model.core.Image) new IceMapper().reverse(image);
        assertEquals(pixelsService.getUri(object), protocolAndHost + urlUnsafeBucketAndKey);
        assertEquals(pixelsService.getUri(object, ENTITY_TYPE, ENTITY_ID),
                protocolAndHost + urlUnsafeBucketAndKey);
    }

    @Test
    public void testGetUriNoExternalInfo() throws ApiUsageException, IOException {
        object = (ome.model.roi.Mask) new IceMapper().reverse(mask);
        assertNull(pixelsService.getUri(object));
        assertNull(pixelsService.getUri(object, ENTITY_TYPE, ENTITY_ID));

        object = (ome.model.core.Image) new IceMapper().reverse(image);
        assertNull(pixelsService.getUri(object));
        assertNull(pixelsService.getUri(object, ENTITY_TYPE, ENTITY_ID));
    }

    @Test
    public void testGetUriNoUuid() throws ApiUsageException, IOException {
        addExternalInfo(mask, ENTITY_ID, ENTITY_TYPE, labelUri, null);
        object = (ome.model.roi.Mask) new IceMapper().reverse(mask);
        assertEquals(pixelsService.getUri(object), labelUri);
        assertEquals(pixelsService.getUri(object, ENTITY_TYPE, ENTITY_ID), labelUri);

        addExternalInfo(image, ENTITY_ID, ENTITY_TYPE, imageUri, null);
        object = (ome.model.core.Image) new IceMapper().reverse(image);
        assertEquals(pixelsService.getUri(object), imageUri);
        assertEquals(pixelsService.getUri(object, ENTITY_TYPE, ENTITY_ID), imageUri);
    }

    @Test
    public void testGetUriNoLsid() throws ApiUsageException, IOException {
        addExternalInfo(mask, ENTITY_ID, ENTITY_TYPE, null, uuid);
        object = (ome.model.roi.Mask) new IceMapper().reverse(mask);
        assertNull(pixelsService.getUri(object));
        assertNull(pixelsService.getUri(object, ENTITY_TYPE, ENTITY_ID));

        addExternalInfo(image, ENTITY_ID, ENTITY_TYPE, null, uuid);
        object = (ome.model.core.Image) new IceMapper().reverse(image);
        assertNull(pixelsService.getUri(object));
        assertNull(pixelsService.getUri(object, ENTITY_TYPE, ENTITY_ID));
    }

    @Test
    public void testGetUriWrongEntityType() throws ApiUsageException, IOException {
        addExternalInfo(mask, ENTITY_ID, "multiscales", labelUri, uuid);
        object = (ome.model.roi.Mask) new IceMapper().reverse(mask);
        assertNull(pixelsService.getUri(object));
        assertNull(pixelsService.getUri(object, ENTITY_TYPE, ENTITY_ID));
        assertEquals(pixelsService.getUri(object, "multiscales", ENTITY_ID), labelUri);

        addExternalInfo(image, ENTITY_ID, "multiscales", imageUri, uuid);
        object = (ome.model.core.Image) new IceMapper().reverse(image);
        assertNull(pixelsService.getUri(object));
        assertNull(pixelsService.getUri(object, ENTITY_TYPE, ENTITY_ID));
        assertEquals(pixelsService.getUri(object, "multiscales", ENTITY_ID), imageUri);
    }

    @Test
    public void testGetUriWrongEntityId() throws ApiUsageException, IOException {
        addExternalInfo(mask, 1L, ENTITY_TYPE, labelUri, uuid);
        object = (ome.model.roi.Mask) new IceMapper().reverse(mask);
        assertNull(pixelsService.getUri(object));
        assertNull(pixelsService.getUri(object, ENTITY_TYPE, ENTITY_ID));
        assertEquals(pixelsService.getUri(object, ENTITY_TYPE, 1L), labelUri);

        addExternalInfo(image, 1L, ENTITY_TYPE, imageUri, uuid);
        object = (ome.model.core.Image) new IceMapper().reverse(image);
        assertNull(pixelsService.getUri(object));
        assertNull(pixelsService.getUri(object, ENTITY_TYPE, ENTITY_ID));
        assertEquals(pixelsService.getUri(object, ENTITY_TYPE, 1L), imageUri);
    }
}
