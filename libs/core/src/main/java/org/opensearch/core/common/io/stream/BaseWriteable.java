/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.core.common.io.stream;

import org.opensearch.common.annotation.InternalApi;

import java.io.IOException;

/**
 * Implementers can be written to a {@linkplain StreamOutput} and read from a {@linkplain StreamInput}. This allows them to be "thrown
 * across the wire" using OpenSearch's internal protocol. If the implementer also implements equals and hashCode then a copy made by
 * serializing and deserializing must be equal and have the same hashCode. It isn't required that such a copy be entirely unchanged.
 *
 * @opensearch.api
 */
@InternalApi
public interface BaseWriteable {

    interface Writer<O, V> {
        void write(final O out, V value) throws IOException;
    }

    interface Reader<I, V> {
        V read(final I in) throws IOException;
    }

}
