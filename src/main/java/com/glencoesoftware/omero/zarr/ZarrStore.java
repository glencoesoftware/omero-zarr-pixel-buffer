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
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

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
     * @param path                      the path to the Zarr
     * @throws URISyntaxException       if the path is not a valid URI
     * @throws IllegalArgumentException if the path uses an unsupported URI scheme
     * @throws ZarrException            if there was a problem reading the zarr
     */
    public ZarrStore(final String path)
            throws URISyntaxException, IllegalArgumentException, ZarrException {
        this.path = path;
        if (!path.contains("://")) {
            store = new FilesystemStore("/")
                    .resolve(path.replaceFirst("/", ""));
        } else if (path.startsWith("http")) {
            URI asUri = new URI(path);
            URI base = new URI(
                    asUri.getScheme(),
                    asUri.getAuthority(),
                    "/",
                    asUri.getQuery(),
                    asUri.getFragment());
            store = new HttpStore(base.toString())
                    .resolve(asUri.getPath().replaceFirst("/", ""));
        } else if (path.startsWith("s3")) {
            S3Path s3path = new S3Path(path);

            S3ClientBuilder clientBuilder =  S3Client.builder()
                    .httpClientBuilder(UrlConnectionHttpClient.builder()
                            .socketTimeout(Duration.ofMinutes(5)));
            clientBuilder.endpointOverride(new URI("https://" + s3path.host));
            clientBuilder.region(s3path.getRegion());

            S3Configuration s3Config = S3Configuration.builder().pathStyleAccessEnabled(true)
                .build();
            clientBuilder.serviceConfiguration(s3Config);

            assertNoAwsSystemEnvCredentials();

            if (s3path.params.containsKey("anonymous")) {
                clientBuilder.credentialsProvider(AnonymousCredentialsProvider.create());
            } else {
                clientBuilder.credentialsProvider(
                        AwsCredentialsProviderChain.builder()
                        .addCredentialsProvider(ProfileCredentialsProvider
                                .create(s3path.params.get("profile")))
                        .addCredentialsProvider(
                                InstanceProfileCredentialsProvider.create()).build());
            }

            S3Client client = clientBuilder.build();
            store = new S3Store(client, s3path.bucket, null).resolve(s3path.key);
        } else {
            throw new IllegalArgumentException("Unsupported URI scheme: "
                    + path.subSequence(0, path.indexOf("://") + 3));
        }
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

    static class S3Path {

        static final Pattern QUERY_REGEX = Pattern.compile("\\?.+=.+");

        final String host;

        final String bucket;

        final String key;

        final Map<String, String> params = new HashMap<String, String>();

        public S3Path(String uri) {
            List<String> parts = new ArrayList<>(
                    Arrays.asList(uri.replaceFirst("s3://", "").split("/")));
            host = parts.remove(0);
            bucket = parts.remove(0);
            // Remove all query parameters, strip trailing slashes
            String[] pathAndQuery = splitOnQuery(parts.get(parts.size() -1 ));
            parts.set(parts.size() - 1, pathAndQuery[0]);
            key  = String.join("/", parts).replaceAll("/+$", "");
            // Extract URL parameters for authentication
            String query = pathAndQuery[1].replaceFirst("\\?", "");
            for (String pair : query.split("&")) {
                int idx = pair.indexOf("=");
                if (idx > 0) {
                    params.put(pair.substring(0, idx), pair.substring(idx + 1));
                }
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
         *         which will be an empty string if no query is found.
         */
        public String[] splitOnQuery(String uri) {
            int idx = uri.lastIndexOf("?");
            if (idx < 0) {
                return new String[] { uri, "" };
            }

            String query = uri.substring(idx);
            if (!QUERY_REGEX.matcher(query).matches()) {
                return new String[] { uri, "" };
            }
            return new String[] {
                 uri.substring(0, idx), uri.substring(idx + 1)
            };
        }

        /**
         * Retrieves the AWS region for this S3 path.  Defaults to
         * {@link Region.US_EAST_1} if a region cannot be detected.
         *
         * @param uri URI to retrieve a region for
         * @return The region
         */
        public Region getRegion() {
            return AwsHostNameUtils.parseSigningRegion(host, null)
                    .orElse(Region.US_EAST_1);
        }
    }
}
