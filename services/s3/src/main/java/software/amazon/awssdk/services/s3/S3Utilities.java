/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package software.amazon.awssdk.services.s3;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.util.function.Consumer;
import java.util.function.Supplier;
import software.amazon.awssdk.annotations.Immutable;
import software.amazon.awssdk.annotations.SdkInternalApi;
import software.amazon.awssdk.annotations.SdkPublicApi;
import software.amazon.awssdk.awscore.client.config.AwsClientOption;
import software.amazon.awssdk.awscore.endpoint.DefaultServiceEndpointBuilder;
import software.amazon.awssdk.awscore.endpoint.DualstackEnabledProvider;
import software.amazon.awssdk.awscore.endpoint.FipsEnabledProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.client.config.SdkClientConfiguration;
import software.amazon.awssdk.core.client.config.SdkClientOption;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.profiles.ProfileFile;
import software.amazon.awssdk.protocols.core.OperationInfo;
import software.amazon.awssdk.protocols.core.PathMarshaller;
import software.amazon.awssdk.protocols.core.ProtocolUtils;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.internal.endpoints.S3EndpointResolverContext;
import software.amazon.awssdk.services.s3.internal.endpoints.S3EndpointResolverFactory;
import software.amazon.awssdk.services.s3.internal.endpoints.S3EndpointResolverFactoryContext;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetUrlRequest;
import software.amazon.awssdk.utils.Lazy;
import software.amazon.awssdk.utils.Validate;

/**
 * Utilities for working with Amazon S3 objects. An instance of this class can be created by:
 * <p>
 * 1) Directly using the {@link #builder()} method. You have to manually specify the configuration params like region,
 * s3Configuration on the builder.
 *
 * <pre>
 * S3Utilities utilities = S3Utilities.builder().region(Region.US_WEST_2).build()
 * GetUrlRequest request = GetUrlRequest.builder().bucket("foo-bucket").key("key-without-spaces").build();
 * URL url = utilities.getUrl(request);
 * </pre>
 * </p>
 *
 * <p>
 * 2) Using the low-level client {@link S3Client#utilities()} method. This is recommended as SDK will use the same
 * configuration from the {@link S3Client} object to create the {@link S3Utilities} object.
 *
 * <pre>
 * S3Client s3client = S3Client.create();
 * S3Utilities utilities = s3client.utilities();
 * GetUrlRequest request = GetUrlRequest.builder().bucket("foo-bucket").key("key-without-spaces").build();
 * URL url = utilities.getUrl(request);
 * </pre>
 * </p>
 *
 * Note: This class does not make network calls.
 */
@Immutable
@SdkPublicApi
public final class S3Utilities {
    private final Region region;
    private final URI endpoint;
    private final S3Configuration s3Configuration;
    private final Supplier<ProfileFile> profileFile;
    private final String profileName;
    private final boolean fipsEnabled;

    /**
     * SDK currently validates that region is present while constructing {@link S3Utilities} object.
     * This can be relaxed in the future when more methods are added that don't use region.
     */
    private S3Utilities(Builder builder) {
        this.region = Validate.paramNotNull(builder.region, "Region");
        this.endpoint = builder.endpoint;
        this.profileFile = builder.profileFile != null ? () -> builder.profileFile
                                                       : new Lazy<>(ProfileFile::defaultProfileFile)::getValue;
        this.profileName = builder.profileName;

        if (builder.s3Configuration == null) {
            this.s3Configuration = S3Configuration.builder().dualstackEnabled(builder.dualstackEnabled).build();
        } else {
            this.s3Configuration = builder.s3Configuration.toBuilder()
                                                          .applyMutation(b -> resolveDualstackSetting(b, builder))
                                                          .build();
        }

        this.fipsEnabled = builder.fipsEnabled != null ? builder.fipsEnabled
                                                       : FipsEnabledProvider.builder()
                                                                            .profileFile(profileFile)
                                                                            .profileName(profileName)
                                                                            .build()
                                                                            .isFipsEnabled()
                                                                            .orElse(false);
    }

    private void resolveDualstackSetting(S3Configuration.Builder s3ConfigBuilder, Builder s3UtiltiesBuilder) {
        Validate.validState(s3ConfigBuilder.dualstackEnabled() == null || s3UtiltiesBuilder.dualstackEnabled == null,
                            "Only one of S3Configuration.Builder's dualstackEnabled or S3Utilities.Builder's dualstackEnabled "
                            + "should be set.");

        if (s3ConfigBuilder.dualstackEnabled() != null) {
            return;
        }

        if (s3UtiltiesBuilder.dualstackEnabled != null) {
            s3ConfigBuilder.dualstackEnabled(s3UtiltiesBuilder.dualstackEnabled);
            return;
        }

        s3ConfigBuilder.dualstackEnabled(DualstackEnabledProvider.builder()
                                                                 .profileFile(profileFile)
                                                                 .profileName(profileName)
                                                                 .build()
                                                                 .isDualstackEnabled()
                                                                 .orElse(false));
    }

    /**
     * Creates a builder for {@link S3Utilities}.
     */
    public static Builder builder() {
        return new Builder();
    }

    // Used by low-level client
    @SdkInternalApi
    static S3Utilities create(SdkClientConfiguration clientConfiguration) {
        return S3Utilities.builder()
                          .region(clientConfiguration.option(AwsClientOption.AWS_REGION))
                          .endpoint(clientConfiguration.option(SdkClientOption.ENDPOINT))
                          .s3Configuration((S3Configuration) clientConfiguration.option(SdkClientOption.SERVICE_CONFIGURATION))
                          .profileFile(clientConfiguration.option(SdkClientOption.PROFILE_FILE))
                          .profileName(clientConfiguration.option(SdkClientOption.PROFILE_NAME))
                          .build();
    }

    /**
     * Returns the URL for an object stored in Amazon S3.
     *
     * If the object identified by the given bucket and key has public read permissions,
     * then this URL can be directly accessed to retrieve the object's data.
     *
     * <p>
     *     If same configuration options are set on both #GetUrlRequest and #S3Utilities objects (for example: region),
     *     the configuration set on the #GetUrlRequest takes precedence.
     * </p>
     *
     * <p>
     *     This is a convenience which creates an instance of the {@link GetUrlRequest.Builder} avoiding the need to
     *     create one manually via {@link GetUrlRequest#builder()}
     * </p>
     *
     * @param getUrlRequest A {@link Consumer} that will call methods on {@link GetUrlRequest.Builder} to create a request.
     * @return A URL for an object stored in Amazon S3.
     * @throws SdkException Generated Url is malformed
     */
    public URL getUrl(Consumer<GetUrlRequest.Builder> getUrlRequest) {
        return getUrl(GetUrlRequest.builder().applyMutation(getUrlRequest).build());
    }

    /**
     * Returns the URL for an object stored in Amazon S3.
     *
     * If the object identified by the given bucket and key has public read permissions,
     * then this URL can be directly accessed to retrieve the object's data.
     *
     * <p>
     *     If same configuration options are set on both #GetUrlRequest and #S3Utilities objects (for example: region),
     *     the configuration set on the #GetUrlRequest takes precedence.
     * </p>
     *
     * @param getUrlRequest request to construct url
     * @return A URL for an object stored in Amazon S3.
     * @throws SdkException Generated Url is malformed
     */
    public URL getUrl(GetUrlRequest getUrlRequest) {
        Region resolvedRegion = resolveRegionForGetUrl(getUrlRequest);
        URI resolvedEndpoint = resolveEndpoint(getUrlRequest.endpoint(), resolvedRegion);

        SdkHttpFullRequest marshalledRequest = createMarshalledRequest(getUrlRequest, resolvedEndpoint);

        GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                                                            .bucket(getUrlRequest.bucket())
                                                            .key(getUrlRequest.key())
                                                            .versionId(getUrlRequest.versionId())
                                                            .build();

        S3EndpointResolverContext resolverContext = S3EndpointResolverContext.builder()
                                                                             .request(marshalledRequest)
                                                                             .originalRequest(getObjectRequest)
                                                                             .region(resolvedRegion)
                                                                             .endpointOverride(getUrlRequest.endpoint())
                                                                             .serviceConfiguration(s3Configuration)
                                                                             .fipsEnabled(fipsEnabled)
                                                                             .build();

        S3EndpointResolverFactoryContext resolverFactoryContext = S3EndpointResolverFactoryContext.builder()
                .bucketName(getObjectRequest.bucket())
                .originalRequest(getObjectRequest)
                .build();

        SdkHttpRequest httpRequest = S3EndpointResolverFactory.getEndpointResolver(resolverFactoryContext)
                                                              .applyEndpointConfiguration(resolverContext)
                                                              .sdkHttpRequest();

        try {
            return httpRequest.getUri().toURL();
        } catch (MalformedURLException exception) {
            throw SdkException.create("Generated URI is malformed: " + httpRequest.getUri(),
                                      exception);
        }
    }

    private Region resolveRegionForGetUrl(GetUrlRequest getUrlRequest) {
        if (getUrlRequest.region() == null && this.region == null) {
            throw new IllegalArgumentException("Region should be provided either in GetUrlRequest object or S3Utilities object");
        }

        return getUrlRequest.region() != null ? getUrlRequest.region() : this.region;
    }

    /**
     * If endpoint is not present, construct a default endpoint using the region information.
     */
    private URI resolveEndpoint(URI requestOverrideEndpoint, Region region) {
        URI overrideEndpoint = requestOverrideEndpoint != null ? requestOverrideEndpoint : endpoint;
        return overrideEndpoint != null
               ? overrideEndpoint
               : new DefaultServiceEndpointBuilder("s3", "https").withRegion(region)
                                                                 .withProfileFile(profileFile)
                                                                 .withProfileName(profileName)
                                                                 .withDualstackEnabled(s3Configuration.dualstackEnabled())
                                                                 .withFipsEnabled(fipsEnabled)
                                                                 .getServiceEndpoint();
    }

    /**
     * Create a {@link SdkHttpFullRequest} object with the bucket and key values marshalled into the path params.
     */
    private SdkHttpFullRequest createMarshalledRequest(GetUrlRequest getUrlRequest, URI endpoint) {
        OperationInfo operationInfo = OperationInfo.builder()
                                                   .requestUri("/{Bucket}/{Key+}")
                                                   .httpMethod(SdkHttpMethod.HEAD)
                                                   .build();

        SdkHttpFullRequest.Builder builder = ProtocolUtils.createSdkHttpRequest(operationInfo, endpoint);

        // encode bucket
        builder.encodedPath(PathMarshaller.NON_GREEDY.marshall(builder.encodedPath(),
                                                               "Bucket",
                                                               getUrlRequest.bucket()));

        // encode key
        builder.encodedPath(PathMarshaller.GREEDY.marshall(builder.encodedPath(), "Key", getUrlRequest.key()));

        if (getUrlRequest.versionId() != null) {
            builder.appendRawQueryParameter("versionId", getUrlRequest.versionId());
        }

        return builder.build();
    }

    /**
     * Builder class to construct {@link S3Utilities} object
     */
    public static final class Builder {
        private Region region;
        private URI endpoint;

        private S3Configuration s3Configuration;
        private ProfileFile profileFile;
        private String profileName;
        private Boolean dualstackEnabled;
        private Boolean fipsEnabled;

        private Builder() {
        }

        /**
         * The default region to use when working with the methods in {@link S3Utilities} class.
         *
         * There can be methods in {@link S3Utilities} that don't need the region info.
         * In that case, this option will be ignored when using those methods.
         *
         * @return This object for method chaining
         */
        public Builder region(Region region) {
            this.region = region;
            return this;
        }

        /**
         * The default endpoint to use when working with the methods in {@link S3Utilities} class.
         *
         * There can be methods in {@link S3Utilities} that don't need the endpoint info.
         * In that case, this option will be ignored when using those methods.
         *
         * @return This object for method chaining
         */
        public Builder endpoint(URI endpoint) {
            this.endpoint = endpoint;
            return this;
        }

        /**
         * Configure whether the SDK should use the AWS dualstack endpoint.
         *
         * <p>If this is not specified, the SDK will attempt to determine whether the dualstack endpoint should be used
         * automatically using the following logic:
         * <ol>
         *     <li>Check the 'aws.useDualstackEndpoint' system property for 'true' or 'false'.</li>
         *     <li>Check the 'AWS_USE_DUALSTACK_ENDPOINT' environment variable for 'true' or 'false'.</li>
         *     <li>Check the {user.home}/.aws/credentials and {user.home}/.aws/config files for the 'use_dualstack_endpoint'
         *     property set to 'true' or 'false'.</li>
         * </ol>
         *
         * <p>If the setting is not found in any of the locations above, 'false' will be used.
         */
        public Builder dualstackEnabled(Boolean dualstackEnabled) {
            this.dualstackEnabled = dualstackEnabled;
            return this;
        }

        /**
         * Configure whether the SDK should use the AWS fips endpoint.
         *
         * <p>If this is not specified, the SDK will attempt to determine whether the fips endpoint should be used
         * automatically using the following logic:
         * <ol>
         *     <li>Check the 'aws.useFipsEndpoint' system property for 'true' or 'false'.</li>
         *     <li>Check the 'AWS_USE_FIPS_ENDPOINT' environment variable for 'true' or 'false'.</li>
         *     <li>Check the {user.home}/.aws/credentials and {user.home}/.aws/config files for the 'use_fips_endpoint'
         *     property set to 'true' or 'false'.</li>
         * </ol>
         *
         * <p>If the setting is not found in any of the locations above, 'false' will be used.
         */
        public Builder fipsEnabled(Boolean fipsEnabled) {
            this.fipsEnabled = fipsEnabled;
            return this;
        }

        /**
         * Sets the S3 configuration to enable options like path style access, dual stack, accelerate mode etc.
         *
         * There can be methods in {@link S3Utilities} that don't need the region info.
         * In that case, this option will be ignored when using those methods.
         *
         * @return This object for method chaining
         */
        public Builder s3Configuration(S3Configuration s3Configuration) {
            this.s3Configuration = s3Configuration;
            return this;
        }

        /**
         * The profile file from the {@link ClientOverrideConfiguration#defaultProfileFile()}. This is private and only used
         * when the utilities is created via {@link S3Client#utilities()}. This is not currently public because it may be less
         * confusing to support the full {@link ClientOverrideConfiguration} object in the future.
         */
        private Builder profileFile(ProfileFile profileFile) {
            this.profileFile = profileFile;
            return this;
        }

        /**
         * The profile name from the {@link ClientOverrideConfiguration#defaultProfileFile()}. This is private and only used
         * when the utilities is created via {@link S3Client#utilities()}. This is not currently public because it may be less
         * confusing to support the full {@link ClientOverrideConfiguration} object in the future.
         */
        private Builder profileName(String profileName) {
            this.profileName = profileName;
            return this;
        }

        /**
         * Construct a {@link S3Utilities} object.
         */
        public S3Utilities build() {
            return new S3Utilities(this);
        }
    }
}
