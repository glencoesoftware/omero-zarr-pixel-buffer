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

import com.github.benmanes.caffeine.cache.AsyncLoadingCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.zarr.zarrjava.ZarrException;
import dev.zarr.zarrjava.core.Array;
import dev.zarr.zarrjava.core.Group;
import dev.zarr.zarrjava.store.StoreHandle;
import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import ome.api.IQuery;
import ome.conditions.LockTimeout;
import ome.io.nio.BackOff;
import ome.io.nio.FilePathResolver;
import ome.io.nio.PixelBuffer;
import ome.io.nio.TileSizes;
import ome.model.IObject;
import ome.model.core.Image;
import ome.model.core.Pixels;
import ome.model.meta.ExternalInfo;
import ome.model.roi.Mask;
import org.perf4j.StopWatch;
import org.perf4j.slf4j.Slf4JStopWatch;
import org.slf4j.LoggerFactory;

/**
 * Subclass which overrides series retrieval to avoid the need for an injected {@link IQuery}.
 *
 * @author Chris Allan
 *
 */
public class ZarrPixelsService extends ome.io.nio.PixelsService {

    private static final org.slf4j.Logger log = LoggerFactory.getLogger(ZarrPixelsService.class);

    public static final String NGFF_ENTITY_TYPE = "com.glencoesoftware.ngff:multiscales";
    public static final long NGFF_ENTITY_ID = 3;
    public static final long NGFF_ENTITY_ID_ZARR_V3 = 4;

    /** Max Plane Width. */
    protected final Integer maxPlaneWidth;

    /** Max Plane Height. */
    protected final Integer maxPlaneHeight;

    /** Zarr metadata and array cache size. */
    private final long zarrCacheSize;

    /** Copy of private IQuery also provided to ome.io.nio.PixelsService. */
    private final IQuery iQuery;

    /** Root path vs. Group cache. */
    private final AsyncLoadingCache<ZarrPath, Group> zarrGroupCache;

    /** Array path vs. Array cache */
    private final AsyncLoadingCache<ZarrPath, Array> zarrArrayCache;

    /**
     * Default constructor.
     *
     * @throws URISyntaxException       If something isn't right.
     * @throws IllegalArgumentException If something isn't right.
     */
    public ZarrPixelsService(String path, boolean isReadOnlyRepo, File memoizerDirectory,
        long memoizerWait, FilePathResolver resolver, BackOff backOff, TileSizes sizes,
        IQuery iQuery, long zarrCacheSize, int maxPlaneWidth, int maxPlaneHeight)
        throws IllegalArgumentException, URISyntaxException {
        super(path, isReadOnlyRepo, memoizerDirectory, memoizerWait, resolver, backOff, sizes,
            iQuery);
        this.zarrCacheSize = zarrCacheSize;
        log.info("Zarr metadata and array cache size: {}", zarrCacheSize);
        this.maxPlaneWidth = maxPlaneWidth;
        this.maxPlaneHeight = maxPlaneHeight;
        this.iQuery = iQuery;
        zarrGroupCache = Caffeine.newBuilder().maximumSize(this.zarrCacheSize)
            .buildAsync(ZarrPixelsService::getZarrGroup);
        zarrArrayCache = Caffeine.newBuilder().maximumSize(this.zarrCacheSize)
            .buildAsync(ZarrPixelsService::getZarrArray);
    }

    /**
     * Opens a Zarr group at a given path.
     * To minimize the number of calls especially for Zarr groups stored
     * remotely, this first tries to open a group compliant with version 3
     * of the Zarr specification and falls back to version 2 of the Zarr
     * specification.
     *
     * @param zp          Location of a multiscale Zarr group
     * @return            Group including the metadata
     */
    public static dev.zarr.zarrjava.core.Group getZarrGroup(ZarrPath zp)
        throws IOException, IllegalArgumentException, URISyntaxException {
        try {
            return dev.zarr.zarrjava.v3.Group.open(zp.store.store);
        } catch (IOException e) {
            return dev.zarr.zarrjava.v2.Group.open(zp.store.store);
        }
    }

    /**
     * Opens a Zarr array at a given path.
     *
     * @param zp path to open a Zarr array from
     * @return See above.
     * @throws URISyntaxException       If something isn't right.
     * @throws IllegalArgumentException If something isn't right.
     */
    public static Array getZarrArray(ZarrPath zp)
        throws IOException, IllegalArgumentException, URISyntaxException, ZarrException {
        StoreHandle handle = zp.store.store.resolve(zp.path.split("/"));
        try {
            return dev.zarr.zarrjava.v3.Array.open(handle);
        } catch (IOException e) {
            return dev.zarr.zarrjava.v2.Array.open(handle);
        }
    }

    /**
     * Retrieve {@link Mask} or {@link Image} URI stored under {@link ExternalInfo}.
     *
     * @param object loaded {@link Mask} or {@link Image} to check for a URI
     * @return the value of {@link ExternalInfo.lsid}, <code>null</code> if the object does not have
     *         an {@link ExternalInfo} with a valid {@link ExternalInfo.lsid} atttribute or if
     *         {@link ExternalInfo.entityType} is not equal to {@link NGFF_ENTITY_TYPE} or if
     *         {@link ExternalInfo.entityId} is not equal to {@link NGFF_ENTITY_ID}
     *         or {@link NGFF_ENTITY_ID_ZARR_V3}.
     */
    public String getUri(IObject object) {
        String uri = getUri(object, NGFF_ENTITY_TYPE, NGFF_ENTITY_ID);
        if (uri == null) {
            uri = getUri(object, NGFF_ENTITY_TYPE, NGFF_ENTITY_ID_ZARR_V3);
        }
        return uri;
    }

    /**
     * Retrieve {@link Mask} or {@link Image} URI stored under {@link ExternalInfo}.
     *
     * @param object           loaded {@link Mask} or {@link Image} to check for a URI
     * @param targetEntityType expected entityType in the object {@link ExternalInfo}
     * @param targetEntityId   expected entityType in the object {@link ExternalInfo}
     * @return the value of {@link ExternalInfo.lsid}, <code>null</code> if the object does not have
     *         an {@link ExternalInfo} with a valid {@link ExternalInfo.lsid} atttribute or if
     *         {@link ExternalInfo.entityType} is not equal to <code>targetEntityType</code> or if
     *         {@link ExternalInfo.entityId} is not equal to <code>targetEntityId</code> .
     */
    public String getUri(IObject object, String targetEntityType, Long targetEntityId) {
        ExternalInfo externalInfo = object.getDetails().getExternalInfo();
        if (externalInfo == null) {
            log.debug("{}:{} missing ExternalInfo", object.getClass().getSimpleName(),
                object.getId());
            return null;
        }

        String entityType = externalInfo.getEntityType();
        if (entityType == null) {
            log.debug("{}:{} missing ExternalInfo entityType", object.getClass().getSimpleName(),
                object.getId());
            return null;
        }
        if (!entityType.equals(targetEntityType)) {
            log.debug("{}:{} unsupported ExternalInfo entityType {}",
                object.getClass().getSimpleName(), object.getId(), entityType);
            return null;
        }

        Long entityId = externalInfo.getEntityId();
        if (entityId == null) {
            log.debug("{}:{} missing ExternalInfo entityId", object.getClass().getSimpleName(),
                object.getId());
            return null;
        }
        if (!entityId.equals(targetEntityId)) {
            log.debug("{}:{} ExternalInfo entityId {} did not match target id {}",
                object.getClass().getSimpleName(), object.getId(), entityId,
                targetEntityId);
            return null;
        }

        String uri = externalInfo.getLsid();

        if (uri == null) {
            log.debug("{}:{} missing LSID", object.getClass().getSimpleName(), object.getId());
            return null;
        }

        if (targetEntityType == NGFF_ENTITY_TYPE
            && targetEntityId == NGFF_ENTITY_ID
            && uri.startsWith("s3://")) {
            String toDecode = uri.replace("s3://", "");
            // We need to URL decode the old-style NGFF LSIDs.
            uri = "s3://" + URLDecoder.decode(toDecode, StandardCharsets.UTF_8);
        }
        return uri;
    }

    /**
     * Retrieve the {@link Image} for a particular set of pixels. Where possible, does not initiate
     * a query.
     *
     * @param pixels Pixels set to retrieve the {@link Image} for.
     * @return See above.
     */
    protected Image getImage(Pixels pixels) {
        if (pixels.getImage().isLoaded()) {
            // Will likely only be true when operating within a microservice
            return pixels.getImage();
        }
        return iQuery.get(Image.class, pixels.getImage().getId());
    }

    /**
     * Retrieves the series for a given set of pixels. Where possible, does not initiate a query.
     *
     * @param pixels Set of pixels to return the series for.
     * @return The series as specified by the pixels parameters or <code>0</code> (the first
     *         series).
     */
    @Override
    protected int getSeries(Pixels pixels) {
        if (pixels.getImage().isLoaded()) {
            // Will likely only be true when operating within a microservice
            return pixels.getImage().getSeries();
        }
        return super.getSeries(pixels);
    }

    /**
     * Returns a label image NGFF pixel buffer if it exists.
     *
     * @param mask Mask to retrieve a pixel buffer for.
     * @return A pixel buffer instance.
     * @throws URISyntaxException       If something isn't right.
     * @throws IllegalArgumentException If something isn't right.
     * @throws ZarrException            If something isn't right.
     */
    public ZarrPixelBuffer getLabelImagePixelBuffer(Mask mask)
        throws IOException, IllegalArgumentException, URISyntaxException, ZarrException {
        Pixels pixels = new ome.model.core.Pixels();
        pixels.setSizeX(mask.getWidth().intValue());
        pixels.setSizeY(mask.getHeight().intValue());
        pixels.setSizeC(1);
        pixels.setSizeT(1);
        pixels.setSizeZ(1);
        String root = getUri(mask);
        if (root == null) {
            throw new IllegalArgumentException("No root for Mask:" + mask.getId());
        }
        ZarrStore store = new ZarrStore(root);
        log.info("Using ZarrStore: " + store);
        return new ZarrPixelBuffer(pixels, store, maxPlaneWidth, maxPlaneHeight, zarrGroupCache,
            zarrArrayCache);
    }

    /**
     * Creates an NGFF pixel buffer for a given set of pixels.
     *
     * @param pixels Pixels set to retrieve a pixel buffer for. <code>true</code> opens as
     *               read-write, <code>false</code> opens as read-only.
     * @return An NGFF pixel buffer instance or <code>null</code> if one cannot be found.
     */
    protected ZarrPixelBuffer createOmeNgffPixelBuffer(Pixels pixels) {
        StopWatch t0 = new Slf4JStopWatch("createOmeNgffPixelBuffer()");
        try {
            Image image = getImage(pixels);
            String uri = getUri(image);

            if (uri == null) {
                // Quick exit if we think we're OME-NGFF but there is no URI
                if (image.getFormat() != null && "OMEXML".equals(image.getFormat().getValue())) {
                    throw new LockTimeout("Import in progress.", 15 * 1000, 0);
                }
                log.debug("No OME-NGFF root");
                return null;
            }
            try {
                ZarrStore store = new ZarrStore(uri);
                log.info("Using ZarrStore: " + store);
                ZarrPixelBuffer v = new ZarrPixelBuffer(pixels, store, maxPlaneWidth,
                    maxPlaneHeight, zarrGroupCache, zarrArrayCache);
                log.info("Using OME-NGFF pixel buffer");
                return v;
            } catch (Exception e) {
                log.warn("Getting OME-NGFF pixel buffer failed - " + "attempting to get local data",
                    e);
            }
        } catch (Exception e1) {
            log.debug("Failed to find OME-NGFF metadata for Pixels:{}", pixels.getId());
        } finally {
            t0.stop();
        }
        return null;
    }

    /**
     * Returns a pixel buffer for a given set of pixels. Either an NGFF pixel buffer, a proprietary
     * ROMIO pixel buffer or a specific pixel buffer implementation.
     *
     * @param pixels Pixels set to retrieve a pixel buffer for.
     * @param write  Whether or not to open the pixel buffer as read-write. <code>true</code> opens
     *               as read-write, <code>false</code> opens as read-only.
     * @return A pixel buffer instance.
     */
    @Override
    public PixelBuffer getPixelBuffer(Pixels pixels, boolean write) {
        PixelBuffer pixelBuffer = createOmeNgffPixelBuffer(pixels);
        if (pixelBuffer != null) {
            return pixelBuffer;
        }
        return _getPixelBuffer(pixels, write);
    }

}
