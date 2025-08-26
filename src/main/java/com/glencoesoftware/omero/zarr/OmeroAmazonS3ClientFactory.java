/*
 * Copyright (C) 2022 Glencoe Software, Inc. All rights reserved.
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

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import org.slf4j.LoggerFactory;

import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.AWSCredentialsProviderChain;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.AnonymousAWSCredentials;
import com.amazonaws.auth.EC2ContainerCredentialsProviderWrapper;
import com.amazonaws.auth.profile.ProfileCredentialsProvider;
import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.util.AwsHostNameUtils;
import com.upplication.s3fs.AmazonS3ClientFactory;

public class OmeroAmazonS3ClientFactory extends AmazonS3ClientFactory {

    private static final org.slf4j.Logger log =
            LoggerFactory.getLogger(OmeroAmazonS3ClientFactory.class);

    private static final Map<String, AmazonS3> bucketClientMap = new HashMap<>();

    @Override
    protected AWSCredentialsProvider getCredentialsProvider(Properties props) {
        // If AWS Environment or System Properties are set, throw an exception
        // so users will know they are not supported
        if (System.getenv("AWS_ACCESS_KEY_ID") != null ||
                System.getenv("AWS_SECRET_ACCESS_KEY") != null ||
                System.getenv("AWS_SESSION_TOKEN") != null ||
                System.getProperty("aws.accessKeyId") != null ||
                System.getProperty("aws.secretAccessKey") != null) {
            throw new RuntimeException("AWS credentials supplied by environment variables"
                    + " or Java system properties are not supported."
                    + " Please use either named profiles or instance"
                    + " profile credentials.");
        }
        boolean anonymous = Boolean.parseBoolean(
                (String) props.get("s3fs_anonymous"));
        if (anonymous) {
            log.debug("Using anonymous credentials");
            return new AWSStaticCredentialsProvider(
                    new AnonymousAWSCredentials());
        } else {
            String profileName =
                    (String) props.get("s3fs_credential_profile_name");
            // Same instances and order from DefaultAWSCredentialsProviderChain
            return new AWSCredentialsProviderChain(
                    new ProfileCredentialsProvider(profileName),
                    new EC2ContainerCredentialsProviderWrapper()
            );
        }
    }
    
    /**
     * Retrieves the bucket name from a given URI.
     * @param uri The URI to handle
     * @return The bucket name
     */
    private String getBucketFromUri(URI uri) {
        String path = uri.getPath();
        if (path.startsWith("/")) {
            path = path.substring(1);
        }
        return path.substring(0, path.indexOf("/"));
    }

    /**
     * Retrieves the region from a given URI.
     * @param uri The URI to handle
     * @return The region
     */
    private String getRegionFromUri(URI uri) {
        String region = AwsHostNameUtils.parseRegion(uri.getHost(), null);
        if (region != null) {
            return region;
        }
        return Regions.DEFAULT_REGION.getName();
    }

    /**
     * Retrieves the endpoint from a given URI.
     * @param uri The URI to handle
     * @return The endpoint
     */
    public String getEndPointFromUri(URI uri) {
        return "https://" + uri.getHost();
    }

    @Override
    public synchronized AmazonS3 getAmazonS3(URI uri, Properties props) {
        //Check if we have a S3 client for this bucket
        String bucket = getBucketFromUri(uri);
        if (bucketClientMap.containsKey(bucket)) {
            log.info("Found bucket " + bucket);
            return bucketClientMap.get(bucket);
        }
        log.info("Creating client for bucket " + bucket);
        AmazonS3 client = AmazonS3ClientBuilder.standard()
                            .withCredentials(getCredentialsProvider(props))
                            .withClientConfiguration(getClientConfiguration(props))
                            .withMetricsCollector(getRequestMetricsCollector(props))
                            .withEndpointConfiguration(new EndpointConfiguration(getEndPointFromUri(uri), getRegionFromUri(uri)))
                            .build();
        bucketClientMap.put(bucket, client);
        return client;
    }

}
