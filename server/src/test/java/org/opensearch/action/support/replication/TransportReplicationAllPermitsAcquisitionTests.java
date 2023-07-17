/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
/*
 * Modifications Copyright OpenSearch Contributors. See
 * GitHub history for details.
 */

package org.opensearch.action.support.replication;

import org.opensearch.Version;
import org.opensearch.action.ActionListener;
import org.opensearch.action.support.ActionFilters;
import org.opensearch.action.support.PlainActionFuture;
import org.opensearch.action.support.replication.TransportReplicationAction.ReplicaResponse;
import org.opensearch.cluster.ClusterState;
import org.opensearch.cluster.action.shard.ShardStateAction;
import org.opensearch.cluster.block.ClusterBlock;
import org.opensearch.cluster.block.ClusterBlockException;
import org.opensearch.cluster.block.ClusterBlockLevel;
import org.opensearch.cluster.block.ClusterBlocks;
import org.opensearch.cluster.metadata.IndexMetadata;
import org.opensearch.cluster.metadata.Metadata;
import org.opensearch.cluster.node.DiscoveryNode;
import org.opensearch.cluster.node.DiscoveryNodeRole;
import org.opensearch.cluster.node.DiscoveryNodes;
import org.opensearch.cluster.routing.IndexRoutingTable;
import org.opensearch.cluster.routing.IndexShardRoutingTable;
import org.opensearch.cluster.routing.RecoverySource;
import org.opensearch.cluster.routing.RoutingTable;
import org.opensearch.cluster.routing.ShardRouting;
import org.opensearch.cluster.routing.ShardRoutingState;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.common.SetOnce;
import org.opensearch.core.common.io.stream.StreamInput;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.unit.TimeValue;
import org.opensearch.common.lease.Releasable;
import org.opensearch.index.IndexService;
import org.opensearch.index.engine.InternalEngineFactory;
import org.opensearch.index.shard.IndexShard;
import org.opensearch.index.shard.IndexShardTestCase;
import org.opensearch.core.index.shard.ShardId;
import org.opensearch.indices.IndicesService;
import org.opensearch.core.rest.RestStatus;
import org.opensearch.test.transport.MockTransport;
import org.opensearch.threadpool.ThreadPool;
import org.opensearch.transport.ProtobufTransportException;
import org.opensearch.transport.TransportException;
import org.opensearch.transport.TransportMessageListener;
import org.opensearch.transport.TransportRequest;
import org.opensearch.transport.TransportResponseHandler;
import org.opensearch.transport.TransportService;

import com.google.protobuf.CodedInputStream;

import org.junit.After;
import org.junit.Before;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;

import static java.util.Collections.emptyMap;
import static java.util.Collections.emptySet;
import static org.opensearch.cluster.metadata.IndexMetadata.SETTING_CREATION_DATE;
import static org.opensearch.cluster.metadata.IndexMetadata.SETTING_INDEX_UUID;
import static org.opensearch.cluster.metadata.IndexMetadata.SETTING_NUMBER_OF_REPLICAS;
import static org.opensearch.cluster.metadata.IndexMetadata.SETTING_NUMBER_OF_SHARDS;
import static org.opensearch.cluster.metadata.IndexMetadata.SETTING_VERSION_CREATED;
import static org.opensearch.cluster.routing.TestShardRouting.newShardRouting;
import static org.opensearch.test.ClusterServiceUtils.createClusterService;
import static org.opensearch.test.ClusterServiceUtils.setState;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * This test tests the concurrent execution of several transport replication actions. All of these actions (except one) acquire a single
 * permit during their execution on shards and are expected to fail if a global level or index level block is present in the cluster state.
 * These actions are all started at the same time, but some are delayed until one last action.
 *
 * This last action is special because it acquires all the permits on shards, adds the block to the cluster state and then "releases" the
 * previously delayed single permit actions. This way, there is a clear transition between the single permit actions executed before the
 * all permit action that sets the block and those executed afterwards that are doomed to fail because of the block.
 */
public class TransportReplicationAllPermitsAcquisitionTests extends IndexShardTestCase {

    private ClusterService clusterService;
    private TransportService transportService;
    private ShardStateAction shardStateAction;
    private ShardId shardId;
    private IndexShard primary;
    private IndexShard replica;
    private boolean globalBlock;
    private ClusterBlock block;

    @Override
    @Before
    public void setUp() throws Exception {
        super.setUp();
        globalBlock = randomBoolean();
        RestStatus restStatus = randomFrom(RestStatus.values());
        block = new ClusterBlock(randomIntBetween(1, 10), randomAlphaOfLength(5), false, true, false, restStatus, ClusterBlockLevel.ALL);
        clusterService = createClusterService(threadPool);

        final ClusterState.Builder state = ClusterState.builder(clusterService.state());
        Set<DiscoveryNodeRole> roles = new HashSet<>(DiscoveryNodeRole.BUILT_IN_ROLES);
        DiscoveryNode node1 = new DiscoveryNode("_name1", "_node1", buildNewFakeTransportAddress(), emptyMap(), roles, Version.CURRENT);
        DiscoveryNode node2 = new DiscoveryNode("_name2", "_node2", buildNewFakeTransportAddress(), emptyMap(), roles, Version.CURRENT);
        state.nodes(DiscoveryNodes.builder().add(node1).add(node2).localNodeId(node1.getId()).clusterManagerNodeId(node1.getId()));

        shardId = new ShardId("index", UUID.randomUUID().toString(), 0);
        ShardRouting shardRouting = newShardRouting(
            shardId,
            node1.getId(),
            true,
            ShardRoutingState.INITIALIZING,
            RecoverySource.EmptyStoreRecoverySource.INSTANCE
        );

        Settings indexSettings = Settings.builder()
            .put(SETTING_VERSION_CREATED, Version.CURRENT)
            .put(SETTING_INDEX_UUID, shardId.getIndex().getUUID())
            .put(SETTING_NUMBER_OF_SHARDS, 1)
            .put(SETTING_NUMBER_OF_REPLICAS, 1)
            .put(SETTING_CREATION_DATE, System.currentTimeMillis())
            .build();

        primary = newStartedShard(p -> newShard(shardRouting, indexSettings, new InternalEngineFactory()), true);
        for (int i = 0; i < 10; i++) {
            final String id = Integer.toString(i);
            indexDoc(primary, "_doc", id, "{\"value\":" + id + "}");
        }

        IndexMetadata indexMetadata = IndexMetadata.builder(shardId.getIndexName())
            .settings(indexSettings)
            .primaryTerm(shardId.id(), primary.getOperationPrimaryTerm())
            .putMapping("{ \"properties\": { \"value\":  { \"type\": \"short\"}}}")
            .build();
        state.metadata(Metadata.builder().put(indexMetadata, false).generateClusterUuidIfNeeded());

        replica = newShard(primary.shardId(), false, node2.getId(), indexMetadata, null);
        recoverReplica(replica, primary, true);

        IndexRoutingTable.Builder routing = IndexRoutingTable.builder(indexMetadata.getIndex());
        routing.addIndexShard(new IndexShardRoutingTable.Builder(shardId).addShard(primary.routingEntry()).build());
        state.routingTable(RoutingTable.builder().add(routing.build()).build());

        setState(clusterService, state.build());

        final Settings transportSettings = Settings.builder().put("node.name", node1.getId()).build();

        MockTransport transport = new MockTransport() {
            @Override
            protected void onSendRequest(long requestId, String action, TransportRequest request, DiscoveryNode node) {
                assertThat(action, allOf(startsWith("cluster:admin/test/"), endsWith("[r]")));
                assertThat(node, equalTo(node2));
                // node2 doesn't really exist, but we are performing some trickery in mockIndicesService() to pretend that node1 holds both
                // the primary and the replica, so redirect the request back to node1.
                transportService.sendRequest(
                    transportService.getLocalNode(),
                    action,
                    request,
                    new TransportResponseHandler<TransportReplicationAction.ReplicaResponse>() {
                        @Override
                        public TransportReplicationAction.ReplicaResponse read(StreamInput in) throws IOException {
                            return new TransportReplicationAction.ReplicaResponse(in);
                        }

                        @SuppressWarnings("unchecked")
                        private TransportResponseHandler<TransportReplicationAction.ReplicaResponse> getResponseHandler() {
                            return (TransportResponseHandler<TransportReplicationAction.ReplicaResponse>) getResponseHandlers()
                                .onResponseReceived(requestId, TransportMessageListener.NOOP_LISTENER);
                        }

                        @Override
                        public void handleResponse(TransportReplicationAction.ReplicaResponse response) {
                            getResponseHandler().handleResponse(response);
                        }

                        @Override
                        public void handleException(TransportException exp) {
                            getResponseHandler().handleException(exp);
                        }

                        @Override
                        public String executor() {
                            return ThreadPool.Names.SAME;
                        }

                        @Override
                        public ReplicaResponse read(CodedInputStream in) throws IOException {
                            // TODO Auto-generated method stub
                            throw new UnsupportedOperationException("Unimplemented method 'read'");
                        }

                        @Override
                        public void handleExceptionProtobuf(ProtobufTransportException exp) {
                            // TODO Auto-generated method stub
                            throw new UnsupportedOperationException("Unimplemented method 'handleExceptionProtobuf'");
                        }
                    }
                );
            }
        };
        transportService = transport.createTransportService(
            transportSettings,
            threadPool,
            TransportService.NOOP_TRANSPORT_INTERCEPTOR,
            bta -> node1,
            null,
            emptySet()
        );
        transportService.start();
        transportService.acceptIncomingRequests();
        shardStateAction = new ShardStateAction(clusterService, transportService, null, null, threadPool);
    }

    @Override
    @After
    public void tearDown() throws Exception {
        closeShards(primary, replica);
        transportService.stop();
        clusterService.close();
        super.tearDown();
    }

    public void testTransportReplicationActionWithAllPermits() throws Exception {
        final int numOperations = scaledRandomIntBetween(4, 32);
        final int delayedOperations = randomIntBetween(1, numOperations);
        logger.trace(
            "starting [{}] operations, among which the first [{}] started ops should be blocked by [{}]",
            numOperations,
            delayedOperations,
            block
        );

        final CyclicBarrier delayedOperationsBarrier = new CyclicBarrier(delayedOperations + 1);
        final List<Thread> threads = new ArrayList<>(delayedOperationsBarrier.getParties());

        @SuppressWarnings("unchecked")
        final PlainActionFuture<Response>[] futures = new PlainActionFuture[numOperations];
        final TestAction[] actions = new TestAction[numOperations];

        for (int i = 0; i < numOperations; i++) {
            final int threadId = i;
            final boolean delayed = (threadId < delayedOperations);

            final PlainActionFuture<Response> listener = new PlainActionFuture<>();
            futures[threadId] = listener;

            final TestAction singlePermitAction = new SinglePermitWithBlocksAction(
                Settings.EMPTY,
                "cluster:admin/test/single_permit[" + threadId + "]",
                transportService,
                clusterService,
                shardStateAction,
                threadPool,
                shardId,
                primary,
                replica,
                globalBlock
            );
            actions[threadId] = singlePermitAction;

            Thread thread = new Thread(() -> {
                final TransportReplicationAction.ConcreteShardRequest<Request> primaryRequest =
                    new TransportReplicationAction.ConcreteShardRequest<>(request(), allocationId(), primaryTerm());
                TransportReplicationAction.AsyncPrimaryAction asyncPrimaryAction = singlePermitAction.new AsyncPrimaryAction(
                    primaryRequest, listener, null
                ) {
                    @Override
                    protected void doRun() throws Exception {
                        if (delayed) {
                            logger.trace("op [{}] has started and will resume execution once allPermitsAction is terminated", threadId);
                            delayedOperationsBarrier.await();
                        }
                        super.doRun();
                    }

                    @Override
                    void runWithPrimaryShardReference(final TransportReplicationAction.PrimaryShardReference reference) {
                        assertThat(reference.indexShard.getActiveOperationsCount(), greaterThan(0));
                        assertSame(primary, reference.indexShard);
                        assertBlockIsPresentForDelayedOp();
                        super.runWithPrimaryShardReference(reference);
                    }

                    @Override
                    public void onFailure(Exception e) {
                        assertBlockIsPresentForDelayedOp();
                        super.onFailure(e);
                    }

                    private void assertBlockIsPresentForDelayedOp() {
                        if (delayed) {
                            final ClusterState clusterState = clusterService.state();
                            if (globalBlock) {
                                assertTrue("Global block must exist", clusterState.blocks().hasGlobalBlock(block));
                            } else {
                                String indexName = primary.shardId().getIndexName();
                                assertTrue("Index block must exist", clusterState.blocks().hasIndexBlock(indexName, block));
                            }
                        }
                    }
                };
                asyncPrimaryAction.run();
            });
            threads.add(thread);
            thread.start();
        }

        logger.trace("now starting the operation that acquires all permits and sets the block in the cluster state");

        // An action which acquires all operation permits during execution and set a block
        final TestAction allPermitsAction = new AllPermitsThenBlockAction(
            Settings.EMPTY,
            "cluster:admin/test/all_permits",
            transportService,
            clusterService,
            shardStateAction,
            threadPool,
            shardId,
            primary,
            replica
        );

        final PlainActionFuture<Response> allPermitFuture = new PlainActionFuture<>();
        Thread thread = new Thread(() -> {
            final TransportReplicationAction.ConcreteShardRequest<Request> primaryRequest =
                new TransportReplicationAction.ConcreteShardRequest<>(request(), allocationId(), primaryTerm());
            TransportReplicationAction.AsyncPrimaryAction asyncPrimaryAction = allPermitsAction.new AsyncPrimaryAction(
                primaryRequest, allPermitFuture, null
            ) {
                @Override
                void runWithPrimaryShardReference(final TransportReplicationAction.PrimaryShardReference reference) {
                    assertEquals(
                        "All permits must be acquired",
                        IndexShard.OPERATIONS_BLOCKED,
                        reference.indexShard.getActiveOperationsCount()
                    );
                    assertSame(primary, reference.indexShard);

                    final ClusterState clusterState = clusterService.state();
                    final ClusterBlocks.Builder blocks = ClusterBlocks.builder();
                    if (globalBlock) {
                        assertFalse("Global block must not exist yet", clusterState.blocks().hasGlobalBlock(block));
                        blocks.addGlobalBlock(block);
                    } else {
                        String indexName = reference.indexShard.shardId().getIndexName();
                        assertFalse("Index block must not exist yet", clusterState.blocks().hasIndexBlock(indexName, block));
                        blocks.addIndexBlock(indexName, block);
                    }

                    logger.trace("adding test block to cluster state {}", block);
                    setState(clusterService, ClusterState.builder(clusterState).blocks(blocks));

                    try {
                        logger.trace("releasing delayed operations");
                        delayedOperationsBarrier.await();
                    } catch (InterruptedException | BrokenBarrierException e) {
                        onFailure(e);
                    }
                    super.runWithPrimaryShardReference(reference);
                }
            };
            asyncPrimaryAction.run();
        });
        threads.add(thread);
        thread.start();

        logger.trace("waiting for all operations to terminate");
        for (Thread t : threads) {
            t.join();
        }

        final Response allPermitsResponse = allPermitFuture.get();

        assertSuccessfulOperation(allPermitsAction, allPermitsResponse);

        for (int i = 0; i < numOperations; i++) {
            final PlainActionFuture<Response> future = futures[i];
            final TestAction action = actions[i];

            if (i < delayedOperations) {
                ExecutionException exception = expectThrows(ExecutionException.class, "delayed operation should have failed", future::get);
                assertFailedOperation(action, exception);
            } else {
                // non delayed operation might fail depending on the order they were executed
                try {
                    assertSuccessfulOperation(action, futures[i].get());
                } catch (final ExecutionException e) {
                    assertFailedOperation(action, e);
                }
            }
        }
    }

    private void assertSuccessfulOperation(final TestAction action, final Response response) {
        final String name = action.getActionName();
        assertThat(name + " operation should have been executed on primary", action.executedOnPrimary.get(), is(true));
        assertThat(name + " operation should have been executed on replica", action.executedOnReplica.get(), is(true));
        assertThat(name + " operation must have a non null result", response, notNullValue());
        assertThat(name + " operation should have been successful on 2 shards", response.getShardInfo().getSuccessful(), equalTo(2));
    }

    private void assertFailedOperation(final TestAction action, final ExecutionException exception) {
        final String name = action.getActionName();
        assertThat(name + " operation should not have been executed on primary", action.executedOnPrimary.get(), nullValue());
        assertThat(name + " operation should not have been executed on replica", action.executedOnReplica.get(), nullValue());
        assertThat(exception.getCause(), instanceOf(ClusterBlockException.class));
        ClusterBlockException clusterBlockException = (ClusterBlockException) exception.getCause();
        assertThat(clusterBlockException.blocks(), hasItem(equalTo(block)));
    }

    private long primaryTerm() {
        return primary.getOperationPrimaryTerm();
    }

    private String allocationId() {
        return primary.routingEntry().allocationId().getId();
    }

    private Request request() {
        return new Request(primary.shardId());
    }

    /**
     * A type of {@link TransportReplicationAction} that allows to use the primary and replica shards passed to the constructor for the
     * execution of the replication action. Also records if the operation is executed on the primary and the replica.
     */
    private abstract class TestAction extends TransportReplicationAction<Request, Request, Response> {

        protected final ShardId shardId;
        protected final IndexShard primary;
        protected final IndexShard replica;
        final SetOnce<Boolean> executedOnPrimary;
        final SetOnce<Boolean> executedOnReplica = new SetOnce<>();

        TestAction(
            Settings settings,
            String actionName,
            TransportService transportService,
            ClusterService clusterService,
            ShardStateAction shardStateAction,
            ThreadPool threadPool,
            ShardId shardId,
            IndexShard primary,
            IndexShard replica,
            SetOnce<Boolean> executedOnPrimary
        ) {
            super(
                settings,
                actionName,
                transportService,
                clusterService,
                mockIndicesService(shardId, executedOnPrimary, primary, replica),
                threadPool,
                shardStateAction,
                new ActionFilters(new HashSet<>()),
                Request::new,
                Request::new,
                ThreadPool.Names.SAME
            );
            this.shardId = Objects.requireNonNull(shardId);
            this.primary = Objects.requireNonNull(primary);
            assertEquals(shardId, primary.shardId());
            this.replica = Objects.requireNonNull(replica);
            assertEquals(shardId, replica.shardId());
            this.executedOnPrimary = executedOnPrimary;
        }

        @Override
        protected Response newResponseInstance(StreamInput in) {
            return new Response();
        }

        public String getActionName() {
            return this.actionName;
        }

        @Override
        protected void shardOperationOnPrimary(
            Request shardRequest,
            IndexShard shard,
            ActionListener<PrimaryResult<Request, Response>> listener
        ) {
            executedOnPrimary.set(true);
            // The TransportReplicationAction.getIndexShard() method is overridden for testing purpose but we double check here
            // that the permit has been acquired on the primary shard
            assertSame(primary, shard);
            listener.onResponse(new PrimaryResult<>(shardRequest, new Response()));
        }

        @Override
        protected void shardOperationOnReplica(Request shardRequest, IndexShard replica, ActionListener<ReplicaResult> listener) {
            assertEquals(
                "Replica is always assigned to node 2 in this test",
                clusterService.state().nodes().get("_node2").getId(),
                replica.routingEntry().currentNodeId()
            );
            executedOnReplica.set(true);
            // The TransportReplicationAction.getIndexShard() method is overridden for testing purpose but we double check here
            // that the permit has been acquired on the replica shard
            assertSame(replica, replica);
            listener.onResponse(new ReplicaResult());
        }
    }

    private static IndicesService mockIndicesService(
        ShardId shardId,
        SetOnce<Boolean> executedOnPrimary,
        IndexShard primary,
        IndexShard replica
    ) {
        final IndexService indexService = mock(IndexService.class);
        when(indexService.getShard(shardId.id())).then(invocation -> (executedOnPrimary.get() == null) ? primary : replica);

        final IndicesService indicesService = mock(IndicesService.class);
        when(indicesService.indexServiceSafe(shardId.getIndex())).then(invocation -> indexService);

        return indicesService;
    }

    /**
     * A type of {@link TransportReplicationAction} that acquires a single permit during execution and that blocks
     * on {@link ClusterBlockLevel#WRITE}. The block can be a global level or an index level block depending of the
     * value of the {@code globalBlock} parameter in the constructor. When the operation is executed on shards it
     * verifies that at least 1 permit is acquired and that there is no blocks in the cluster state.
     */
    private class SinglePermitWithBlocksAction extends TestAction {

        private final boolean globalBlock;

        SinglePermitWithBlocksAction(
            Settings settings,
            String actionName,
            TransportService transportService,
            ClusterService clusterService,
            ShardStateAction shardStateAction,
            ThreadPool threadPool,
            ShardId shardId,
            IndexShard primary,
            IndexShard replica,
            boolean globalBlock
        ) {
            super(
                settings,
                actionName,
                transportService,
                clusterService,
                shardStateAction,
                threadPool,
                shardId,
                primary,
                replica,
                new SetOnce<>()
            );
            this.globalBlock = globalBlock;
        }

        @Override
        protected ClusterBlockLevel globalBlockLevel() {
            return globalBlock ? ClusterBlockLevel.WRITE : super.globalBlockLevel();
        }

        @Override
        public ClusterBlockLevel indexBlockLevel() {
            return globalBlock == false ? ClusterBlockLevel.WRITE : super.indexBlockLevel();
        }

        @Override
        protected void shardOperationOnPrimary(
            Request shardRequest,
            IndexShard shard,
            ActionListener<PrimaryResult<Request, Response>> listener
        ) {
            assertNoBlocks("block must not exist when executing the operation on primary shard: it should have been blocked before");
            assertThat(shard.getActiveOperationsCount(), greaterThan(0));
            super.shardOperationOnPrimary(shardRequest, shard, listener);
        }

        @Override
        protected void shardOperationOnReplica(Request shardRequest, IndexShard replica, ActionListener<ReplicaResult> listener) {
            assertNoBlocks("block must not exist when executing the operation on replica shard: it should have been blocked before");
            assertThat(replica.getActiveOperationsCount(), greaterThan(0));
            super.shardOperationOnReplica(shardRequest, replica, listener);
        }

        private void assertNoBlocks(final String error) {
            final ClusterState clusterState = clusterService.state();
            assertFalse("Global level " + error, clusterState.blocks().hasGlobalBlock(block));
            assertFalse("Index level " + error, clusterState.blocks().hasIndexBlock(shardId.getIndexName(), block));
        }
    }

    /**
     * A type of {@link TransportReplicationAction} that acquires all permits during execution.
     */
    private class AllPermitsThenBlockAction extends TestAction {

        private final TimeValue timeout = TimeValue.timeValueSeconds(30L);

        AllPermitsThenBlockAction(
            Settings settings,
            String actionName,
            TransportService transportService,
            ClusterService clusterService,
            ShardStateAction shardStateAction,
            ThreadPool threadPool,
            ShardId shardId,
            IndexShard primary,
            IndexShard replica
        ) {
            super(
                settings,
                actionName,
                transportService,
                clusterService,
                shardStateAction,
                threadPool,
                shardId,
                primary,
                replica,
                new SetOnce<>()
            );
        }

        @Override
        protected void acquirePrimaryOperationPermit(IndexShard shard, Request request, ActionListener<Releasable> onAcquired) {
            shard.acquireAllPrimaryOperationsPermits(onAcquired, timeout);
        }

        @Override
        protected void acquireReplicaOperationPermit(
            IndexShard shard,
            Request request,
            ActionListener<Releasable> onAcquired,
            long primaryTerm,
            long globalCheckpoint,
            long maxSeqNo
        ) {
            shard.acquireAllReplicaOperationsPermits(primaryTerm, globalCheckpoint, maxSeqNo, onAcquired, timeout);
        }

        @Override
        protected void shardOperationOnPrimary(
            Request shardRequest,
            IndexShard shard,
            ActionListener<PrimaryResult<Request, Response>> listener
        ) {
            assertEquals("All permits must be acquired", IndexShard.OPERATIONS_BLOCKED, shard.getActiveOperationsCount());
            super.shardOperationOnPrimary(shardRequest, shard, listener);
        }

        @Override
        protected void shardOperationOnReplica(Request shardRequest, IndexShard replica, ActionListener<ReplicaResult> listener) {
            assertEquals("All permits must be acquired", IndexShard.OPERATIONS_BLOCKED, replica.getActiveOperationsCount());
            super.shardOperationOnReplica(shardRequest, replica, listener);
        }
    }

    static class Request extends ReplicationRequest<Request> {
        Request(StreamInput in) throws IOException {
            super(in);
        }

        Request(ShardId shardId) {
            super(shardId);
        }

        @Override
        public String toString() {
            return getTestClass().getName() + ".Request";
        }
    }

    static class Response extends ReplicationResponse {}
}
