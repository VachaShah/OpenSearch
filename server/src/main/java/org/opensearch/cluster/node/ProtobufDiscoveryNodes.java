/*
* SPDX-License-Identifier: Apache-2.0
*
* The OpenSearch Contributors require contributions made to
* this file be licensed under the Apache-2.0 license or a
* compatible open source license.
*/

package org.opensearch.cluster.node;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import com.carrotsearch.hppc.ObjectHashSet;
import com.carrotsearch.hppc.cursors.ObjectCursor;
import com.carrotsearch.hppc.cursors.ObjectObjectCursor;
import org.opensearch.Version;
import org.opensearch.cluster.ProtobufAbstractDiffable;
import org.opensearch.cluster.ProtobufDiff;
import org.opensearch.cluster.ProtobufAbstractDiffable;
import org.opensearch.common.Booleans;
import org.opensearch.common.Nullable;
import org.opensearch.common.Strings;
import org.opensearch.common.collect.ImmutableOpenMap;
import org.opensearch.common.regex.Regex;
import org.opensearch.common.transport.TransportAddress;
import org.opensearch.common.util.set.Sets;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * This class holds all {@link ProtobufDiscoveryNode} in the cluster and provides convenience methods to
* access, modify merge / diff discovery nodes.
*
* @opensearch.internal
*/
public class ProtobufDiscoveryNodes extends ProtobufAbstractDiffable<ProtobufDiscoveryNodes> implements Iterable<ProtobufDiscoveryNode> {

    public static final ProtobufDiscoveryNodes EMPTY_NODES = builder().build();

    private final ImmutableOpenMap<String, ProtobufDiscoveryNode> nodes;
    private final ImmutableOpenMap<String, ProtobufDiscoveryNode> dataNodes;
    private final ImmutableOpenMap<String, ProtobufDiscoveryNode> clusterManagerNodes;
    private final ImmutableOpenMap<String, ProtobufDiscoveryNode> ingestNodes;

    private final String clusterManagerNodeId;
    private final String localNodeId;
    private final Version minNonClientNodeVersion;
    private final Version maxNonClientNodeVersion;
    private final Version maxNodeVersion;
    private final Version minNodeVersion;

    private ProtobufDiscoveryNodes(
        ImmutableOpenMap<String, ProtobufDiscoveryNode> nodes,
        ImmutableOpenMap<String, ProtobufDiscoveryNode> dataNodes,
        ImmutableOpenMap<String, ProtobufDiscoveryNode> clusterManagerNodes,
        ImmutableOpenMap<String, ProtobufDiscoveryNode> ingestNodes,
        String clusterManagerNodeId,
        String localNodeId,
        Version minNonClientNodeVersion,
        Version maxNonClientNodeVersion,
        Version maxNodeVersion,
        Version minNodeVersion
    ) {
        this.nodes = nodes;
        this.dataNodes = dataNodes;
        this.clusterManagerNodes = clusterManagerNodes;
        this.ingestNodes = ingestNodes;
        this.clusterManagerNodeId = clusterManagerNodeId;
        this.localNodeId = localNodeId;
        this.minNonClientNodeVersion = minNonClientNodeVersion;
        this.maxNonClientNodeVersion = maxNonClientNodeVersion;
        this.minNodeVersion = minNodeVersion;
        this.maxNodeVersion = maxNodeVersion;
    }

    @Override
    public Iterator<ProtobufDiscoveryNode> iterator() {
        return nodes.valuesIt();
    }

    /**
     * Returns {@code true} if the local node is the elected cluster-manager node.
    */
    public boolean isLocalNodeElectedClusterManager() {
        if (localNodeId == null) {
            // we don't know yet the local node id, return false
            return false;
        }
        return localNodeId.equals(clusterManagerNodeId);
    }

    /**
     * Returns {@code true} if the local node is the elected cluster-manager node.
    *
    * @deprecated As of 2.2, because supporting inclusive language, replaced by {@link #isLocalNodeElectedClusterManager()}
    */
    @Deprecated
    public boolean isLocalNodeElectedMaster() {
        return isLocalNodeElectedClusterManager();
    }

    /**
     * Get the number of known nodes
    *
    * @return number of nodes
    */
    public int getSize() {
        return nodes.size();
    }

    /**
     * Get a {@link Map} of the discovered nodes arranged by their ids
    *
    * @return {@link Map} of the discovered nodes arranged by their ids
    */
    public ImmutableOpenMap<String, ProtobufDiscoveryNode> getNodes() {
        return this.nodes;
    }

    /**
     * Get a {@link Map} of the discovered data nodes arranged by their ids
    *
    * @return {@link Map} of the discovered data nodes arranged by their ids
    */
    public ImmutableOpenMap<String, ProtobufDiscoveryNode> getDataNodes() {
        return this.dataNodes;
    }

    /**
     * Get a {@link Map} of the discovered cluster-manager nodes arranged by their ids
    *
    * @return {@link Map} of the discovered cluster-manager nodes arranged by their ids
    */
    public ImmutableOpenMap<String, ProtobufDiscoveryNode> getClusterManagerNodes() {
        return this.clusterManagerNodes;
    }

    /**
     * Get a {@link Map} of the discovered cluster-manager nodes arranged by their ids
    *
    * @return {@link Map} of the discovered cluster-manager nodes arranged by their ids
    * @deprecated As of 2.2, because supporting inclusive language, replaced by {@link #getClusterManagerNodes()}
    */
    @Deprecated
    public ImmutableOpenMap<String, ProtobufDiscoveryNode> getMasterNodes() {
        return getClusterManagerNodes();
    }

    /**
     * @return All the ingest nodes arranged by their ids
    */
    public ImmutableOpenMap<String, ProtobufDiscoveryNode> getIngestNodes() {
        return ingestNodes;
    }

    /**
     * Get a {@link Map} of the discovered cluster-manager and data nodes arranged by their ids
    *
    * @return {@link Map} of the discovered cluster-manager and data nodes arranged by their ids
    */
    public ImmutableOpenMap<String, ProtobufDiscoveryNode> getClusterManagerAndDataNodes() {
        ImmutableOpenMap.Builder<String, ProtobufDiscoveryNode> nodes = ImmutableOpenMap.builder(dataNodes);
        nodes.putAll(clusterManagerNodes);
        return nodes.build();
    }

    /**
     * Get a {@link Map} of the discovered cluster-manager and data nodes arranged by their ids
    *
    * @return {@link Map} of the discovered cluster-manager and data nodes arranged by their ids
    * @deprecated As of 2.2, because supporting inclusive language, replaced by {@link #getClusterManagerAndDataNodes()}
    */
    @Deprecated
    public ImmutableOpenMap<String, ProtobufDiscoveryNode> getMasterAndDataNodes() {
        return getClusterManagerAndDataNodes();
    }

    /**
     * Get a {@link Map} of the coordinating only nodes (nodes which are neither cluster-manager, nor data, nor ingest nodes) arranged by their ids
    *
    * @return {@link Map} of the coordinating only nodes arranged by their ids
    */
    public ImmutableOpenMap<String, ProtobufDiscoveryNode> getCoordinatingOnlyNodes() {
        ImmutableOpenMap.Builder<String, ProtobufDiscoveryNode> nodes = ImmutableOpenMap.builder(this.nodes);
        nodes.removeAll(clusterManagerNodes.keys());
        nodes.removeAll(dataNodes.keys());
        nodes.removeAll(ingestNodes.keys());
        return nodes.build();
    }

    /**
     * Returns a stream of all nodes, with cluster-manager nodes at the front
    */
    public Stream<ProtobufDiscoveryNode> clusterManagersFirstStream() {
        return Stream.concat(
            StreamSupport.stream(clusterManagerNodes.spliterator(), false).map(cur -> cur.value),
            StreamSupport.stream(this.spliterator(), false).filter(n -> n.isClusterManagerNode() == false)
        );
    }

    /**
     * Returns a stream of all nodes, with cluster-manager nodes at the front
    *
    * @deprecated As of 2.2, because supporting inclusive language, replaced by {@link #clusterManagersFirstStream()}
    */
    @Deprecated
    public Stream<ProtobufDiscoveryNode> mastersFirstStream() {
        return clusterManagersFirstStream();
    }

    /**
     * Get a node by its id
    *
    * @param nodeId id of the wanted node
    * @return wanted node if it exists. Otherwise <code>null</code>
    */
    public ProtobufDiscoveryNode get(String nodeId) {
        return nodes.get(nodeId);
    }

    /**
     * Determine if a given node id exists
    *
    * @param nodeId id of the node which existence should be verified
    * @return <code>true</code> if the node exists. Otherwise <code>false</code>
    */
    public boolean nodeExists(String nodeId) {
        return nodes.containsKey(nodeId);
    }

    /**
     * Determine if a given node exists
    *
    * @param node of the node which existence should be verified
    * @return <code>true</code> if the node exists. Otherwise <code>false</code>
    */
    public boolean nodeExists(ProtobufDiscoveryNode node) {
        ProtobufDiscoveryNode existing = nodes.get(node.getId());
        return existing != null && existing.equals(node);
    }

    /**
     * Determine if the given node exists and has the right roles. Supported roles vary by version, and our local cluster state might
    * have come via an older cluster-manager, so the roles may differ even if the node is otherwise identical.
    */
    public boolean nodeExistsWithSameRoles(ProtobufDiscoveryNode discoveryNode) {
        final ProtobufDiscoveryNode existing = nodes.get(discoveryNode.getId());
        return existing != null && existing.equals(discoveryNode) && existing.getRoles().equals(discoveryNode.getRoles());
    }

    /**
     * Get the id of the cluster-manager node
    *
    * @return id of the cluster-manager
    */
    public String getClusterManagerNodeId() {
        return this.clusterManagerNodeId;
    }

    /**
     * Get the id of the cluster-manager node
    *
    * @return id of the cluster-manager
    * @deprecated As of 2.2, because supporting inclusive language, replaced by {@link #getClusterManagerNodeId()}
    */
    @Deprecated
    public String getMasterNodeId() {
        return getClusterManagerNodeId();
    }

    /**
     * Get the id of the local node
    *
    * @return id of the local node
    */
    public String getLocalNodeId() {
        return this.localNodeId;
    }

    /**
     * Get the local node
    *
    * @return local node
    */
    public ProtobufDiscoveryNode getLocalNode() {
        return nodes.get(localNodeId);
    }

    /**
     * Returns the cluster-manager node, or {@code null} if there is no cluster-manager node
    */
    @Nullable
    public ProtobufDiscoveryNode getClusterManagerNode() {
        if (clusterManagerNodeId != null) {
            return nodes.get(clusterManagerNodeId);
        }
        return null;
    }

    /**
     * Returns the cluster-manager node, or {@code null} if there is no cluster-manager node
    *
    * @deprecated As of 2.2, because supporting inclusive language, replaced by {@link #getClusterManagerNode()}
    */
    @Deprecated
    @Nullable
    public ProtobufDiscoveryNode getMasterNode() {
        return getClusterManagerNode();
    }

    /**
     * Get a node by its address
    *
    * @param address {@link TransportAddress} of the wanted node
    * @return node identified by the given address or <code>null</code> if no such node exists
    */
    public ProtobufDiscoveryNode findByAddress(TransportAddress address) {
        for (ObjectCursor<ProtobufDiscoveryNode> cursor : nodes.values()) {
            ProtobufDiscoveryNode node = cursor.value;
            if (node.getAddress().equals(address)) {
                return node;
            }
        }
        return null;
    }

    /**
     * Returns the version of the node with the oldest version in the cluster that is not a client node
    *
    * If there are no non-client nodes, Version.CURRENT will be returned.
    *
    * @return the oldest version in the cluster
    */
    public Version getSmallestNonClientNodeVersion() {
        return minNonClientNodeVersion;
    }

    /**
     * Returns the version of the node with the youngest version in the cluster that is not a client node.
    *
    * If there are no non-client nodes, Version.CURRENT will be returned.
    *
    * @return the youngest version in the cluster
    */
    public Version getLargestNonClientNodeVersion() {
        return maxNonClientNodeVersion;
    }

    /**
     * Returns the version of the node with the oldest version in the cluster.
    *
    * @return the oldest version in the cluster
    */
    public Version getMinNodeVersion() {
        return minNodeVersion;
    }

    /**
     * Returns the version of the node with the youngest version in the cluster
    *
    * @return the youngest version in the cluster
    */
    public Version getMaxNodeVersion() {
        return maxNodeVersion;
    }

    /**
     * Resolve a node with a given id
    *
    * @param node id of the node to discover
    * @return discovered node matching the given id
    * @throws IllegalArgumentException if more than one node matches the request or no nodes have been resolved
    */
    public ProtobufDiscoveryNode resolveNode(String node) {
        String[] resolvedNodeIds = resolveNodes(node);
        if (resolvedNodeIds.length > 1) {
            throw new IllegalArgumentException(
                "resolved [" + node + "] into [" + resolvedNodeIds.length + "] nodes, where expected to be resolved to a single node"
            );
        }
        if (resolvedNodeIds.length == 0) {
            throw new IllegalArgumentException("failed to resolve [" + node + "], no matching nodes");
        }
        return nodes.get(resolvedNodeIds[0]);
    }

    /**
     * Resolves a set of nodes according to the given sequence of node specifications. Implements the logic in various APIs that allow the
    * user to run the action on a subset of the nodes in the cluster. See [Node specification] in the reference manual for full details.
    *
    * Works by tracking the current set of nodes and applying each node specification in sequence. The set starts out empty and each node
    * specification may either add or remove nodes. For instance:
    *
    * - _local, _cluster_manager (_master) and _all respectively add to the subset the local node, the currently-elected cluster_manager, and all the nodes
    * - node IDs, names, hostnames and IP addresses all add to the subset any nodes which match
    * - a wildcard-based pattern of the form "attr*:value*" adds to the subset all nodes with a matching attribute with a matching value
    * - role:true adds to the subset all nodes with a matching role
    * - role:false removes from the subset all nodes with a matching role.
    *
    * An empty sequence of node specifications returns all nodes, since the corresponding actions run on all nodes by default.
    */
    public String[] resolveNodes(String... nodes) {
        if (nodes == null || nodes.length == 0) {
            return StreamSupport.stream(this.spliterator(), false).map(ProtobufDiscoveryNode::getId).toArray(String[]::new);
        } else {
            ObjectHashSet<String> resolvedNodesIds = new ObjectHashSet<>(nodes.length);
            for (String nodeId : nodes) {
                if (nodeId == null) {
                    // don't silence the underlying issue, it is a bug, so lets fail if assertions are enabled
                    assert nodeId != null : "nodeId should not be null";
                    continue;
                } else if (nodeId.equals("_local")) {
                    String localNodeId = getLocalNodeId();
                    if (localNodeId != null) {
                        resolvedNodesIds.add(localNodeId);
                    }
                } else if (nodeId.equals("_master") || nodeId.equals("_cluster_manager")) {
                    String clusterManagerNodeId = getClusterManagerNodeId();
                    if (clusterManagerNodeId != null) {
                        resolvedNodesIds.add(clusterManagerNodeId);
                    }
                } else if (nodeExists(nodeId)) {
                    resolvedNodesIds.add(nodeId);
                } else {
                    for (ProtobufDiscoveryNode node : this) {
                        if ("_all".equals(nodeId)
                            || Regex.simpleMatch(nodeId, node.getName())
                            || Regex.simpleMatch(nodeId, node.getHostAddress())
                            || Regex.simpleMatch(nodeId, node.getHostName())) {
                            resolvedNodesIds.add(node.getId());
                        }
                    }
                    int index = nodeId.indexOf(':');
                    if (index != -1) {
                        String matchAttrName = nodeId.substring(0, index);
                        String matchAttrValue = nodeId.substring(index + 1);
                        if (DiscoveryNodeRole.DATA_ROLE.roleName().equals(matchAttrName)) {
                            if (Booleans.parseBoolean(matchAttrValue, true)) {
                                resolvedNodesIds.addAll(dataNodes.keys());
                            } else {
                                resolvedNodesIds.removeAll(dataNodes.keys());
                            }
                        } else if (roleNameIsClusterManager(matchAttrName)) {
                            if (Booleans.parseBoolean(matchAttrValue, true)) {
                                resolvedNodesIds.addAll(clusterManagerNodes.keys());
                            } else {
                                resolvedNodesIds.removeAll(clusterManagerNodes.keys());
                            }
                        } else if (DiscoveryNodeRole.INGEST_ROLE.roleName().equals(matchAttrName)) {
                            if (Booleans.parseBoolean(matchAttrValue, true)) {
                                resolvedNodesIds.addAll(ingestNodes.keys());
                            } else {
                                resolvedNodesIds.removeAll(ingestNodes.keys());
                            }
                        } else if (ProtobufDiscoveryNode.COORDINATING_ONLY.equals(matchAttrName)) {
                            if (Booleans.parseBoolean(matchAttrValue, true)) {
                                resolvedNodesIds.addAll(getCoordinatingOnlyNodes().keys());
                            } else {
                                resolvedNodesIds.removeAll(getCoordinatingOnlyNodes().keys());
                            }
                        } else {
                            for (ProtobufDiscoveryNode node : this) {
                                for (DiscoveryNodeRole role : Sets.difference(node.getRoles(), DiscoveryNodeRole.BUILT_IN_ROLES)) {
                                    if (role.roleName().equals(matchAttrName)) {
                                        if (Booleans.parseBoolean(matchAttrValue, true)) {
                                            resolvedNodesIds.add(node.getId());
                                        } else {
                                            resolvedNodesIds.remove(node.getId());
                                        }
                                    }
                                }
                            }
                            for (ProtobufDiscoveryNode node : this) {
                                for (Map.Entry<String, String> entry : node.getAttributes().entrySet()) {
                                    String attrName = entry.getKey();
                                    String attrValue = entry.getValue();
                                    if (Regex.simpleMatch(matchAttrName, attrName) && Regex.simpleMatch(matchAttrValue, attrValue)) {
                                        resolvedNodesIds.add(node.getId());
                                    }
                                }
                            }
                        }
                    }
                }
            }
            return resolvedNodesIds.toArray(String.class);
        }
    }

    public ProtobufDiscoveryNodes newNode(ProtobufDiscoveryNode node) {
        return new Builder(this).add(node).build();
    }

    /**
     * Returns the changes comparing this nodes to the provided nodes.
    */
    public Delta delta(ProtobufDiscoveryNodes other) {
        final List<ProtobufDiscoveryNode> removed = new ArrayList<>();
        final List<ProtobufDiscoveryNode> added = new ArrayList<>();
        for (ProtobufDiscoveryNode node : other) {
            if (this.nodeExists(node) == false) {
                removed.add(node);
            }
        }
        for (ProtobufDiscoveryNode node : this) {
            if (other.nodeExists(node) == false) {
                added.add(node);
            }
        }

        return new Delta(
            other.getClusterManagerNode(),
            getClusterManagerNode(),
            localNodeId,
            Collections.unmodifiableList(removed),
            Collections.unmodifiableList(added)
        );
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("nodes: \n");
        for (ProtobufDiscoveryNode node : this) {
            sb.append("   ").append(node);
            if (node == getLocalNode()) {
                sb.append(", local");
            }
            if (node == getClusterManagerNode()) {
                sb.append(", cluster-manager");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    /**
     * Delta between nodes.
    *
    * @opensearch.internal
    */
    public static class Delta {

        private final String localNodeId;
        @Nullable
        private final ProtobufDiscoveryNode previousClusterManagerNode;
        @Nullable
        private final ProtobufDiscoveryNode newClusterManagerNode;
        private final List<ProtobufDiscoveryNode> removed;
        private final List<ProtobufDiscoveryNode> added;

        private Delta(
            @Nullable ProtobufDiscoveryNode previousClusterManagerNode,
            @Nullable ProtobufDiscoveryNode newClusterManagerNode,
            String localNodeId,
            List<ProtobufDiscoveryNode> removed,
            List<ProtobufDiscoveryNode> added
        ) {
            this.previousClusterManagerNode = previousClusterManagerNode;
            this.newClusterManagerNode = newClusterManagerNode;
            this.localNodeId = localNodeId;
            this.removed = removed;
            this.added = added;
        }

        public boolean hasChanges() {
            return clusterManagerNodeChanged() || !removed.isEmpty() || !added.isEmpty();
        }

        public boolean clusterManagerNodeChanged() {
            return Objects.equals(newClusterManagerNode, previousClusterManagerNode) == false;
        }

        /** @deprecated As of 2.2, because supporting inclusive language, replaced by {@link #clusterManagerNodeChanged()} */
        @Deprecated
        public boolean masterNodeChanged() {
            return clusterManagerNodeChanged();
        }

        @Nullable
        public ProtobufDiscoveryNode previousClusterManagerNode() {
            return previousClusterManagerNode;
        }

        /** @deprecated As of 2.2, because supporting inclusive language, replaced by {@link #previousClusterManagerNode()} */
        @Deprecated
        @Nullable
        public ProtobufDiscoveryNode previousMasterNode() {
            return previousClusterManagerNode();
        }

        @Nullable
        public ProtobufDiscoveryNode newClusterManagerNode() {
            return newClusterManagerNode;
        }

        /** @deprecated As of 2.2, because supporting inclusive language, replaced by {@link #newClusterManagerNode()} */
        @Deprecated
        @Nullable
        public ProtobufDiscoveryNode newMasterNode() {
            return newClusterManagerNode();
        }

        public boolean removed() {
            return !removed.isEmpty();
        }

        public List<ProtobufDiscoveryNode> removedNodes() {
            return removed;
        }

        public boolean added() {
            return !added.isEmpty();
        }

        public List<ProtobufDiscoveryNode> addedNodes() {
            return added;
        }

        public String shortSummary() {
            final StringBuilder summary = new StringBuilder();
            if (clusterManagerNodeChanged()) {
                summary.append("cluster-manager node changed {previous [");
                if (previousClusterManagerNode() != null) {
                    summary.append(previousClusterManagerNode());
                }
                summary.append("], current [");
                if (newClusterManagerNode() != null) {
                    summary.append(newClusterManagerNode());
                }
                summary.append("]}");
            }
            if (removed()) {
                if (summary.length() > 0) {
                    summary.append(", ");
                }
                summary.append("removed {").append(Strings.collectionToCommaDelimitedString(removedNodes())).append('}');
            }
            if (added()) {
                final String addedNodesExceptLocalNode = addedNodes().stream()
                    .filter(node -> node.getId().equals(localNodeId) == false)
                    .map(ProtobufDiscoveryNode::toString)
                    .collect(Collectors.joining(","));
                if (addedNodesExceptLocalNode.length() > 0) {
                    // ignore ourselves when reporting on nodes being added
                    if (summary.length() > 0) {
                        summary.append(", ");
                    }
                    summary.append("added {").append(addedNodesExceptLocalNode).append('}');
                }
            }
            return summary.toString();
        }
    }

    @Override
    public void writeTo(CodedOutputStream out) throws IOException {
        if (clusterManagerNodeId == null) {
            out.writeBoolNoTag(false);
        } else {
            out.writeBoolNoTag(true);
            out.writeStringNoTag(clusterManagerNodeId);
        }
        out.writeInt32NoTag(nodes.size());
        for (ProtobufDiscoveryNode node : this) {
            node.writeTo(out);
        }
    }

    public static ProtobufDiscoveryNodes readFrom(CodedInputStream in, ProtobufDiscoveryNode localNode) throws IOException {
        Builder builder = new Builder();
        if (in.readBool()) {
            builder.clusterManagerNodeId(in.readString());
        }
        if (localNode != null) {
            builder.localNodeId(localNode.getId());
        }
        int size = in.readInt32();
        for (int i = 0; i < size; i++) {
            ProtobufDiscoveryNode node = new ProtobufDiscoveryNode(in);
            if (localNode != null && node.getId().equals(localNode.getId())) {
                // reuse the same instance of our address and local node id for faster equality
                node = localNode;
            }
            // some one already built this and validated it's OK, skip the n2 scans
            assert builder.validateAdd(node) == null : "building disco nodes from network doesn't pass preflight: "
                + builder.validateAdd(node);
            builder.putUnsafe(node);
        }
        return builder.build();
    }

    public static ProtobufDiff<ProtobufDiscoveryNodes> readDiffFrom(CodedInputStream in, ProtobufDiscoveryNode localNode)
        throws IOException {
        return ProtobufAbstractDiffable.readDiffFrom(in1 -> readFrom(in1, localNode), in);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static Builder builder(ProtobufDiscoveryNodes nodes) {
        return new Builder(nodes);
    }

    /**
     * Builder of a map of discovery nodes.
    *
    * @opensearch.internal
    */
    public static class Builder {

        private final ImmutableOpenMap.Builder<String, ProtobufDiscoveryNode> nodes;
        private String clusterManagerNodeId;
        private String localNodeId;

        public Builder() {
            nodes = ImmutableOpenMap.builder();
        }

        public Builder(ProtobufDiscoveryNodes nodes) {
            this.clusterManagerNodeId = nodes.getClusterManagerNodeId();
            this.localNodeId = nodes.getLocalNodeId();
            this.nodes = ImmutableOpenMap.builder(nodes.getNodes());
        }

        /**
         * adds a disco node to the builder. Will throw an {@link IllegalArgumentException} if
        * the supplied node doesn't pass the pre-flight checks performed by {@link #validateAdd(ProtobufDiscoveryNode)}
        */
        public Builder add(ProtobufDiscoveryNode node) {
            final String preflight = validateAdd(node);
            if (preflight != null) {
                throw new IllegalArgumentException(preflight);
            }
            putUnsafe(node);
            return this;
        }

        /**
         * Get a node by its id
        *
        * @param nodeId id of the wanted node
        * @return wanted node if it exists. Otherwise <code>null</code>
        */
        @Nullable
        public ProtobufDiscoveryNode get(String nodeId) {
            return nodes.get(nodeId);
        }

        private void putUnsafe(ProtobufDiscoveryNode node) {
            nodes.put(node.getId(), node);
        }

        public Builder remove(String nodeId) {
            nodes.remove(nodeId);
            return this;
        }

        public Builder remove(ProtobufDiscoveryNode node) {
            if (node.equals(nodes.get(node.getId()))) {
                nodes.remove(node.getId());
            }
            return this;
        }

        public Builder clusterManagerNodeId(String clusterManagerNodeId) {
            this.clusterManagerNodeId = clusterManagerNodeId;
            return this;
        }

        /** @deprecated As of 2.2, because supporting inclusive language, replaced by {@link #clusterManagerNodeId} */
        @Deprecated
        public Builder masterNodeId(String clusterManagerNodeId) {
            return clusterManagerNodeId(clusterManagerNodeId);
        }

        public Builder localNodeId(String localNodeId) {
            this.localNodeId = localNodeId;
            return this;
        }

        /**
         * Checks that a node can be safely added to this node collection.
        *
        * @return null if all is OK or an error message explaining why a node can not be added.
        *
        * Note: if this method returns a non-null value, calling {@link #add(ProtobufDiscoveryNode)} will fail with an
        * exception
        */
        private String validateAdd(ProtobufDiscoveryNode node) {
            for (ObjectCursor<ProtobufDiscoveryNode> cursor : nodes.values()) {
                final ProtobufDiscoveryNode existingNode = cursor.value;
                if (node.getAddress().equals(existingNode.getAddress()) && node.getId().equals(existingNode.getId()) == false) {
                    return "can't add node " + node + ", found existing node " + existingNode + " with same address";
                }
                if (node.getId().equals(existingNode.getId()) && node.equals(existingNode) == false) {
                    return "can't add node "
                        + node
                        + ", found existing node "
                        + existingNode
                        + " with the same id but is a different node instance";
                }
            }
            return null;
        }

        public ProtobufDiscoveryNodes build() {
            ImmutableOpenMap.Builder<String, ProtobufDiscoveryNode> dataNodesBuilder = ImmutableOpenMap.builder();
            ImmutableOpenMap.Builder<String, ProtobufDiscoveryNode> clusterManagerNodesBuilder = ImmutableOpenMap.builder();
            ImmutableOpenMap.Builder<String, ProtobufDiscoveryNode> ingestNodesBuilder = ImmutableOpenMap.builder();
            Version minNodeVersion = null;
            Version maxNodeVersion = null;
            Version minNonClientNodeVersion = null;
            Version maxNonClientNodeVersion = null;
            for (ObjectObjectCursor<String, ProtobufDiscoveryNode> nodeEntry : nodes) {
                if (nodeEntry.value.isDataNode()) {
                    dataNodesBuilder.put(nodeEntry.key, nodeEntry.value);
                }
                if (nodeEntry.value.isClusterManagerNode()) {
                    clusterManagerNodesBuilder.put(nodeEntry.key, nodeEntry.value);
                }
                final Version version = nodeEntry.value.getVersion();
                if (nodeEntry.value.isDataNode() || nodeEntry.value.isClusterManagerNode()) {
                    if (minNonClientNodeVersion == null) {
                        minNonClientNodeVersion = version;
                        maxNonClientNodeVersion = version;
                    } else {
                        minNonClientNodeVersion = Version.min(minNonClientNodeVersion, version);
                        maxNonClientNodeVersion = Version.max(maxNonClientNodeVersion, version);
                    }
                }
                if (nodeEntry.value.isIngestNode()) {
                    ingestNodesBuilder.put(nodeEntry.key, nodeEntry.value);
                }
                minNodeVersion = minNodeVersion == null ? version : Version.min(minNodeVersion, version);
                maxNodeVersion = maxNodeVersion == null ? version : Version.max(maxNodeVersion, version);
            }

            return new ProtobufDiscoveryNodes(
                nodes.build(),
                dataNodesBuilder.build(),
                clusterManagerNodesBuilder.build(),
                ingestNodesBuilder.build(),
                clusterManagerNodeId,
                localNodeId,
                minNonClientNodeVersion == null ? Version.CURRENT : minNonClientNodeVersion,
                maxNonClientNodeVersion == null ? Version.CURRENT : maxNonClientNodeVersion,
                maxNodeVersion == null ? Version.CURRENT : maxNodeVersion,
                minNodeVersion == null ? Version.CURRENT : minNodeVersion
            );
        }

        public boolean isLocalNodeElectedClusterManager() {
            return clusterManagerNodeId != null && clusterManagerNodeId.equals(localNodeId);
        }

        /** @deprecated As of 2.2, because supporting inclusive language, replaced by {@link #isLocalNodeElectedClusterManager()} */
        @Deprecated
        public boolean isLocalNodeElectedMaster() {
            return isLocalNodeElectedClusterManager();
        }
    }

    /**
     * Check if the given name of the node role is 'cluster_manager' or 'master'.
    * The method is added for {@link #resolveNodes} to keep the code clear, when support the both above roles.
    * @deprecated As of 2.0, because promoting inclusive language. MASTER_ROLE is deprecated.
    * @param matchAttrName a given String for a name of the node role.
    * @return true if the given roleName is 'cluster_manger' or 'master'
    */
    @Deprecated
    private boolean roleNameIsClusterManager(String matchAttrName) {
        return DiscoveryNodeRole.MASTER_ROLE.roleName().equals(matchAttrName)
            || DiscoveryNodeRole.CLUSTER_MANAGER_ROLE.roleName().equals(matchAttrName);
    }
}
