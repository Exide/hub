package com.flightstats.hub.replication;

import com.flightstats.hub.app.HubProperties;
import com.flightstats.hub.app.HubServices;
import com.flightstats.hub.cluster.WatchManager;
import com.flightstats.hub.cluster.Watcher;
import com.flightstats.hub.dao.ChannelService;
import com.flightstats.hub.model.ChannelConfiguration;
import com.google.common.util.concurrent.AbstractIdleService;
import com.google.inject.Inject;
import com.google.inject.Provider;
import org.apache.curator.framework.api.CuratorEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Replication is moving from one Hub into another Hub
 * in Replication, we will presume we are moving forward in time, starting with configurable item age.
 * <p>
 * Secnario:
 * Producers are inserting Items into a Hub channel
 * The Hub is setup to Replicate a channel from a Hub
 * Replication starts at nearly the oldest Item, and gradually progresses forward to the current item
 * Replication stays up to date, with some minimal amount of lag
 */
public class ReplicatorImpl implements Replicator {
    private static final String REPLICATOR_WATCHER_PATH = "/replicator/watcher";
    private final static Logger logger = LoggerFactory.getLogger(ReplicatorImpl.class);

    private final ChannelService channelService;
    private final ChannelUtils channelUtils;
    private final Provider<V1ChannelReplicator> v1ReplicatorProvider;
    private final WatchManager watchManager;
    private final Map<String, ChannelReplicator> replicatorMap = new HashMap<>();
    private final AtomicBoolean stopped = new AtomicBoolean();

    @Inject
    public ReplicatorImpl(ChannelService channelService, ChannelUtils channelUtils,
                          Provider<V1ChannelReplicator> v1ReplicatorProvider, WatchManager watchManager) {
        this.channelService = channelService;
        this.channelUtils = channelUtils;
        this.v1ReplicatorProvider = v1ReplicatorProvider;
        this.watchManager = watchManager;
        HubServices.registerPreStop(new ReplicatorService());
    }

    private class ReplicatorService extends AbstractIdleService {

        @Override
        protected void startUp() throws Exception {
            startReplicator();
        }

        @Override
        protected void shutDown() throws Exception {
            stopped.set(true);
            stopReplication();
        }

    }

    public void startReplicator() {
        logger.info("starting");
        watchManager.register(new Watcher() {
            @Override
            public void callback(CuratorEvent event) {
                //todo - gfm - 1/23/15 - this should probably use a different thread
                replicateChannels();
            }

            @Override
            public String getPath() {
                return REPLICATOR_WATCHER_PATH;
            }
        });
        replicateChannels();
    }

    private synchronized void replicateChannels() {
        if (stopped.get()) {
            logger.info("replication stopped");
            return;
        }
        logger.info("replicating channels");
        Set<String> replicators = new HashSet<>();
        Iterable<ChannelConfiguration> replicatedChannels = channelService.getChannels(REPLICATED);
        for (ChannelConfiguration channel : replicatedChannels) {
            if (replicatorMap.containsKey(channel.getName())) {
                ChannelReplicator replicator = replicatorMap.get(channel.getName());
                if (!replicator.getChannel().getReplicationSource().equals(channel.getReplicationSource())) {
                    logger.info("changing replication source from {} to {}",
                            replicator.getChannel().getReplicationSource(), channel.getReplicationSource());
                    replicator.exit();
                    startReplication(channel);
                }
            } else {
                startReplication(channel);
            }
            replicators.add(channel.getName());
        }
        Set<String> toStop = new HashSet<>(replicatorMap.keySet());
        toStop.removeAll(replicators);
        logger.info("stopping replicators {}", toStop);
        for (String nameToStop : toStop) {
            logger.info("stopping {}", nameToStop);
            ChannelReplicator replicator = replicatorMap.remove(nameToStop);
            replicator.exit();
        }
    }

    private void stopReplication() {
        logger.info("stopping all replication " + replicatorMap.keySet());
        Collection<ChannelReplicator> replicators = replicatorMap.values();
        for (ChannelReplicator replicator : replicators) {
            replicator.exit();
        }
        logger.info("stopped all replication " + replicatorMap.keySet());
    }

    private void startReplication(ChannelConfiguration channel) {
        logger.info("starting replication of " + channel);
        ChannelUtils.Version version = channelUtils.getHubVersion(channel.getReplicationSource());
        if (version.equals(ChannelUtils.Version.V2)) {
            startV2Replication(channel);
        } else if (version.equals(ChannelUtils.Version.V1)) {
            startV1Replication(channel);
        }
    }

    private void startV2Replication(ChannelConfiguration channel) {
        logger.debug("starting v2 replication of " + channel);
        try {
            String appUrl = HubProperties.getProperty("app.url", "");
            String groupName = "Replication_" + channel.getName();
            String callbackUrl = appUrl + "internal/replication/" + channel.getName();
            channelUtils.startGroupCallback(groupName, callbackUrl, channel.getReplicationSource());
        } catch (Exception e) {
            logger.warn("unable to start replication " + channel, e);
        }
    }

    private void startV1Replication(ChannelConfiguration channel) {
        logger.debug("starting v1 replication of " + channel);
        try {
            V1ChannelReplicator v1ChannelReplicator = v1ReplicatorProvider.get();
            v1ChannelReplicator.setChannel(channel);
            if (v1ChannelReplicator.tryLeadership()) {
                replicatorMap.put(channel.getName(), v1ChannelReplicator);
            }
        } catch (Exception e) {
            logger.warn("unable to start replication " + channel, e);
        }
    }

    @Override
    public void notifyWatchers() {
        watchManager.notifyWatcher(REPLICATOR_WATCHER_PATH);
    }

}