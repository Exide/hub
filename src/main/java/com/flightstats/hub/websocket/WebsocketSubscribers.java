package com.flightstats.hub.websocket;

import com.google.inject.Inject;
import com.hazelcast.core.MessageListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;

public class WebsocketSubscribers {

	private final static Logger logger = LoggerFactory.getLogger(WebsocketSubscribers.class);
	private final WebsocketPublisher websocketPublisher;
    private final ConcurrentHashMap<ChannelConsumer, String> consumerToMessageListener = new ConcurrentHashMap<>();

	@Inject
	public WebsocketSubscribers(WebsocketPublisher websocketPublisher) {
		this.websocketPublisher = websocketPublisher;
    }

	public void subscribe(final String channelName, Consumer<String> consumer) {
        logger.info("Adding new message listener for sequence channel " + channelName);
        MessageListener<String> messageListener = new SequenceSubscriber(consumer);
        String registrationId = websocketPublisher.subscribe(channelName, messageListener);
        consumerToMessageListener.put( new ChannelConsumer( channelName, consumer ), registrationId );
	}

    public void unsubscribe(String channelName, Consumer<String> subscription) {
		String registrationId = consumerToMessageListener.remove(new ChannelConsumer(channelName, subscription));
		logger.info("Removing message listener for websocket hazelcast queue for channel " + channelName);
		websocketPublisher.unsubscribe(channelName, registrationId);
	}

	public int getTotalSubscriberCount() {
		return consumerToMessageListener.size();
	}

	private static class ChannelConsumer {
		private final String channelName;
		private final Consumer<String> consumer;

		ChannelConsumer( String channelName, Consumer<String> consumer ) {

			this.channelName = channelName;
			this.consumer = consumer;
		}

		public boolean equals( Object o ) {
			if ( this == o ) return true;
			if ( !(o instanceof ChannelConsumer) ) return false;

			ChannelConsumer that = (ChannelConsumer) o;

			if ( !channelName.equals( that.channelName ) ) return false;
			if ( !consumer.equals( that.consumer ) ) return false;

			return true;
		}

		public int hashCode() {
			int result = channelName.hashCode();
			result = 31 * result + consumer.hashCode();
			return result;
		}
	}
}
