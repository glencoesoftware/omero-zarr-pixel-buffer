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
import dev.zarr.zarrjava.store.FilesystemStore;
import dev.zarr.zarrjava.store.HttpStore;
import dev.zarr.zarrjava.store.S3Store;
import dev.zarr.zarrjava.store.StoreHandle;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProviderChain;
import software.amazon.awssdk.auth.credentials.InstanceProfileCredentialsProvider;
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider;
import software.amazon.awssdk.awscore.util.AwsHostNameUtils;
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
 * <li>http:// or https:// - HTTP-based storage</li>
 * <li>s3:// - S3-compatible object storage (with authentication via URL parameters)</li>
 * </ul>
 */
public class ZarrStore {

    private static final org.slf4j.Logger log = LoggerFactory.getLogger(ZarrStore.class);

    private static final String QUERY_REGEX = "\\?.+=.+";

    private static final String PATH_WITH_QUERY_REGEX = ".*" + QUERY_REGEX;

    /** The underlying store handle for accessing Zarr data. */
    private final StoreHandle store;

    /** The full path to the Zarr store including the .zarr extension. */
    private final String path;

    /**
     * Constructs a ZarrLocation from the given path.
     *
     * <p>The constructor automatically detects the storage backend based on the URI
     * scheme and initializes the appropriate store. </p>
     *
     * <p>For S3 URIs, authentication parameters can be provided as query parameters: </p>
     * <ul>
     * <li>anonymous - Use anonymous access</li>
     * <li>profile - Use profile credentials</li>
     * </ul>
     * Example: s3://host/bucket/path/data.zarr?anonymous=true
     *
     * @param orgPath                   the path to the Zarr
     * @throws URISyntaxException       if the path is not a valid URI
     * @throws IllegalArgumentException if the path does not contain .zarr or uses an unsupported
     *                                  URI scheme
     * @throws ZarrException            if there was a problem reading the zarr
     */
    public ZarrStore(final String orgPath)
            throws URISyntaxException, IllegalArgumentException, ZarrException {
        this.path = normalizePath(orgPath);
        if (!path.contains("://")) {
            store = new StoreHandle(new FilesystemStore(path));
        } else if (path.startsWith("http")) {
            int sep = path.lastIndexOf("/");
            String storePath = path.substring(0, sep);
            String rest = path.substring(sep + 1);
            // TODO: Fix names with "?" characters
            store = new HttpStore(storePath).resolve(rest);
        } else if (path.startsWith("s3")) {
            String[] tmp = path.replaceFirst("s3://", "").split("/");
            final String host = tmp[0];
            final String bucket = tmp[1];
            final String[] rest = Arrays.copyOfRange(tmp, 2, tmp.length);
            // Remove all query parameters
            String rawFinalPart = rest[rest.length - 1];
            String[] pathAndQuery = splitOnQuery(rawFinalPart);
            rest[rest.length - 1] = pathAndQuery[0];
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
            clientBuilder.region(getRegionFromUri(endpoint));

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

            S3Client client = clientBuilder.build();
            store = new S3Store(client, bucket, null).resolve(rest);
        } else {
            throw new IllegalArgumentException("Unsupported URI scheme: "
                    + path.subSequence(0, path.indexOf("://") + 3));
        }
    }

    /**
     * Retrieves the AWS region from a given URI.  Defaults to
     * {@link Region.US_EAST_1} if a region cannot be detected.
     *
     * @param uri URI to retrieve a region for
     * @return The region
     */
    public static Region getRegionFromUri(URI uri) {
        Optional<Region> region = AwsHostNameUtils.parseSigningRegion(uri.getHost(), null);
        if (region.isPresent()) {
            return region.get();
        }
        return Region.US_EAST_1;
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
     * Returns the underlying store handle.
     *
     * @return the StoreHandle for this Zarr location
     */
    public StoreHandle getStoreHandle() {
        return store;
    }

    /**
     * Returns the full path to the Zarr.
     *
     * @return the complete path
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
        return "ZarrStore {" + "path = " + path + "}";
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
