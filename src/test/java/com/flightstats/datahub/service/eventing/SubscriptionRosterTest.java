package com.flightstats.datahub.service.eventing;

import org.junit.Test;

import java.net.URI;
import java.util.Collection;

import static junit.framework.Assert.assertEquals;
import static junit.framework.TestCase.assertTrue;
import static org.hamcrest.CoreMatchers.hasItem;
import static org.hamcrest.CoreMatchers.hasItems;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Mockito.mock;

public class SubscriptionRosterTest {

	@Test
	public void testSubscribe() throws Exception {
		SubscriptionRoster testClass = new SubscriptionRoster();
		Consumer<URI> sink = mock(Consumer.class);
		testClass.subscribe("mychan", sink);
		assertThat(testClass.getSubscribers("mychan"), hasItem(sink));
	}

	@Test
	public void testUnsubscribe() throws Exception {
		SubscriptionRoster testClass = new SubscriptionRoster();
		Consumer<URI> sink = mock(Consumer.class);
		testClass.subscribe("mychan", sink);
		testClass.unsubscribe("mychan", sink);
		assertTrue(testClass.getSubscribers("mychan").isEmpty());
	}

	@Test
	public void testGetSubscribers() throws Exception {
		String channel1 = "mychan";
		String channel2 = "chan2";
		Consumer<URI> sink1 = mock(Consumer.class);
		Consumer<URI> sink2 = mock(Consumer.class);
		Consumer<URI> sink3 = mock(Consumer.class);

		SubscriptionRoster testClass = new SubscriptionRoster();

		testClass.subscribe(channel1, sink1);
		testClass.subscribe(channel2, sink2);
		testClass.subscribe(channel1, sink3);

		assertEquals(2, testClass.getSubscribers(channel1).size());
		assertEquals(1, testClass.getSubscribers(channel2).size());
		assertThat(testClass.getSubscribers(channel1), hasItems(sink1, sink3));
		assertThat(testClass.getSubscribers(channel2), hasItem(sink2));
	}

	@Test
	public void testConcurrentModification() throws Exception {
		SubscriptionRoster testClass = new SubscriptionRoster();
		testClass.subscribe("chan", mock(Consumer.class));
		testClass.subscribe("chan", mock(Consumer.class));
		testClass.subscribe("chan", mock(Consumer.class));
		Collection<Consumer<URI>> subscribers = testClass.getSubscribers("chan");
		int iterationCount = 0;
		for (Consumer<URI> subscriber : subscribers) {
			testClass.subscribe("chan", mock(Consumer.class));    //For each subscriber, add a new one as we iterate.
			iterationCount++;
		}
		assertEquals(3, iterationCount);
		assertEquals(6, testClass.getSubscribers("chan").size());
	}

}
