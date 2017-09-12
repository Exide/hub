package com.flightstats.hub.cluster;

import com.google.inject.Inject;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.locks.InterProcessSemaphoreMutex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

/**
 * The CuratorLock should be used for short running processes which need a global lock across all instances.
 * Longer running processes should use CuratorLeader.
 */
//todo - gfm - deprecated?
public class CuratorLock {
    private final static Logger logger = LoggerFactory.getLogger(CuratorLock.class);

    private final CuratorFramework curator;
    private final ZooKeeperState zooKeeperState;

    @Inject
    public CuratorLock(CuratorFramework curator, ZooKeeperState zooKeeperState) {
        this.curator = curator;
        this.zooKeeperState = zooKeeperState;
    }

    /**
     * Long running processes need to check shouldStopWorking to see if you've lost the lock.
     * runWithLock will not throw an exception up the stack.
     */
    public void runWithLock(Lockable lockable, String lockPath, long time, TimeUnit timeUnit) {

        InterProcessSemaphoreMutex mutex = new InterProcessSemaphoreMutex(curator, lockPath);
        try {
            logger.debug("attempting acquire {}", lockPath);
            if (mutex.acquire(time, timeUnit)) {
                logger.debug("acquired {}", lockPath);
                lockable.runWithLock();
            } else {
                logger.debug("unable to acquire {} ", lockPath);
            }
        } catch (Exception e) {
            logger.warn("oh no! issue with " + lockPath, e);
        } finally {
            try {
                mutex.release();
            } catch (Exception e) {
                //ignore
            }
        }
    }

    public void delete(final String lockPath) {
        //deleting the path within a lock will cause Curator to log an error 'Lease already released', which can be ignored.
        Lockable lockable = new Lockable() {
            @Override
            public void runWithLock() throws Exception {
                curator.delete().deletingChildrenIfNeeded().forPath(lockPath);
            }
        };
        runWithLock(lockable, lockPath, 1, TimeUnit.SECONDS);
    }

    /**
     * All users should handle this
     */
    private boolean shouldStopWorking() {
        return zooKeeperState.shouldStopWorking();
    }

    public boolean shouldKeepWorking() {
        return !shouldStopWorking();
    }

}
