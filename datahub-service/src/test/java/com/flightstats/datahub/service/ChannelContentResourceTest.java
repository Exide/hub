package com.flightstats.datahub.service;

import com.flightstats.datahub.dao.ChannelService;
import com.flightstats.datahub.model.Content;
import com.flightstats.datahub.model.ContentKey;
import com.flightstats.datahub.model.LinkedContent;
import com.flightstats.datahub.model.SequenceContentKey;
import com.google.common.base.Optional;
import org.junit.Before;
import org.junit.Test;

import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;
import java.net.URI;

import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class ChannelContentResourceTest {

    private ChannelService channelService;

    @Before
    public void setUp() throws Exception {
        channelService = mock(ChannelService.class);
    }

    @Test
    public void testGetValue() throws Exception {
        String channelName = "canal4";
        byte[] expected = new byte[]{55, 66, 77, 88};
        Optional<String> contentType = Optional.of("text/plain");
        Optional<String> contentLanguage = Optional.of("en");
        ContentKey key = new SequenceContentKey( 1000);
        Content value = new Content(contentType, contentLanguage, expected, 0L);
        LinkedContent linkedValue = new LinkedContent(value, Optional.<ContentKey>absent(),
                Optional.<ContentKey>absent());

        when(channelService.getValue(channelName, key.keyToString())).thenReturn(Optional.of(linkedValue));

        ChannelContentResource testClass = new ChannelContentResource(null, channelService);
        Response result = testClass.getValue(channelName, key.keyToString(), null);

        assertEquals(MediaType.TEXT_PLAIN_TYPE, result.getMetadata().getFirst("Content-Type"));
        assertEquals("en", result.getMetadata().getFirst("Content-Language"));
        assertEquals(expected, result.getEntity());
    }

    @Test
    public void testGetValueNoContentTypeSpecified() throws Exception {
        String channelName = "canal4";
        ContentKey key = new SequenceContentKey( 1000);
        byte[] expected = new byte[]{55, 66, 77, 88};
        Content value = new Content(Optional.<String>absent(), Optional.<String>absent(), expected, 0L);
        Optional<ContentKey> previous = Optional.absent();

        when(channelService.getValue(channelName, key.keyToString())).thenReturn(Optional.of(new LinkedContent(value, previous, Optional.<ContentKey>absent())));

        ChannelContentResource testClass = new ChannelContentResource(null, channelService);
        Response result = testClass.getValue(channelName, key.keyToString(), null);

        assertEquals(MediaType.APPLICATION_OCTET_STREAM_TYPE, result.getMetadata().getFirst("Content-Type"));      //null, and the framework defaults to application/octet-stream
        assertEquals(expected, result.getEntity());
    }

    @Test
    public void testGetValueContentMismatch() throws Exception {
        String channelName = "canal4";
        ContentKey key = new SequenceContentKey( 1000);
        byte[] expected = new byte[]{55, 66, 77, 88};
        Content value = new Content(Optional.of(MediaType.APPLICATION_XML), Optional.<String>absent(), expected, 0L);
        Optional<ContentKey> previous = Optional.absent();

        when(channelService.getValue(channelName, key.keyToString())).thenReturn(Optional.of(new LinkedContent(value, previous, Optional.<ContentKey>absent())));

        ChannelContentResource testClass = new ChannelContentResource(null, channelService);
        Response result = testClass.getValue(channelName, key.keyToString(), MediaType.APPLICATION_JSON);

        assertEquals(406, result.getStatus());
    }

    @Test
    public void testGetValueNotFound() throws Exception {

        String channelName = "canal4";
        ContentKey key = new SequenceContentKey( 1000);

        when(channelService.getValue(channelName, key.keyToString())).thenReturn(Optional.<LinkedContent>absent());

        ChannelContentResource testClass = new ChannelContentResource(null, channelService);
        try {
            testClass.getValue(channelName, key.keyToString(), null);
            fail("Should have thrown exception.");
        } catch (WebApplicationException e) {
            assertEquals(Response.Status.NOT_FOUND.getStatusCode(), e.getResponse().getStatus());
        }
    }

    @Test
    public void testCreationDateHeaderInResponse() throws Exception {
        String channelName = "woo";
        ContentKey key = new SequenceContentKey(  1000);
        Content value = new Content(Optional.<String>absent(), Optional.<String>absent(), "found it!".getBytes(), 987654321);
        LinkedContent linkedValue = new LinkedContent(value, Optional.<ContentKey>absent(),
                Optional.<ContentKey>absent());

        when(channelService.getValue(channelName, key.keyToString())).thenReturn(Optional.of(linkedValue));

        ChannelContentResource testClass = new ChannelContentResource(null, channelService);
        Response result = testClass.getValue(channelName, key.keyToString(), null);

        String creationDateString = (String) result.getMetadata().getFirst(CustomHttpHeaders.CREATION_DATE_HEADER.getHeaderName());
        assertEquals("1970-01-12T10:20:54.321Z", creationDateString);
    }

    @Test
    public void testPreviousLink() throws Exception {
        String channelName = "woo";
        ContentKey previousKey = new SequenceContentKey(1000);
        ContentKey key = new SequenceContentKey(1001);
        Content value = new Content(Optional.<String>absent(), Optional.<String>absent(), "found it!".getBytes(), 0L);
        Optional<ContentKey> previous = Optional.of(previousKey);
        LinkedContent linkedValue = new LinkedContent(value, previous, Optional.<ContentKey>absent());

        UriInfo uriInfo = mock(UriInfo.class);

        when(channelService.getValue(channelName, key.keyToString())).thenReturn(Optional.of(linkedValue));
        when(uriInfo.getRequestUri()).thenReturn(URI.create("http://path/to/thisitem123"));

        ChannelContentResource testClass = new ChannelContentResource(uriInfo, channelService);
        Response result = testClass.getValue(channelName, key.keyToString(), null);

        String link = (String) result.getMetadata().getFirst("Link");
        assertEquals("<http://path/to/1000>;rel=\"previous\"", link);
    }

    @Test
    public void testPreviousLink_none() throws Exception {
        String channelName = "woo";
        ContentKey key = new SequenceContentKey( 1000);
        Content value = new Content(Optional.<String>absent(), Optional.<String>absent(), "found it!".getBytes(), 0L);
        Optional<ContentKey> previous = Optional.absent();
        LinkedContent linkedValue = new LinkedContent(value, previous, Optional.<ContentKey>absent());

        when(channelService.getValue(channelName, key.keyToString())).thenReturn(Optional.of(linkedValue));

        ChannelContentResource testClass = new ChannelContentResource(null, channelService);
        Response result = testClass.getValue(channelName, key.keyToString(), null);

        String link = (String) result.getMetadata().getFirst("Link");
        assertNull(link);
    }

    @Test
    public void testNextLink() throws Exception {
        String channelName = "nerxt";
        ContentKey nextKey = new SequenceContentKey( 1001);
        ContentKey key = new SequenceContentKey( 1000);
        Content value = new Content(Optional.<String>absent(), Optional.<String>absent(), "found it!".getBytes(), 0L);
        Optional<ContentKey> next = Optional.of(nextKey);
        LinkedContent linkedValue = new LinkedContent(value, Optional.<ContentKey>absent(), next);

        UriInfo uriInfo = mock(UriInfo.class);

        when(channelService.getValue(channelName, key.keyToString())).thenReturn(Optional.of(linkedValue));
        when(uriInfo.getRequestUri()).thenReturn(URI.create("http://path/to/thisitem123"));

        ChannelContentResource testClass = new ChannelContentResource(uriInfo, channelService);
        Response result = testClass.getValue(channelName, key.keyToString(), null);

        String link = (String) result.getMetadata().getFirst("Link");
        assertEquals("<http://path/to/1001>;rel=\"next\"", link);
    }

    @Test
    public void testNextLinkNone() throws Exception {
        String channelName = "nerxt";
        ContentKey key = new SequenceContentKey( 1000);
        Content value = new Content(Optional.<String>absent(), Optional.<String>absent(), "found it!".getBytes(), 0L);
        LinkedContent linkedValue = new LinkedContent(value, Optional.<ContentKey>absent(),
                Optional.<ContentKey>absent());


        when(channelService.getValue(channelName, key.keyToString())).thenReturn(Optional.of(linkedValue));

        ChannelContentResource testClass = new ChannelContentResource(null, channelService);
        Response result = testClass.getValue(channelName, key.keyToString(), null);

        String link = (String) result.getMetadata().getFirst("Link");
        assertNull(link);
    }

    @Test
    public void testLanguageHeader_missing() throws Exception {
        String channelName = "canal4";
        ContentKey key = new SequenceContentKey( 1000);
        Content value = new Content(Optional.<String>absent(), Optional.<String>absent(), new byte[]{}, 0L);
        LinkedContent linkedValue = new LinkedContent(value, Optional.<ContentKey>absent(),
                Optional.<ContentKey>absent());

        when(channelService.getValue(channelName, key.keyToString())).thenReturn(Optional.of(linkedValue));

        ChannelContentResource testClass = new ChannelContentResource(null, channelService);
        Response result = testClass.getValue(channelName, key.keyToString(), null);

        assertNull(result.getMetadata().getFirst("Content-Language"));
    }

    @Test
    public void testEncodingHeader_missing() throws Exception {
        String channelName = "canal4";
        ContentKey key = new SequenceContentKey( 1000);
        Content value = new Content(Optional.<String>absent(), Optional.<String>absent(), new byte[]{}, 0L);
        LinkedContent linkedValue = new LinkedContent(value, Optional.<ContentKey>absent(),
                Optional.<ContentKey>absent());

        when(channelService.getValue(channelName, key.keyToString())).thenReturn(Optional.of(linkedValue));

        ChannelContentResource testClass = new ChannelContentResource(null, channelService);
        Response result = testClass.getValue(channelName, key.keyToString(), null);

        assertNull(result.getMetadata().getFirst("Content-Encoding"));
    }
}
