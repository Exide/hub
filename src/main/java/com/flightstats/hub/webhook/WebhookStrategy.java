package com.flightstats.hub.webhook;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flightstats.hub.app.HubProvider;
import com.flightstats.hub.cluster.LastContentPath;
import com.flightstats.hub.dao.ChannelService;
import com.flightstats.hub.model.ContentKey;
import com.flightstats.hub.model.ContentPath;
import com.flightstats.hub.model.MinutePath;
import com.flightstats.hub.model.SecondPath;
import com.flightstats.hub.util.TimeUtil;
import com.google.common.base.Optional;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

interface WebhookStrategy extends AutoCloseable {

    ContentPath getStartingPath();

    ContentPath getLastCompleted();

    void start(Webhook webhook, ContentPath startingKey);

    Optional<ContentPath> next() throws Exception;

    ObjectNode createResponse(ContentPath contentPath);

    ContentPath inProcess(ContentPath contentPath);

    final static WebhookService webhookService = HubProvider.getInstance(WebhookService.class);
    final static Logger logger = LoggerFactory.getLogger(SingleWebhookStrategy.class);

    static ContentPath createContentPath(Webhook webhook) {
        if (webhook.isSecond()) {
            return new SecondPath();
        }
        if (webhook.isMinute()) {
            return new MinutePath();
        }
        return new ContentKey(TimeUtil.now(), "initial");
    }

    static WebhookStrategy getStrategy(Webhook webhook, LastContentPath lastContentPath, ChannelService channelService) {
        if (webhook.isMinute() || webhook.isSecond()) {
            return new TimedWebhookStrategy(webhook, lastContentPath, channelService);
        }
        return new SingleWebhookStrategy(webhook, lastContentPath, channelService);
    }

    static void close(AtomicBoolean shouldExit, ExecutorService executorService, BlockingQueue queue) {
        if (!shouldExit.get()) {
            shouldExit.set(true);
        }
        if (executorService != null) {
            executorService.shutdownNow();
        }
        if (queue != null) {
            queue.clear();
        }
    }

    static boolean pastEndtimeThreshold(Webhook webhook, DateTime current) {
        if (webhook.getEndItem() != null) {
            DateTime endTime = ContentPath.fromFullUrl(webhook.getEndItem()).get().getTime();
            if (current.compareTo(endTime) >= 0) {
                logger.info("Bracketed Webhook " + webhook.getName() + " complete at time: " + current);
                webhookService.delete(webhook.getName());
                return true;
            }
        }
        return false;
    }

    ;
}
