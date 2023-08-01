/*
* SPDX-License-Identifier: Apache-2.0
*
* The OpenSearch Contributors require contributions made to
* this file be licensed under the Apache-2.0 license or a
* compatible open source license.
*/

package org.opensearch.plugins;

import org.opensearch.action.ProtobufActionType;
import org.opensearch.action.ProtobufActionRequest;
import org.opensearch.action.ProtobufActionResponse;
import org.opensearch.action.RequestValidators;
import org.opensearch.action.admin.indices.alias.IndicesAliasesRequest;
import org.opensearch.action.admin.indices.mapping.put.PutMappingRequest;
import org.opensearch.action.support.ProtobufActionFilter;
import org.opensearch.action.support.ProtobufTransportAction;
import org.opensearch.action.support.TransportActions;
import org.opensearch.cluster.metadata.IndexNameExpressionResolver;
import org.opensearch.cluster.node.DiscoveryNodes;
import org.opensearch.core.common.Strings;
import org.opensearch.common.settings.ClusterSettings;
import org.opensearch.common.settings.IndexScopedSettings;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.settings.SettingsFilter;
import org.opensearch.common.util.concurrent.ThreadContext;
import org.opensearch.rest.RestController;
import org.opensearch.rest.ProtobufRestHandler;
import org.opensearch.rest.RestHeaderDefinition;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

/**
 * An additional extension point for {@link Plugin}s that extends OpenSearch's scripting functionality. Implement it like this:
* <pre>{@code
*   {@literal @}Override
*   public List<ActionHandler<?, ?>> getActions() {
*       return Arrays.asList(new ActionHandler<>(ReindexAction.INSTANCE, TransportReindexAction.class),
*               new ActionHandler<>(UpdateByQueryAction.INSTANCE, TransportUpdateByQueryAction.class),
*               new ActionHandler<>(DeleteByQueryAction.INSTANCE, TransportDeleteByQueryAction.class),
*               new ActionHandler<>(RethrottleAction.INSTANCE, TransportRethrottleAction.class));
*   }
* }</pre>
*
* @opensearch.api
*/
public interface ProtobufActionPlugin {
    /**
     * Actions added by this plugin.
    */
    default List<ActionHandler<? extends ProtobufActionRequest, ? extends ProtobufActionResponse>> getActions() {
        return Collections.emptyList();
    }

    /**
     * Client actions added by this plugin. This defaults to all of the {@linkplain ProtobufActionType} in
    * {@linkplain ProtobufActionPlugin#getActions()}.
    */
    default List<ProtobufActionType<? extends ProtobufActionResponse>> getClientActions() {
        return getActions().stream().map(a -> a.action).collect(Collectors.toList());
    }

    /**
     * ProtobufActionType filters added by this plugin.
    */
    default List<ProtobufActionFilter> getActionFilters() {
        return Collections.emptyList();
    }

    /**
     * Rest handlers added by this plugin.
    */
    default List<ProtobufRestHandler> getRestHandlers(
        Settings settings,
        RestController restController,
        ClusterSettings clusterSettings,
        IndexScopedSettings indexScopedSettings,
        SettingsFilter settingsFilter,
        IndexNameExpressionResolver indexNameExpressionResolver,
        Supplier<DiscoveryNodes> nodesInCluster
    ) {
        return Collections.emptyList();
    }

    /**
     * Returns headers which should be copied through rest requests on to internal requests.
    */
    default Collection<RestHeaderDefinition> getRestHeaders() {
        return Collections.emptyList();
    }

    /**
     * Returns headers which should be copied from internal requests into tasks.
    */
    default Collection<String> getTaskHeaders() {
        return Collections.emptyList();
    }

    /**
     * Returns a function used to wrap each rest request before handling the request.
    * The returned {@link UnaryOperator} is called for every incoming rest request and receives
    * the original rest handler as it's input. This allows adding arbitrary functionality around
    * rest request handlers to do for instance logging or authentication.
    * A simple example of how to only allow GET request is here:
    * <pre>
    * {@code
    *    UnaryOperator<ProtobufRestHandler> getRestHandlerWrapper(ThreadContext threadContext) {
    *      return originalHandler -> (ProtobufRestHandler) (request, channel, client) -> {
    *        if (request.method() != Method.GET) {
    *          throw new IllegalStateException("only GET requests are allowed");
    *        }
    *        originalHandler.handleRequest(request, channel, client);
    *      };
    *    }
    * }
    * </pre>
    *
    * Note: Only one installed plugin may implement a rest wrapper.
    */
    default UnaryOperator<ProtobufRestHandler> getRestHandlerWrapper(ThreadContext threadContext) {
        return null;
    }

    /**
     *  Class responsible for handing Transport Actions
    *
    * @opensearch.internal
    */
    final class ActionHandler<Request extends ProtobufActionRequest, Response extends ProtobufActionResponse> {
        private final ProtobufActionType<Response> action;
        private final Class<? extends ProtobufTransportAction<Request, Response>> transportAction;
        private final Class<?>[] supportTransportActions;

        /**
         * Create a record of an action, the {@linkplain ProtobufTransportAction} that handles it, and any supporting {@linkplain TransportActions}
        * that are needed by that {@linkplain ProtobufTransportAction}.
        */
        public ActionHandler(
            ProtobufActionType<Response> action,
            Class<? extends ProtobufTransportAction<Request, Response>> transportAction,
            Class<?>... supportTransportActions
        ) {
            this.action = action;
            this.transportAction = transportAction;
            this.supportTransportActions = supportTransportActions;
        }

        public ProtobufActionType<Response> getAction() {
            return action;
        }

        public Class<? extends ProtobufTransportAction<Request, Response>> getTransportAction() {
            return transportAction;
        }

        public Class<?>[] getSupportTransportActions() {
            return supportTransportActions;
        }

        @Override
        public String toString() {
            StringBuilder b = new StringBuilder().append(action.name()).append(" is handled by ").append(transportAction.getName());
            if (supportTransportActions.length > 0) {
                b.append('[').append(Strings.arrayToCommaDelimitedString(supportTransportActions)).append(']');
            }
            return b.toString();
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == null || obj.getClass() != ActionHandler.class) {
                return false;
            }
            ActionHandler<?, ?> other = (ActionHandler<?, ?>) obj;
            return Objects.equals(action, other.action)
                && Objects.equals(transportAction, other.transportAction)
                && Objects.deepEquals(supportTransportActions, other.supportTransportActions);
        }

        @Override
        public int hashCode() {
            return Objects.hash(action, transportAction, supportTransportActions);
        }
    }

    /**
     * Returns a collection of validators that are used by {@link RequestValidators} to validate a
    * {@link org.opensearch.action.admin.indices.mapping.put.PutMappingRequest} before the executing it.
    */
    default Collection<RequestValidators.RequestValidator<PutMappingRequest>> mappingRequestValidators() {
        return Collections.emptyList();
    }

    default Collection<RequestValidators.RequestValidator<IndicesAliasesRequest>> indicesAliasesRequestValidators() {
        return Collections.emptyList();
    }

}