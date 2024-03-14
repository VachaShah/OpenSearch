/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

/*
 * Modifications Copyright OpenSearch Contributors. See
 * GitHub history for details.
 */

package org.opensearch.core.common.io.stream;

import org.opensearch.common.annotation.ExperimentalApi;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Implementers can be written to a {@linkplain OutputStream} and read from a byte array. This allows them to be "thrown
 * across the wire" using OpenSearch's internal protocol with protobuf bytes.
 *
 * @opensearch.api
 */
@ExperimentalApi
public interface BytesWriteable extends BaseWriteable {

    /**
     * Write this into the {@linkplain OutputStream}.
     */
    void writeTo(OutputStream out) throws IOException;

}
