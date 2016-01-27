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

package org.elasticsearch.discovery;

import org.codehaus.jackson.JsonGenerationException;
import org.codehaus.jackson.JsonParseException;
import org.codehaus.jackson.map.JsonMappingException;
import org.elasticsearch.ElasticsearchTimeoutException;
import org.elasticsearch.cluster.ClusterChangedEvent;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.block.ClusterBlock;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.routing.ShardRoutingState;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.component.AbstractLifecycleComponent;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;

import java.io.IOException;
import java.net.InetAddress;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 *
 */
public class DiscoveryService extends AbstractLifecycleComponent<DiscoveryService> {

    public static final String SETTING_INITIAL_STATE_TIMEOUT = "discovery.initial_state_timeout";
    public static final String SETTING_DISCOVERY_SEED = "discovery.id.seed";


    private static class InitialStateListener implements InitialStateDiscoveryListener {

        private final CountDownLatch latch = new CountDownLatch(1);
        private volatile boolean initialStateReceived;

        @Override
        public void initialStateProcessed() {
            initialStateReceived = true;
            latch.countDown();
        }

        public boolean waitForInitialState(TimeValue timeValue) throws InterruptedException {
            if (timeValue.millis() > 0) {
                latch.await(timeValue.millis(), TimeUnit.MILLISECONDS);
            }
            return initialStateReceived;
        }
    }

    private final TimeValue initialStateTimeout;
    private final Discovery discovery;
    private InitialStateListener initialStateListener;
    private final DiscoverySettings discoverySettings;

    @Inject
    public DiscoveryService(Settings settings, DiscoverySettings discoverySettings, Discovery discovery) {
        super(settings);
        this.discoverySettings = discoverySettings;
        this.discovery = discovery;
        this.initialStateTimeout = settings.getAsTime(SETTING_INITIAL_STATE_TIMEOUT, TimeValue.timeValueSeconds(30));
    }

    public ClusterBlock getNoMasterBlock() {
        return discoverySettings.getNoMasterBlock();
    }

    @Override
    protected void doStart() {
        initialStateListener = new InitialStateListener();
        discovery.addListener(initialStateListener);
        discovery.start();
        logger.info(discovery.nodeDescription());
    }

    public void waitForInitialState() {
        try {
            if (!initialStateListener.waitForInitialState(initialStateTimeout)) {
                logger.warn("waited for {} and no initial state was set by the discovery", initialStateTimeout);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ElasticsearchTimeoutException("Interrupted while waiting for initial discovery state");
        }
    }

    @Override
    protected void doStop() {
        if (initialStateListener != null) {
            discovery.removeListener(initialStateListener);
        }
        discovery.stop();
    }

    @Override
    protected void doClose() {
        discovery.close();
    }

    public DiscoveryNode localNode() {
        return discovery.localNode();
    }

    public String nodeDescription() {
        return discovery.nodeDescription();
    }

    /**
     * Publish all the changes to the cluster from the master (can be called just by the master). The publish
     * process should not publish this state to the master as well! (the master is sending it...).
     * <p/>
     * The {@link org.elasticsearch.discovery.Discovery.AckListener} allows to acknowledge the publish
     * event based on the response gotten from all nodes
     */
    public void publish(ClusterChangedEvent clusterChangedEvent, Discovery.AckListener ackListener) {
        if (lifecycle.started()) {
            discovery.publish(clusterChangedEvent, ackListener);
        }
    }

    public static String generateNodeId(Settings settings) {
        String seed = settings.get(DiscoveryService.SETTING_DISCOVERY_SEED);
        if (seed != null) {
            return Strings.randomBase64UUID(new Random(Long.parseLong(seed)));
        }
        return Strings.randomBase64UUID();
    }
    
    
    /**
     * Get indices shard state from gossip endpoints state map.
     * @param address
     * @param index
     * @return
     * @throws JsonParseException
     * @throws JsonMappingException
     * @throws IOException
     */
    public ShardRoutingState readIndexShardState(InetAddress address, String index, ShardRoutingState defaultState) {
        return this.discovery.readIndexShardState(address, index, defaultState);
    }

    
    /**
     * Set index shard state in the gossip endpoint map (must be synchronized).
     * @param index
     * @param shardRoutingState
     * @throws JsonGenerationException
     * @throws JsonMappingException
     * @throws IOException
     */
    public void writeIndexShardSate(String index, ShardRoutingState shardRoutingState) throws JsonGenerationException, JsonMappingException, IOException {
        this.discovery.writeIndexShardState(index, shardRoutingState);
    }

    public void removeIndexShardState(String index) throws JsonGenerationException, JsonMappingException, IOException {
        this.discovery.writeIndexShardState(index, null);
    }
    
    /**
     * Publish cluster metadata uuid and version in gossip state.
     * @param clusterState
     */
    public void publish(ClusterState clusterState) {
        this.discovery.publish(clusterState);
    }

    public boolean awaitMetaDataVersion(long version, TimeValue ackTimeout) throws Exception  {
        return this.discovery.awaitMetaDataVersion(version, ackTimeout);
    }
}
