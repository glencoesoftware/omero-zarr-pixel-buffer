/*
 * Copyright (C) 2026 Glencoe Software, Inc. All rights reserved.
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

import dev.zarr.zarrjava.ZarrException;
import dev.zarr.zarrjava.core.Array;
import dev.zarr.zarrjava.core.ArrayMetadata;
import dev.zarr.zarrjava.core.Group;
import dev.zarr.zarrjava.core.Node;
import dev.zarr.zarrjava.store.FilesystemStore;
import dev.zarr.zarrjava.store.HttpStore;
import dev.zarr.zarrjava.store.S3Store;
import dev.zarr.zarrjava.store.Store;
import dev.zarr.zarrjava.store.StoreHandle;
import dev.zarr.zarrjava.v2.Endianness;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProviderChain;
import software.amazon.awssdk.auth.credentials.InstanceProfileCredentialsProvider;
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;

/**
 * This class provides a unified interface for accessing Zarr stores from different storage backends
 * including local filesystem, HTTP/HTTPS endpoints, and S3-compatible object storage. It offers
 * convenience methods for accessing arrays and groups, as well as their metadata.
 *
 * <p>Supported URI schemes: </p>
 * <ul>
 * <li>file:// or no scheme - Local filesystem storage</li>
 * <li>http:// or https:// - HTTP-based storage</li>
 * <li>s3:// - S3-compatible object storage (with authentication via URL parameters)</li>
 * </ul>
 */
public class ZarrStore {

    private static final org.slf4j.Logger log = LoggerFactory.getLogger(ZarrStore.class);

    private static final String QUERY_REGEX = "\\?.+=.+";

    private static final String PATH_WITH_QUERY_REGEX = ".*" + QUERY_REGEX;

    /** The underlying store handle for accessing Zarr data. */
    StoreHandle store;

    /** Store interface; just for debug output. */
    Class<? extends Store> iface;

    /** Zarr version; just for debug output. */
    String zarrVersion = "N/A";

    /** The full path to the Zarr store including the .zarr extension. */
    String path;

    /** The name of the Zarr (typically the .zarr directory name). */
    String name;

    /**
     * Constructs a ZarrLocation from the given path.
     *
     * <p>The path must contain a .zarr extension. The constructor automatically detects the
     * storage backend based on the URI scheme and initializes the appropriate store. </p>
     *
     * <p>For S3 URIs, authentication parameters can be provided as query parameters: </p>
     * <ul>
     * <li>anonymous - Use anonymous access</li>
     * <li>accessKeyId and secretAccessKey - Use basic credentials</li>
     * <li>profile - Use profile credentials</li>
     * <li>region - Specify AWS region (defaults to US_EAST_1)</li>
     * </ul>
     * Example: s3://host/bucket/path/data.zarr?anonymous=true
     *
     * @param orgPath the path to the Zarr, must contain .zarr extension
     * @throws URISyntaxException       if the path is not a valid URI
     * @throws IllegalArgumentException if the path does not contain .zarr or uses an unsupported
     *                                  URI scheme
     * @throws ZarrException            if there was a problem reading the zarr
     * @throws IOException              if there was a problem reading the zarr
     */
    public ZarrStore(final String orgPath) throws URISyntaxException, IllegalArgumentException,
        IOException, ZarrException {
        this.path = normalizePath(orgPath);
        int zarrIndex = path.lastIndexOf(".zarr");
        if (zarrIndex < 0) {
            throw new IllegalArgumentException("Path is not a .zarr");
        }
        String pathToZarr = path.substring(0, zarrIndex + 5);
        if (!path.contains("://") || path.startsWith("file")) {
            int sep = path.lastIndexOf(File.separator);
            String storePath = path.substring(0, sep);
            String rest = path.substring(sep + 1);
            this.name = path.substring(pathToZarr.lastIndexOf(File.separator) + 1);
            store = new FilesystemStore(storePath).resolve(rest);
            iface = FilesystemStore.class;
        } else if (path.startsWith("http")) {
            int sep = path.lastIndexOf("/");
            String storePath = path.substring(0, sep);
            String rest = path.substring(sep + 1);
            // TODO: Fix names with "?" characters
            this.name = path.substring(pathToZarr.lastIndexOf("/") + 1);
            if (this.name.contains("?")) {
                this.name = this.name.substring(0, this.name.indexOf("?"));
            }

            store = new HttpStore(storePath).resolve(rest);
            iface = HttpStore.class;
        } else if (path.startsWith("s3")) {
            String[] tmp = path.replaceFirst("s3://", "").split("/");
            final String host = tmp[0];
            final String bucket = tmp[1];
            final String[] rest = Arrays.copyOfRange(tmp, 2, tmp.length);
            // Remove all query parameters
            String rawFinalPart = rest[rest.length - 1];
            String[] pathAndQuery = splitOnQuery(rawFinalPart);
            rest[rest.length - 1] = pathAndQuery[0];
            this.name = String.join("/", rest);
            // Extract URL parameters for authentication
            Map<String, String> params = new HashMap<>();
            String query = pathAndQuery[1];
            if (query != null) {
                if (query.startsWith("?")) {
                    query = query.substring(1);
                }
                String[] pairs = query.split("&");
                for (String pair : pairs) {
                    int idx = pair.indexOf("=");
                    if (idx > 0) {
                        params.put(pair.substring(0, idx), pair.substring(idx + 1));
                    }
                }
            }

            URI endpoint = new URI("https://" + host);

            S3ClientBuilder clientBuilder =  S3Client.builder()
                    .httpClientBuilder(UrlConnectionHttpClient.builder()
                            .socketTimeout(Duration.ofMinutes(5)));
            clientBuilder.endpointOverride(endpoint);
            clientBuilder.region(Region.US_EAST_1); // Default region required even for non-AWS

            S3Configuration s3Config = S3Configuration.builder().pathStyleAccessEnabled(true)
                .build();
            clientBuilder.serviceConfiguration(s3Config);

            assertNoAwsSystemEnvCredentials();

            if (params.containsKey("anonymous")) {
                clientBuilder.credentialsProvider(AnonymousCredentialsProvider.create());
            } else {
                clientBuilder.credentialsProvider(
                        AwsCredentialsProviderChain.builder()
                        .addCredentialsProvider(ProfileCredentialsProvider
                                .create(params.get("profile")))
                        .addCredentialsProvider(
                                InstanceProfileCredentialsProvider.create()).build());
            }
            if (params.containsKey("region")) {
                clientBuilder.region(Region.of(params.get("region")));
            }

            S3Client client = clientBuilder.build();
            store = new S3Store(client, bucket, null).resolve(rest);
            iface = S3Store.class;
        } else {
            throw new IllegalArgumentException("Unsupported URI scheme: "
                    + path.subSequence(0, path.indexOf("://") + 3));
        }
        Group group = Group.open(store);
        if (group instanceof dev.zarr.zarrjava.v2.Group) {
            zarrVersion = "2";
        } else if (group instanceof dev.zarr.zarrjava.v3.Group) {
            zarrVersion = "3";
        }
    }

    /**
     * A method to split a query-parameter-like segment from a string.
     * this method assumes there will be no ? characters in the parameters.
     * This means the method will only search from the last instance of the "?"
     * character until the end of the string.
     *
     * @param uri The String to remove the parameter from
     * @return A String array of size 2. First element is the path without the query
     *         (or entire path if no query is found) and the second is the query string
     *         or {@code null} if no query is found.
     */
    public static String[] splitOnQuery(String uri) {
        String[] pathAndQuery = new String[2];
        pathAndQuery[0] = uri;
        int lastQuestionMarkIdx = uri.lastIndexOf("?");
        if (lastQuestionMarkIdx != -1) {
            String potentialQuery = uri.substring(lastQuestionMarkIdx);
            if (potentialQuery.matches(QUERY_REGEX)) {
                pathAndQuery[0] = uri.substring(0, lastQuestionMarkIdx);
                pathAndQuery[1] = potentialQuery;
            }
        }
        return pathAndQuery;
    }

    /**
     * Removes trailing slashes from the path component of the given URI while preserving any
     * query string. Trailing slashes otherwise produce empty path segments when the URI is
     * split, which leads to malformed S3 keys such as {@code path//zarr.json}.
     *
     * @param orgPath the original URI, possibly ending in one or more slashes
     * @return the URI with trailing slashes removed from the path component
     */
    static String normalizePath(String orgPath) {
        String[] pathAndQuery = splitOnQuery(orgPath);
        String normalized = pathAndQuery[0];
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized + (pathAndQuery[1] != null ? pathAndQuery[1] : "");
    }

    private void assertNoAwsSystemEnvCredentials() {
        // If AWS Environment or System Properties are set, throw an exception
        // so users will know they are not supported
        if (System.getenv("AWS_ACCESS_KEY_ID") != null
                || System.getenv("AWS_SECRET_ACCESS_KEY") != null
                || System.getenv("AWS_SESSION_TOKEN") != null
                || System.getProperty("aws.accessKeyId") != null
                || System.getProperty("aws.secretAccessKey") != null) {
            throw new RuntimeException("AWS credentials supplied by environment variables"
                    + " or Java system properties are not supported."
                    + " Please use either named profiles or instance"
                    + " profile credentials.");
        }
    }

    /**
     * Opens and returns a Zarr group at the specified path.
     *
     * @param path the relative path to the group within the store (empty string for root group)
     * @return the opened Group object
     * @throws IOException if the group cannot be opened or does not exist
     */
    public Group getGroup(String path) throws IOException {
        try {
            if (path.isEmpty()) {
                return Group.open(store);
            } else {
                return Group.open(store.resolve(path.split("/")));
            }
        } catch (Exception e) {
            throw new IOException("Failed to open group at path: " + store + " / " + path, e);
        }
    }

    /**
     * Opens and returns a Zarr array at the specified path.
     *
     * @param path the relative path to the array within the store (empty string for root array)
     * @return the opened Array object
     * @throws IOException if the array cannot be opened or does not exist
     */
    public Array getArray(String path) throws IOException {
        try {
            if (path.isEmpty()) {
                return Array.open(store);
            } else {
                return Array.open(store.resolve(path.split("/")));
            }
        } catch (Exception e) {
            throw new IOException("Failed to open array at path: " + store + " / " + path, e);
        }
    }

    /**
     * Retrieves metadata from a Zarr array at the specified path.
     *
     * @param path the relative path to the array within the store
     * @return a map containing metadata including shape, chunkShape, dataType, and littleEndian
     * @throws RuntimeException if the array cannot be accessed or metadata cannot be retrieved
     */
    public Map<String, Object> metadataFromArray(String path) {
        try {
            Array array = getArray(path);
            return metadataFromArray(array);
        } catch (IOException e) {
            throw new RuntimeException(
                "Failed to get array metadata from path: " + store + " / " + path, e);
        }
    }

    /**
     * Extracts metadata from a Zarr array object.
     *
     * <p>The returned map contains:</p>
     * <ul>
     * <li>shape - The dimensions of the array</li>
     * <li>chunkShape - The chunk dimensions</li>
     * <li>dataType - The data type of array elements</li>
     * <li>littleEndian - Byte order (Todo: currently always true for v3 arrays)</li>
     * </ul>
     *
     * @param array the Array object to extract metadata from
     * @return a map containing the array metadata
     */
    public Map<String, Object> metadataFromArray(Array array) {
        Map<String, Object> res = new HashMap<>();
        ArrayMetadata m = array.metadata();
        if (array instanceof dev.zarr.zarrjava.v3.Array) {
            // TODO: Implement!!
            res.put("littleEndian", true);
        } else {
            dev.zarr.zarrjava.v2.ArrayMetadata m2 = (dev.zarr.zarrjava.v2.ArrayMetadata) m;
            res.put("littleEndian", m2.endianness.equals(Endianness.LITTLE));
        }
        res.put("shape", m.shape);
        res.put("chunkShape", m.chunkShape());
        res.put("dataType", m.dataType());
        return res;
    }

    /**
     * Retrieves metadata (group or array) from a Zarr node at the specified path.
     *
     * @param path the relative path to the node within the store
     * @return a map containing metadata
     * @throws RuntimeException if the node cannot be accessed or metadata cannot be retrieved
     */
    public Map<String, Object> metadata(String path) {
        try {
            Node node = null;
            if (path.isEmpty()) {
                node = Node.open(store);
            } else {
                node = Node.open(store.resolve(path.split("/")));
            }
            if (node instanceof Array) {
                return metadataFromArray((Array) node);
            } else if (node instanceof Group) {
                return metadataFromGroup((Group) node);
            } else {
                throw new RuntimeException(
                    "Failed to get metadata from path: " + store + " / " + path);
            }
        } catch (IOException | ZarrException e) {
            throw new RuntimeException(
                "Failed to get array metadata from path: " + store + " / " + path, e);
        }
    }

    /**
     * Retrieves metadata from a Zarr group at the specified path.
     *
     * @param path the relative path to the group within the store
     * @return a map containing the group's attributes
     * @throws RuntimeException if the group cannot be accessed or metadata cannot be retrieved
     */
    public Map<String, Object> metadataFromGroup(String path) {
        try {
            Group group = getGroup(path);
            return metadataFromGroup(group);
        } catch (IOException e) {
            throw new RuntimeException(
                "Failed to get group metadata from path: " + store + " / " + path, e);
        }
    }

    /**
     * Extracts metadata attributes from a Zarr group object.
     *
     * <p>This method handles both Zarr v2 and v3 group formats.</p>
     *
     * @param group the Group object to extract metadata from
     * @return a map containing the group's attributes
     */
    public Map<String, Object> metadataFromGroup(Group group) {
        if (group instanceof dev.zarr.zarrjava.v2.Group) {
            dev.zarr.zarrjava.v2.GroupMetadata m = ((dev.zarr.zarrjava.v2.Group) group).metadata;
            return m.attributes;
        } else {
            dev.zarr.zarrjava.v3.GroupMetadata m = ((dev.zarr.zarrjava.v3.Group) group).metadata;
            return m.attributes;
        }
    }

    /**
     * Returns the underlying store handle.
     *
     * @return the StoreHandle for this Zarr location
     */
    public StoreHandle getStoreHandle() {
        return store;
    }

    /**
     * Returns the name of the Zarr.
     *
     * @return the name (typically the .zarr directory name)
     */
    public String getName() {
        return name;
    }

    /**
     * Returns the full path to the Zarr.
     *
     * @return the complete path including the .zarr extension
     */
    public String getPath() {
        return path;
    }

    /**
     * Returns a string representation of this Zarr store.
     *
     * @return a string containing the path and name of the store
     */
    @Override
    public String toString() {
        return "ZarrStore {" + "path = " + path + " , name = " + name
            + " , interface = " + iface.getName() + " , zarrVersion = " + zarrVersion + "}";
    }

    /**
     * Compares this store to another object for equality.
     *
     * @param obj the object to compare with
     * @return {@code true} if the other object is a ZarrStore with the same path, {@code false}
     *         otherwise
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof ZarrStore)) {
            return false;
        }
        ZarrStore other = (ZarrStore) obj;
        return Objects.equals(path, other.path);
    }

    /**
     * Returns the hash code for this store, based solely on the path.
     *
     * @return the hash code value for this store
     */
    @Override
    public int hashCode() {
        return Objects.hash(path);
    }
}
