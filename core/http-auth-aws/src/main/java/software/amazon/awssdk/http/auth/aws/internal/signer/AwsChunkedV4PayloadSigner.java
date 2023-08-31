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

package software.amazon.awssdk.http.auth.aws.internal.signer;

import static software.amazon.awssdk.http.auth.aws.internal.util.ChecksumUtil.checksumHeaderName;
import static software.amazon.awssdk.http.auth.aws.internal.util.ChecksumUtil.fromChecksumAlgorithm;
import static software.amazon.awssdk.http.auth.aws.signer.V4CanonicalRequest.getCanonicalHeaders;
import static software.amazon.awssdk.http.auth.aws.signer.V4CanonicalRequest.getCanonicalHeadersString;
import static software.amazon.awssdk.http.auth.aws.util.SignerConstant.STREAMING_SIGNED_PAYLOAD;
import static software.amazon.awssdk.http.auth.aws.util.SignerConstant.STREAMING_SIGNED_PAYLOAD_TRAILER;
import static software.amazon.awssdk.http.auth.aws.util.SignerConstant.STREAMING_UNSIGNED_PAYLOAD_TRAILER;
import static software.amazon.awssdk.http.auth.aws.util.SignerConstant.X_AMZ_CONTENT_SHA256;
import static software.amazon.awssdk.http.auth.aws.util.SignerConstant.X_AMZ_DECODED_CONTENT_LENGTH;
import static software.amazon.awssdk.http.auth.aws.util.SignerConstant.X_AMZ_TRAILER;
import static software.amazon.awssdk.http.auth.aws.util.SignerUtils.hash;
import static software.amazon.awssdk.utils.BinaryUtils.toHex;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.reactivestreams.Publisher;
import software.amazon.awssdk.annotations.SdkInternalApi;
import software.amazon.awssdk.checksums.spi.ChecksumAlgorithm;
import software.amazon.awssdk.http.ContentStreamProvider;
import software.amazon.awssdk.http.Header;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.http.auth.aws.internal.checksums.SdkChecksum;
import software.amazon.awssdk.http.auth.aws.internal.chunkedencoding.ChunkedEncodedInputStream;
import software.amazon.awssdk.http.auth.aws.internal.chunkedencoding.TrailerProvider;
import software.amazon.awssdk.http.auth.aws.internal.io.ChecksumInputStream;
import software.amazon.awssdk.http.auth.aws.signer.CredentialScope;
import software.amazon.awssdk.http.auth.aws.signer.V4Context;
import software.amazon.awssdk.http.auth.aws.signer.V4PayloadSigner;
import software.amazon.awssdk.utils.Pair;
import software.amazon.awssdk.utils.StringInputStream;

/**
 * An implementation of a V4PayloadSigner which chunk-encodes a payload, optionally adding a chunk-signature chunk-extension,
 * and/or trailers representing trailing headers with their signature at the end.
 */
@SdkInternalApi
public class AwsChunkedV4PayloadSigner implements V4PayloadSigner {

    private static final String EMPTY_HASH = toHex(hash(""));

    private final CredentialScope credentialScope;
    private final int chunkSize;
    private final ChecksumAlgorithm checksumAlgorithm;

    public AwsChunkedV4PayloadSigner(CredentialScope credentialScope, int chunkSize, ChecksumAlgorithm checksumAlgorithm) {
        this.credentialScope = credentialScope;
        this.chunkSize = chunkSize;
        this.checksumAlgorithm = checksumAlgorithm;
    }

    /**
     * Move `Content-Length` to `x-amz-decoded-content-length` if not already present. If neither header is present,
     * an exception is thrown.
     */
    private static void moveContentLength(SdkHttpRequest.Builder request) {
        if (!request.firstMatchingHeader(X_AMZ_DECODED_CONTENT_LENGTH).isPresent()) {
            // if the decoded length isn't present, content-length must be there
            String contentLength = request
                .firstMatchingHeader(Header.CONTENT_LENGTH)
                .orElseThrow(() -> new IllegalArgumentException(Header.CONTENT_LENGTH + " must be specified!"));

            request.putHeader(X_AMZ_DECODED_CONTENT_LENGTH, contentLength)
                   .removeHeader(Header.CONTENT_LENGTH);
        } else {
            // decoded header is already there, so remove content-length just to be sure it's gone
            request.removeHeader(Header.CONTENT_LENGTH);
        }
    }

    @Override
    public ContentStreamProvider sign(ContentStreamProvider payload, V4Context v4Context) {
        SdkHttpRequest.Builder request = v4Context.getSignedRequest();
        moveContentLength(request);

        String checksum = request.firstMatchingHeader(X_AMZ_CONTENT_SHA256).orElseThrow(
            () -> new IllegalArgumentException(X_AMZ_CONTENT_SHA256 + " must be set!")
        );

        InputStream inputStream = payload != null ? payload.newStream() : new StringInputStream("");
        ChunkedEncodedInputStream.Builder chunkedEncodedInputStreamBuilder = ChunkedEncodedInputStream
            .builder()
            .inputStream(inputStream)
            .chunkSize(chunkSize)
            .header(chunk -> Integer.toHexString(chunk.length).getBytes(StandardCharsets.UTF_8));
        setupPreExistingTrailers(chunkedEncodedInputStreamBuilder, request);

        switch (checksum) {
            case STREAMING_SIGNED_PAYLOAD: {
                RollingSigner rollingSigner = new RollingSigner(v4Context.getSigningKey(), v4Context.getSignature());
                setupSigExt(chunkedEncodedInputStreamBuilder, rollingSigner);
                break;
            }
            case STREAMING_UNSIGNED_PAYLOAD_TRAILER:
                if (checksumAlgorithm != null) {
                    setupChecksumTrailer(chunkedEncodedInputStreamBuilder, request);
                }
                break;
            case STREAMING_SIGNED_PAYLOAD_TRAILER: {
                RollingSigner rollingSigner = new RollingSigner(v4Context.getSigningKey(), v4Context.getSignature());
                setupSigExt(chunkedEncodedInputStreamBuilder, rollingSigner);
                if (checksumAlgorithm != null) {
                    setupChecksumTrailer(chunkedEncodedInputStreamBuilder, request);
                }
                setupSigTrailer(chunkedEncodedInputStreamBuilder, rollingSigner);
                break;
            }
            default:
                throw new UnsupportedOperationException();
        }

        return chunkedEncodedInputStreamBuilder::build;
    }

    @Override
    public Publisher<ByteBuffer> signAsync(Publisher<ByteBuffer> payload, V4Context v4Context) {
        throw new UnsupportedOperationException();
    }

    /**
     * Add the chunk signature as a chunk-extension.
     * <p>
     * An instance of a rolling-signer is required, since each chunk's signature is dependent on the last. The first
     * chunk signature is dependent on the request signature ("seed" signature).
     */
    private void setupSigExt(ChunkedEncodedInputStream.Builder builder, RollingSigner rollingSigner) {
        Function<byte[], String> extTemplate = chunk -> rollingSigner.sign(
            previousSignature ->
                String.join("\n",
                            "AWS4-HMAC-SHA256-PAYLOAD",
                            credentialScope.getDatetime(),
                            credentialScope.scope(),
                            previousSignature,
                            EMPTY_HASH,
                            toHex(hash(chunk))
                )
        );

        builder.addExtension(
            chunk -> Pair.of(
                "chunk-signature".getBytes(StandardCharsets.UTF_8),
                extTemplate.apply(chunk).getBytes(StandardCharsets.UTF_8)
            )
        );
    }

    /**
     * Add the trailer signature as a chunk-trailer.
     * <p>
     * In order for this to work, the instance of the rolling-signer MUST be the same instance used to provide a chunk
     * signature chunk-extension. The trailer signature depends on the rolling calculation of all previous chunks.
     */
    private void setupSigTrailer(ChunkedEncodedInputStream.Builder builder, RollingSigner rollingSigner) {
        List<TrailerProvider> trailers = builder.trailers();

        Supplier<String> sigSupplier = () -> rollingSigner.sign(
            previousSignature -> {
                // Get the headers by calling get() on each of the trailers
                Map<String, List<String>> headers =
                    trailers.stream().map(TrailerProvider::get).collect(
                        Collectors.toMap(
                            Pair::left,
                            Pair::right
                        )
                    );

                // build the string-to-sign template for the rolling-signer to sign
                return String.join("\n",
                                   "AWS4-HMAC-SHA256-TRAILER",
                                   credentialScope.getDatetime(),
                                   credentialScope.scope(),
                                   previousSignature,
                                   toHex(hash(getCanonicalHeadersString(getCanonicalHeaders(headers))))
                );
            }

        );

        builder.addTrailer(
            () -> Pair.of(
                "x-amz-trailer-signature",
                Collections.singletonList(sigSupplier.get())
            )
        );
    }

    /**
     * Add the checksum as a chunk-trailer and add it to the request's trailer header.
     * <p>
     * The checksum-algorithm MUST be set if this is called, otherwise it will throw.
     */
    private void setupChecksumTrailer(ChunkedEncodedInputStream.Builder builder, SdkHttpRequest.Builder request) {
        if (checksumAlgorithm == null) {
            throw new IllegalArgumentException("A checksum-algorithm must be configured in order to add a checksum trailer!");
        }
        SdkChecksum sdkChecksum = fromChecksumAlgorithm(checksumAlgorithm);
        ChecksumInputStream checksumInputStream = new ChecksumInputStream(
            builder.inputStream(),
            Collections.singleton(sdkChecksum)
        );
        String checksumHeaderName = checksumHeaderName(checksumAlgorithm);

        TrailerProvider checksumTrailer = () -> Pair.of(
            checksumHeaderName,
            Collections.singletonList(sdkChecksum.getChecksum())
        );

        request.appendHeader(X_AMZ_TRAILER, checksumHeaderName);
        builder.inputStream(checksumInputStream).addTrailer(checksumTrailer);
    }

    /**
     * Create chunk-trailers for each pre-existing trailer given in the request.
     * <p>
     * However, we need to validate that these are valid trailers. Since aws-chunked encoding adds the checksum as a trailer, it
     * isn't part of the request headers, but other trailers MUST be present in the request-headers.
     */
    private void setupPreExistingTrailers(ChunkedEncodedInputStream.Builder builder, SdkHttpRequest.Builder request) {
        List<String> trailerHeaders = request.matchingHeaders(X_AMZ_TRAILER);

        for (String header : trailerHeaders) {
            List<String> values = request.matchingHeaders(header);
            if (values.isEmpty()) {
                throw new IllegalArgumentException(header + " must be present in the request headers to be a valid trailer.");
            }

            // Add the trailer to the aws-chunked stream-builder, and remove it from the request headers
            builder.addTrailer(() -> Pair.of(header, values));
            request.removeHeader(header);
        }
    }
}
