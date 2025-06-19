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

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.perf4j.StopWatch;
import org.perf4j.slf4j.Slf4JStopWatch;
import org.slf4j.LoggerFactory;

import com.google.common.base.Splitter;
import com.upplication.s3fs.OmeroS3FilesystemProvider;
import com.bc.zarr.ZarrArray;
import com.bc.zarr.ZarrGroup;
import com.github.benmanes.caffeine.cache.AsyncLoadingCache;
import com.github.benmanes.caffeine.cache.Caffeine;

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

/**
 * Subclass which overrides series retrieval to avoid the need for
 * an injected {@link IQuery}.
 * @author Chris Allan <callan@glencoesoftware.com>
 *
 */
public class ZarrPixelsService extends ome.io.nio.PixelsService {

    private static final org.slf4j.Logger log =
            LoggerFactory.getLogger(ZarrPixelsService.class);

    public static final String NGFF_ENTITY_TYPE = "com.glencoesoftware.ngff:multiscales";
    public static final long NGFF_ENTITY_ID = 3;

    /** Max Plane Width */
    protected final Integer maxPlaneWidth;

    /** Max Plane Height */
    protected final Integer maxPlaneHeight;

    /** Zarr metadata and array cache size */
    private final long zarrCacheSize;

    /** Copy of private IQuery also provided to ome.io.nio.PixelsService */
    private final IQuery iQuery;

    /** Root path vs. metadata cache */
    private final
        AsyncLoadingCache<Path, Map<String, Object>> zarrMetadataCache;

    /** Array path vs. ZarrArray cache */
    private final AsyncLoadingCache<Path, ZarrArray> zarrArrayCache;

    public ZarrPixelsService(
            String path, boolean isReadOnlyRepo, File memoizerDirectory,
            long memoizerWait, FilePathResolver resolver, BackOff backOff,
            TileSizes sizes, IQuery iQuery,
            long zarrCacheSize,
            int maxPlaneWidth, int maxPlaneHeight) {
        super(
            path, isReadOnlyRepo, memoizerDirectory, memoizerWait, resolver,
            backOff, sizes, iQuery
        );
        this.zarrCacheSize = zarrCacheSize;
        log.info("Zarr metadata and array cache size: {}", zarrCacheSize);
        this.maxPlaneWidth = maxPlaneWidth;
        this.maxPlaneHeight = maxPlaneHeight;
        this.iQuery = iQuery;
        zarrMetadataCache = Caffeine.newBuilder()
                .maximumSize(this.zarrCacheSize)
                .buildAsync(ZarrPixelsService::getZarrMetadata);
        zarrArrayCache = Caffeine.newBuilder()
                .maximumSize(this.zarrCacheSize)
                .buildAsync(ZarrPixelsService::getZarrArray);
    }

    /**
     * Retrieves Zarr metadata from a given path.
     * @param root path to get Zarr metadata from
     * @return See above.
     * @throws IOException
     */
    public static Map<String, Object> getZarrMetadata(Path path)
            throws IOException {
        // FIXME: Really should be ZarrUtils.readAttributes() to allow for
        // attribute retrieval from either a ZarrArray or ZarrGroup but ZarrPath
        // is package private at the moment.
        return ZarrGroup.open(path).getAttributes();
    }

    /**
     * Opens a Zarr array at a given path.
     * @param root path to open a Zarr array from
     * @return See above.
     * @throws IOException
     */
    public static ZarrArray getZarrArray(Path path) throws IOException {
        return ZarrArray.open(path);
    }

    /**
     * Converts an NGFF root string to a path, initializing a {@link FileSystem}
     * if required
     * @param ngffDir NGFF directory root
     * @return Fully initialized path or <code>null</code> if the NGFF root
     * directory has not been specified in configuration.
     * @throws IOException
     */
    public static Path asPath(String ngffDir) throws IOException {
        if (ngffDir.isEmpty()) {
            return null;
        }

        try {
            URI uri = new URI(ngffDir);
            if ("s3".equals(uri.getScheme())) {
                if (uri.getUserInfo() != null && !uri.getUserInfo().isEmpty()) {
                    throw new RuntimeException(
                        "Found unsupported user information in S3 URI."
                        + " If you are trying to pass S3 credentials, "
                        + "use either named profiles or instance credentials.");
                }
                String query = Optional.ofNullable(uri.getQuery()).orElse("");
                Map<String, String> params = Splitter.on('&')
                        .trimResults()
                        .omitEmptyStrings()
                        .withKeyValueSeparator('=')
                        .split(query);
                // drop initial "/"
                String uriPath = uri.getPath().substring(1);
                int first = uriPath.indexOf("/");
                String bucket = "/" + uriPath.substring(0, first);
                String rest = uriPath.substring(first + 1);
                // FIXME: We might want to support additional S3FS settings in
                // the future.  See:
                //   * https://github.com/lasersonlab/Amazon-S3-FileSystem-NIO2
                Map<String, String> env = new HashMap<String, String>();
                String profile = params.get("profile");
                if (profile != null) {
                    env.put("s3fs_credential_profile_name", profile);
                }
                OmeroS3FilesystemProvider fsp = new OmeroS3FilesystemProvider();
                FileSystem fs = fsp.newFileSystem(uri, env);
                return fs.getPath(bucket, rest);
            }
        } catch (URISyntaxException e) {
            // Fall through
        }
        return Paths.get(ngffDir);
    }

    /**
     * Retrieve {@link Mask} or {@link Image} URI stored under {@link ExternalInfo}.
     * @param object loaded {@link Mask} or {@link Image} to check for a URI
     * @return the value of {@link ExternalInfo.lsid}, <code>null</code> if the object
     * does not have an {@link ExternalInfo} with a valid {@link ExternalInfo.lsid} atttribute
     * or if {@link ExternalInfo.entityType} is not equal to {@link NGFF_ENTITY_TYPE} or if
     * {@link ExternalInfo.entityId} is not equal to {@link NGFF_ENTITY_ID}.
     */
    public String getUri(IObject object) {
        return getUri(object, NGFF_ENTITY_TYPE, NGFF_ENTITY_ID);
    }

    /**
     * Retrieve {@link Mask} or {@link Image} URI stored under {@link ExternalInfo}
     * @param object loaded {@link Mask} or {@link Image} to check for a URI
     * @param targetEntityType expected entityType in the object {@link ExternalInfo}
     * @param targetEntityId expected entityType in the object {@link ExternalInfo}
     * @return the value of {@link ExternalInfo.lsid}, <code>null</code> if the object
     * does not have an {@link ExternalInfo} with a valid {@link ExternalInfo.lsid} atttribute
     * or if {@link ExternalInfo.entityType} is not equal to <code>targetEntityType</code> or if
     * {@link ExternalInfo.entityId} is not equal to <code>targetEntityId</code> .
     */
    public String getUri(IObject object, String targetEntityType, Long targetEntityId) {
        ExternalInfo externalInfo = object.getDetails().getExternalInfo();
        if (externalInfo == null) {
            log.debug(
                "{}:{} missing ExternalInfo",
                object.getClass().getSimpleName(), object.getId());
            return null;
        }

        String entityType = externalInfo.getEntityType();
        if (entityType == null) {
            log.debug(
                "{}:{} missing ExternalInfo entityType",
                object.getClass().getSimpleName(), object.getId());
            return null;
        }
        if (!entityType.equals(targetEntityType)) {
            log.debug(
                "{}:{} unsupported ExternalInfo entityType {}",
                object.getClass().getSimpleName(), object.getId(), entityType);
            return null;
        }

        Long entityId = externalInfo.getEntityId();
        if (entityType == null) {
            log.debug(
                "{}:{} missing ExternalInfo entityId",
                object.getClass().getSimpleName(), object.getId());
            return null;
        }
        if (!entityId.equals(targetEntityId)) {
            log.debug(
                "{}:{} unsupported ExternalInfo entityId {}",
                object.getClass().getSimpleName(), object.getId(), entityId);
            return null;
        }

        String uri = externalInfo.getLsid();
        if (uri == null) {
            log.debug(
                "{}:{} missing LSID",
                object.getClass().getSimpleName(), object.getId());
            return null;
        }
        return uri;
    }

    /**
     * Retrieve the {@link Image} for a particular set of pixels.  Where
     * possible, does not initiate a query.
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
     * Retrieves the series for a given set of pixels.  Where possible, does not
     * initiate a query.
     * @param pixels Set of pixels to return the series for.
     * @return The series as specified by the pixels parameters or
     * <code>0</code> (the first series).
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
     * @param mask Mask to retrieve a pixel buffer for.
     * @return A pixel buffer instance.
     * @throws IOException
     */
    public ZarrPixelBuffer getLabelImagePixelBuffer(Mask mask)
            throws IOException {
        Pixels pixels = new ome.model.core.Pixels();
        pixels.setSizeX(mask.getWidth().intValue());
        pixels.setSizeY(mask.getHeight().intValue());
        pixels.setSizeC(1);
        pixels.setSizeT(1);
        pixels.setSizeZ(1);
        String root = getUri(mask);
        if (root == null) {
            throw new IllegalArgumentException(
                    "No root for Mask:" + mask.getId());
        }
        return new ZarrPixelBuffer(
                pixels, asPath(root), maxPlaneWidth, maxPlaneHeight,
                zarrMetadataCache, zarrArrayCache);
    }

    /**
     * Creates an NGFF pixel buffer for a given set of pixels.
     * @param pixels Pixels set to retrieve a pixel buffer for.
     * <code>true</code> opens as read-write, <code>false</code> opens as
     * read-only.
     * @return An NGFF pixel buffer instance or <code>null</code> if one cannot
     * be found.
     */
    protected ZarrPixelBuffer createOmeNgffPixelBuffer(Pixels pixels) {
        StopWatch t0 = new Slf4JStopWatch("createOmeNgffPixelBuffer()");
        try {
            Image image = getImage(pixels);
            String uri = getUri(image);
            if (uri == null) {
                // Quick exit if we think we're OME-NGFF but there is no URI
                if (image.getFormat() != null && "OMEXML".equals(image.getFormat().getValue())) {
                    throw new LockTimeout("Import in progress.", 15*1000, 0);
                }
                log.debug("No OME-NGFF root");
                return null;
            }
            Path root = asPath(uri);
            log.info("OME-NGFF root is: " + uri);
            try {
                ZarrPixelBuffer v = new ZarrPixelBuffer(
                    pixels, root, maxPlaneWidth, maxPlaneHeight,
                    zarrMetadataCache, zarrArrayCache);
                log.info("Using OME-NGFF pixel buffer");
                return v;
            } catch (Exception e) {
                log.warn(
                    "Getting OME-NGFF pixel buffer failed - " +
                    "attempting to get local data", e);
            }
        } catch (IOException e1) {
            log.debug(
                "Failed to find OME-NGFF metadata for Pixels:{}",
                pixels.getId());
        } finally {
            t0.stop();
        }
        return null;
    }

    /**
     * Returns a pixel buffer for a given set of pixels. Either an NGFF pixel
     * buffer, a proprietary ROMIO pixel buffer or a specific pixel buffer
     * implementation.
     * @param pixels Pixels set to retrieve a pixel buffer for.
     * @param write Whether or not to open the pixel buffer as read-write.
     * <code>true</code> opens as read-write, <code>false</code> opens as
     * read-only.
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

