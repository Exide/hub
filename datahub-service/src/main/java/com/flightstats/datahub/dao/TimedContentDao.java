package com.flightstats.datahub.dao;

import com.flightstats.datahub.metrics.MetricsTimer;
import com.flightstats.datahub.metrics.TimedCallback;
import com.flightstats.datahub.model.ChannelConfiguration;
import com.flightstats.datahub.model.Content;
import com.flightstats.datahub.model.ContentKey;
import com.flightstats.datahub.model.ValueInsertionResult;
import com.google.common.base.Optional;
import com.google.inject.Inject;
import com.google.inject.name.Named;
import org.joda.time.DateTime;

import java.util.Collection;

/**
 *
 */
public class TimedContentDao implements ContentDao {
    public static final String DELEGATE = "TimedContentDao.DELEGATE";
    private final ContentDao delegate;
    private final MetricsTimer metricsTimer;

    @Inject
    public TimedContentDao(@Named(DELEGATE) ContentDao delegate, MetricsTimer metricsTimer) {
        this.delegate = delegate;
        this.metricsTimer = metricsTimer;
    }

    @Override
    public ValueInsertionResult write(final String channelName, final Content columnValue, final Optional<Integer> ttlSeconds) {
        return metricsTimer.time("valueDao.write", new TimedCallback<ValueInsertionResult>() {
            @Override
            public ValueInsertionResult call() {
                return delegate.write(channelName, columnValue, ttlSeconds);
            }
        });
    }

    @Override
    public Content read(final String channelName, final ContentKey key) {
        return metricsTimer.time("valueDao.read", new TimedCallback<Content>() {
            @Override
            public Content call() {
                return delegate.read(channelName, key);
            }
        });
    }

    @Override
    public void initialize() {
        delegate.initialize();
    }

    @Override
    public void initializeChannel(final ChannelConfiguration configuration) {
        metricsTimer.time("valueDao.initializeChannel", new TimedCallback<Object>() {
            @Override
            public Object call() {
                delegate.initializeChannel(configuration);
                return null;
            }
        });
    }

    @Override
    public Optional<ContentKey> getKey(String id) {
        return delegate.getKey(id);
    }

    @Override
    public Collection<ContentKey> getKeys(final String channelName, final DateTime dateTime) {
        return metricsTimer.time("valueDao.getKeys", new TimedCallback<Collection<ContentKey>>() {
            @Override
            public Collection<ContentKey> call() {
                return delegate.getKeys(channelName, dateTime);
            }
        });

    }

    @Override
    public void delete(String channelName) {
        delegate.delete(channelName);
    }

    @Override
    public void updateChannel(ChannelConfiguration configuration) {
        delegate.updateChannel(configuration);
    }
}
