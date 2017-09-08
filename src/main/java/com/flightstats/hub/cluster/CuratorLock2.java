package com.flightstats.hub.cluster;

import com.google.inject.Inject;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.locks.InterProcessSemaphoreMutex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * CuratorLock2 blends concepts from CuratorLock and CuratorLeader
 * It is intended for long or short running processes.
 */
public class CuratorLock2 {
    private final static Logger logger = LoggerFactory.getLogger(CuratorLock2.class);

    private final CuratorFramework curator;
    private final ExecutorService singleThreadExecutor;
    private final LeadershipV2 leadershipV2;
    private InterProcessSemaphoreMutex mutex;

    @Inject
    public CuratorLock2(CuratorFramework curator, ZooKeeperState zooKeeperState) {
        this.curator = curator;
        singleThreadExecutor = Executors.newSingleThreadExecutor();
        leadershipV2 = new LeadershipV2(zooKeeperState);
    }

    public boolean runWithLock(Lockable lockable, String lockPath, long time, TimeUnit timeUnit) {
        mutex = new InterProcessSemaphoreMutex(curator, lockPath);
        try {
            logger.debug("attempting acquire {}", lockPath);
            if (mutex.acquire(time, timeUnit)) {
                leadershipV2.setLeadership(true);
                logger.debug("acquired {} {}", lockPath, leadershipV2.hasLeadership());
                singleThreadExecutor.submit(() -> {
                    try {
                        lockable.takeLeadership(leadershipV2);
                    } catch (Exception e) {
                        logger.warn("we lost the lock " + lockPath, e);
                        leadershipV2.setLeadership(false);
                    }
                });
                return true;
            } else {
                logger.debug("unable to acquire {} ", lockPath);
                return false;
            }
        } catch (Exception e) {
            logger.warn("oh no! issue with " + lockPath, e);
            leadershipV2.setLeadership(false);
            release();
            return false;
        }
    }

    public void stopWorking() {
        leadershipV2.setLeadership(false);
    }

    public void delete() {
        stopWorking();
        if (mutex != null) {
            release();
        }
        try {
            singleThreadExecutor.awaitTermination(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            logger.info("InterruptedException", e);
        }
    }

    private void release() {
        try {
            mutex.release();
        } catch (Exception e) {
            logger.warn("issue releasing mutex", e);
        }
    }

}
