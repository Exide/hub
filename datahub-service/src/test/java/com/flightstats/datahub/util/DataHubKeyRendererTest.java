package com.flightstats.datahub.util;

import com.flightstats.datahub.model.DataHubKey;
import com.google.common.base.Optional;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class DataHubKeyRendererTest {

    @Test
    public void testKeyToString() throws Exception {
        DataHubKey key = new DataHubKey(987654321);
        DataHubKeyRenderer testClass = new DataHubKeyRenderer();
        String result = testClass.keyToString(key);
        assertEquals("987654321", result);
    }

    @Test
    public void testKeyFromString() throws Exception {
        DataHubKey expected = new DataHubKey(1290);
        DataHubKeyRenderer testClass = new DataHubKeyRenderer();
        Optional<DataHubKey> result = testClass.fromString(testClass.keyToString(expected));
        assertEquals(expected, result.get());
    }

    @Test
    public void testSequencesAreOrderedWithinSameMillis() throws Exception {
        DataHubKey key1 = new DataHubKey(1000);
        DataHubKey key2 = new DataHubKey(2000);
        DataHubKey key3 = new DataHubKey(3000);

        DataHubKeyRenderer testClass = new DataHubKeyRenderer();

        String string1 = testClass.keyToString(key1);
        String string2 = testClass.keyToString(key2);
        String string3 = testClass.keyToString(key3);

        assertTrue(string1.compareTo(string2) < 0);
        assertTrue(string2.compareTo(string3) < 0);
    }

}
