package com.flightstats.hub.channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flightstats.hub.app.HubProperties;
import com.flightstats.hub.app.HubProvider;
import com.flightstats.hub.dao.ChannelService;
import com.flightstats.hub.dao.ContentMarshaller;
import com.flightstats.hub.dao.ItemRequest;
import com.flightstats.hub.events.ContentOutput;
import com.flightstats.hub.events.EventsService;
import com.flightstats.hub.exception.ConflictException;
import com.flightstats.hub.exception.ContentTooLargeException;
import com.flightstats.hub.exception.InvalidRequestException;
import com.flightstats.hub.metrics.ActiveTraces;
import com.flightstats.hub.metrics.MetricsService;
import com.flightstats.hub.metrics.NewRelicIgnoreTransaction;
import com.flightstats.hub.model.*;
import com.flightstats.hub.rest.Linked;
import com.flightstats.hub.util.TimeUtil;
import com.google.common.base.Optional;
import com.google.common.io.ByteStreams;
import com.sun.jersey.core.header.MediaTypes;
import datadog.trace.api.Trace;
import org.apache.commons.lang3.StringUtils;
import org.glassfish.jersey.media.sse.EventOutput;
import org.glassfish.jersey.media.sse.SseFeature;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.*;
import javax.ws.rs.core.*;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.*;

import static com.flightstats.hub.rest.Linked.linked;
import static com.flightstats.hub.util.TimeUtil.*;
import static com.google.common.base.Strings.isNullOrEmpty;
import static javax.ws.rs.core.Response.Status.NOT_FOUND;
import static javax.ws.rs.core.Response.Status.SEE_OTHER;

@Path("/channel/{channel}/{Y}/{M}/{D}/")
public class ChannelContentResource {
    static final String CREATION_DATE = "Creation-Date";
    public static final String THREADS = HubProperties.getProperty("s3.large.threads", "3");

    private final static Logger logger = LoggerFactory.getLogger(ChannelContentResource.class);

    @Context
    private UriInfo uriInfo;

    private final static TagContentResource tagContentResource = HubProvider.getInstance(TagContentResource.class);
    private final static ObjectMapper mapper = HubProvider.getInstance(ObjectMapper.class);
    private final static ChannelService channelService = HubProvider.getInstance(ChannelService.class);
    private final static MetricsService metricsService = HubProvider.getInstance(MetricsService.class);
    private final static EventsService eventsService = HubProvider.getInstance(EventsService.class);

    public static MediaType getContentType(Content content) {
        Optional<String> contentType = content.getContentType();
        if (contentType.isPresent() && !isNullOrEmpty(contentType.get())) {
            return MediaType.valueOf(contentType.get());
        }
        return MediaType.APPLICATION_OCTET_STREAM_TYPE;
    }

    static boolean contentTypeIsNotCompatible(String acceptHeader, final MediaType actualContentType) {
        List<MediaType> acceptableContentTypes;
        if (StringUtils.isBlank(acceptHeader)) {
            acceptableContentTypes = MediaTypes.GENERAL_MEDIA_TYPE_LIST;
        } else {
            acceptableContentTypes = new ArrayList<>();
            String[] types = acceptHeader.split(",");
            for (String type : types) {
                acceptableContentTypes.addAll(getMediaTypes(type));
            }
        }
        return !acceptableContentTypes.stream().anyMatch(input -> input.isCompatible(actualContentType));
    }

    private static List<MediaType> getMediaTypes(String type) {
        try {
            return MediaTypes.createMediaTypes(new String[]{type});
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    @Trace
    @Produces({MediaType.APPLICATION_JSON, "multipart/*", "application/zip"})
    @GET
    public Response getDay(@PathParam("channel") String channel,
                           @PathParam("Y") int year,
                           @PathParam("M") int month,
                           @PathParam("D") int day,
                           @QueryParam("location") @DefaultValue(Location.DEFAULT) String location,
                           @QueryParam("epoch") @DefaultValue(Epoch.DEFAULT) String epoch,
                           @QueryParam("trace") @DefaultValue("false") boolean trace,
                           @QueryParam("stable") @DefaultValue("true") boolean stable,
                           @QueryParam("batch") @DefaultValue("false") boolean batch,
                           @QueryParam("bulk") @DefaultValue("false") boolean bulk,
                           @QueryParam("order") @DefaultValue(Order.DEFAULT) String order,
                           @QueryParam("tag") String tag,
                           @HeaderParam("Accept") String accept) {
        DateTime startTime = new DateTime(year, month, day, 0, 0, 0, 0, DateTimeZone.UTC);
        return getTimeQueryResponse(channel, startTime, location, trace, stable, Unit.DAYS, tag, bulk || batch, accept, epoch, Order.isDescending(order));
    }

    @Trace
    @Path("/{hour}")
    @Produces({MediaType.APPLICATION_JSON, "multipart/*", "application/zip"})
    @GET
    public Response getHour(@PathParam("channel") String channel,
                            @PathParam("Y") int year,
                            @PathParam("M") int month,
                            @PathParam("D") int day,
                            @PathParam("hour") int hour,
                            @QueryParam("location") @DefaultValue(Location.DEFAULT) String location,
                            @QueryParam("epoch") @DefaultValue(Epoch.DEFAULT) String epoch,
                            @QueryParam("trace") @DefaultValue("false") boolean trace,
                            @QueryParam("stable") @DefaultValue("true") boolean stable,
                            @QueryParam("batch") @DefaultValue("false") boolean batch,
                            @QueryParam("bulk") @DefaultValue("false") boolean bulk,
                            @QueryParam("order") @DefaultValue(Order.DEFAULT) String order,
                            @QueryParam("tag") String tag,
                            @HeaderParam("Accept") String accept) {
        DateTime startTime = new DateTime(year, month, day, hour, 0, 0, 0, DateTimeZone.UTC);
        return getTimeQueryResponse(channel, startTime, location, trace, stable, Unit.HOURS, tag, bulk || batch, accept, epoch, Order.isDescending(order));
    }

    @Trace
    @Path("/{h}/{minute}")
    @Produces({MediaType.APPLICATION_JSON, "multipart/*", "application/zip"})
    @GET
    public Response getMinute(@PathParam("channel") String channel,
                              @PathParam("Y") int year,
                              @PathParam("M") int month,
                              @PathParam("D") int day,
                              @PathParam("h") int hour,
                              @PathParam("minute") int minute,
                              @QueryParam("location") @DefaultValue(Location.DEFAULT) String location,
                              @QueryParam("epoch") @DefaultValue(Epoch.DEFAULT) String epoch,
                              @QueryParam("trace") @DefaultValue("false") boolean trace,
                              @QueryParam("stable") @DefaultValue("true") boolean stable,
                              @QueryParam("batch") @DefaultValue("false") boolean batch,
                              @QueryParam("bulk") @DefaultValue("false") boolean bulk,
                              @QueryParam("order") @DefaultValue(Order.DEFAULT) String order,
                              @QueryParam("tag") String tag,
                              @HeaderParam("Accept") String accept) {
        DateTime startTime = new DateTime(year, month, day, hour, minute, 0, 0, DateTimeZone.UTC);
        return getTimeQueryResponse(channel, startTime, location, trace, stable, Unit.MINUTES, tag, bulk || batch, accept, epoch, Order.isDescending(order));
    }

    @Trace
    @Path("/{h}/{m}/{second}")
    @Produces({MediaType.APPLICATION_JSON, "multipart/*", "application/zip"})
    @GET
    public Response getSecond(@PathParam("channel") String channel,
                              @PathParam("Y") int year,
                              @PathParam("M") int month,
                              @PathParam("D") int day,
                              @PathParam("h") int hour,
                              @PathParam("m") int minute,
                              @PathParam("second") int second,
                              @QueryParam("location") @DefaultValue(Location.DEFAULT) String location,
                              @QueryParam("epoch") @DefaultValue(Epoch.DEFAULT) String epoch,
                              @QueryParam("trace") @DefaultValue("false") boolean trace,
                              @QueryParam("stable") @DefaultValue("true") boolean stable,
                              @QueryParam("batch") @DefaultValue("false") boolean batch,
                              @QueryParam("bulk") @DefaultValue("false") boolean bulk,
                              @QueryParam("order") @DefaultValue(Order.DEFAULT) String order,
                              @QueryParam("tag") String tag,
                              @HeaderParam("Accept") String accept) {
        DateTime startTime = new DateTime(year, month, day, hour, minute, second, 0, DateTimeZone.UTC);
        return getTimeQueryResponse(channel, startTime, location, trace, stable, Unit.SECONDS, tag, bulk || batch, accept, epoch, Order.isDescending(order));
    }

    private Response getTimeQueryResponse(String channel, DateTime startTime, String location, boolean trace, boolean stable,
                                          Unit unit, String tag, boolean bulk, String accept, String epoch, boolean descending) {
        if (tag != null) {
            return tagContentResource.getTimeQueryResponse(tag, startTime, location, trace, stable, unit, bulk, accept, uriInfo, epoch, descending);
        }
        TimeQuery query = TimeQuery.builder()
                .channelName(channel)
                .startTime(startTime)
                .stable(stable)
                .unit(unit)
                .location(Location.valueOf(location))
                .epoch(Epoch.valueOf(epoch))
                .build();
        SortedSet<ContentKey> keys = channelService.queryByTime(query);
        DateTime current = stable ? stable() : now();
        DateTime next = startTime.plus(unit.getDuration());
        DateTime previous = startTime.minus(unit.getDuration());
        if (bulk) {
            return BulkBuilder.build(keys, channel, channelService, uriInfo, accept, descending, (builder) -> {
                if (next.isBefore(current)) {
                    builder.header("Link", "<" + TimeLinkUtil.getUri(channel, uriInfo, unit, next) +
                            ">;rel=\"" + "next" + "\"");
                }
                builder.header("Link", "<" + TimeLinkUtil.getUri(channel, uriInfo, unit, previous) +
                        ">;rel=\"" + "previous" + "\"");
            });
        } else {
            ObjectNode root = mapper.createObjectNode();
            ObjectNode links = root.putObject("_links");
            ObjectNode self = links.putObject("self");
            self.put("href", uriInfo.getRequestUri().toString());
            if (next.isBefore(current)) {
                links.putObject("next").put("href", TimeLinkUtil.getUri(channel, uriInfo, unit, next).toString());
            }
            links.putObject("previous").put("href", TimeLinkUtil.getUri(channel, uriInfo, unit, previous).toString());
            ArrayNode ids = links.putArray("uris");
            URI channelUri = LinkBuilder.buildChannelUri(channel, uriInfo);
            ArrayList<ContentKey> list = new ArrayList<>(keys);
            if (descending) {
                Collections.reverse(list);
            }
            for (ContentKey key : list) {
                URI uri = LinkBuilder.buildItemUri(key, channelUri);
                ids.add(uri.toString());
            }
            if (trace) {
                ActiveTraces.getLocal().output(root);
            }
            return Response.ok(root).build();
        }
    }

    @Trace
    @Path("/{h}/{m}/{second}/{direction:[n|p].*}/{count}")
    @Produces({MediaType.APPLICATION_JSON})
    @GET
    public Response getDirectionalSecond(@PathParam("channel") String channel,
                                         @PathParam("Y") int year,
                                         @PathParam("M") int month,
                                         @PathParam("D") int day,
                                         @PathParam("h") int hour,
                                         @PathParam("m") int minute,
                                         @PathParam("second") int second,
                                         @PathParam("count") int count,
                                         @PathParam("direction") String direction,
                                         @QueryParam("location") @DefaultValue(Location.DEFAULT) String location,
                                         @QueryParam("epoch") @DefaultValue(Epoch.DEFAULT) String epoch,
                                         @QueryParam("trace") @DefaultValue("false") boolean trace,
                                         @QueryParam("stable") @DefaultValue("true") boolean stable,
                                         @QueryParam("order") @DefaultValue(Order.DEFAULT) String order) {
        SortedSet<ContentPath> keys = new TreeSet<>();
        DateTime stableTime = TimeUtil.stable();
        DateTime startTime = new DateTime(year, month, day, hour, minute, second, 999, DateTimeZone.UTC);
        boolean next = direction.startsWith("n");
        if (next) {
            while (startTime.isBefore(stableTime) && keys.size() < count) {
                keys.add(new SecondPath(startTime));
                startTime = startTime.plusSeconds(1);
            }
        } else {
            if (startTime.isAfter(stableTime)) {
                startTime = stableTime;
            }
            startTime = startTime.minusSeconds(1);
            while (keys.size() < count) {
                keys.add(new SecondPath(startTime));
                startTime = startTime.minusSeconds(1);
            }
        }
        //todo - gfm - can the following code be factored out with getTimeQueryResponse ?
        ObjectNode root = mapper.createObjectNode();
        ObjectNode links = root.putObject("_links");
        ObjectNode self = links.putObject("self");
        self.put("href", uriInfo.getRequestUri().toString());
        if (keys.size() == count) {
            URI uri = LinkBuilder.uriBuilder(channel, uriInfo)
                    .path(Unit.SECONDS.format(keys.last().getTime()))
                    .path("next")
                    .path("" + count)
                    .build();
            links.putObject("next").put("href", uri.toString());
        }
        if (!keys.isEmpty()) {
            URI uri = LinkBuilder.uriBuilder(channel, uriInfo)
                    .path(Unit.SECONDS.format(keys.first().getTime()))
                    .path("previous")
                    .path("" + count)
                    .build();
            links.putObject("previous").put("href", uri.toString());
        }

        ArrayNode ids = links.putArray("uris");
        URI channelUri = LinkBuilder.buildChannelUri(channel, uriInfo);

        ArrayList<ContentPath> list = new ArrayList<>(keys);
        if (Order.isDescending(order)) {
            Collections.reverse(list);
        }
        for (ContentPath path : list) {
            URI uri = LinkBuilder.buildItemUri(path, channelUri);
            ids.add(uri.toString());
        }
        if (trace) {
            ActiveTraces.getLocal().output(root);
        }
        return Response.ok(root).build();
    }

    @Trace
    @Path("/{h}/{m}/{s}/{ms}/{hash}")
    @GET
    public Response getItem(@PathParam("channel") String channel,
                            @PathParam("Y") int year,
                            @PathParam("M") int month,
                            @PathParam("D") int day,
                            @PathParam("h") int hour,
                            @PathParam("m") int minute,
                            @PathParam("s") int second,
                            @PathParam("ms") int millis,
                            @PathParam("hash") String hash,
                            @HeaderParam("Accept") String accept,
                            @HeaderParam("X-Item-Length-Required") @DefaultValue("false") boolean itemLengthRequired,
                            @QueryParam("remoteOnly") @DefaultValue("false") boolean remoteOnly
    ) throws Exception {
        long start = System.currentTimeMillis();
        ContentKey key = new ContentKey(year, month, day, hour, minute, second, millis, hash);
        ItemRequest itemRequest = ItemRequest.builder()
                .channel(channel)
                .key(key)
                .uri(uriInfo.getRequestUri())
                .remoteOnly(remoteOnly)
                .build();
        Optional<Content> optionalResult = channelService.get(itemRequest);

        if (!optionalResult.isPresent()) {
            logger.warn("404 content not found {} {}", channel, key);
            throw new WebApplicationException(Response.Status.NOT_FOUND);
        }
        Content content = optionalResult.get();

        MediaType actualContentType = getContentType(content);

        if (contentTypeIsNotCompatible(accept, actualContentType)) {
            return Response.status(Response.Status.NOT_ACCEPTABLE).build();
        }

        Response.ResponseBuilder builder = Response.ok((StreamingOutput) output -> {
            try {
                ByteStreams.copy(content.getStream(), output);
            } catch (IOException e) {
                logger.warn("issue streaming content " + channel + " " + key, e);
                throw e;
            } finally {
                content.close();
            }
        });

        if (content.isLarge()) {
            builder.header("X-LargeItem", "true");
        }
        builder.type(actualContentType)
                .header(CREATION_DATE, FORMATTER.print(new DateTime(key.getMillis())));

        builder.header("Link", "<" + uriInfo.getRequestUriBuilder().path("previous").build() + ">;rel=\"" + "previous" + "\"");
        builder.header("Link", "<" + uriInfo.getRequestUriBuilder().path("next").build() + ">;rel=\"" + "next" + "\"");

        long itemLength = content.getSize();
        if (itemLength == -1 && itemLengthRequired) {
            if (content.isLarge()) {
                itemLength = content.getSize();
            } else {
                byte[] bytes = ContentMarshaller.toBytes(content);
                itemLength = bytes.length;
            }
        }

        builder.header("X-Item-Length", itemLength);

        metricsService.time(channel, "get", start);
        return builder.build();
    }

    @Trace
    @Path("/{h}/{m}/{s}/{ms}/{hash}/{direction:[n|p].*}")
    @GET
    public Response getDirection(@PathParam("channel") String channel,
                                 @PathParam("Y") int year,
                                 @PathParam("M") int month,
                                 @PathParam("D") int day,
                                 @PathParam("h") int hour,
                                 @PathParam("m") int minute,
                                 @PathParam("s") int second,
                                 @PathParam("ms") int millis,
                                 @PathParam("hash") String hash,
                                 @QueryParam("location") @DefaultValue(Location.DEFAULT) String location,
                                 @PathParam("direction") String direction,
                                 @QueryParam("epoch") @DefaultValue(Epoch.DEFAULT) String epoch,
                                 @QueryParam("stable") @DefaultValue("true") boolean stable,
                                 @QueryParam("tag") String tag) {
        ContentKey contentKey = new ContentKey(year, month, day, hour, minute, second, millis, hash);
        boolean next = direction.startsWith("n");
        if (null != tag) {
            return tagContentResource.adjacent(tag, contentKey, stable, next, uriInfo, location, epoch);
        }
        DirectionQuery query = DirectionQuery.builder()
                .channelName(channel)
                .startKey(contentKey)
                .next(next)
                .stable(stable)
                .location(Location.valueOf(location))
                .epoch(Epoch.valueOf(epoch))
                .count(1)
                .build();
        Collection<ContentKey> keys = channelService.query(query);
        if (keys.isEmpty()) {
            return Response.status(NOT_FOUND).build();
        }
        Response.ResponseBuilder builder = Response.status(SEE_OTHER);
        String channelUri = uriInfo.getBaseUri() + "channel/" + channel;
        ContentKey foundKey = keys.iterator().next();
        URI uri = URI.create(channelUri + "/" + foundKey.toUrl());
        builder.location(uri);
        return builder.build();
    }

    @Trace
    @GET
    @Path("/{h}/{m}/{s}/{ms}/{hash}/events")
    @Produces(SseFeature.SERVER_SENT_EVENTS)
    @NewRelicIgnoreTransaction
    public EventOutput getEvents(@PathParam("channel") String channel,
                                 @PathParam("Y") int year,
                                 @PathParam("M") int month,
                                 @PathParam("D") int day,
                                 @PathParam("h") int hour,
                                 @PathParam("m") int minute,
                                 @PathParam("s") int second,
                                 @PathParam("ms") int millis,
                                 @PathParam("hash") String hash,
                                 @HeaderParam("Last-Event-ID") String lastEventId) {
        ContentKey contentKey = new ContentKey(year, month, day, hour, minute, second, millis, hash);
        ContentKey parsedKey = ContentKey.fromFullUrl(lastEventId);
        if (parsedKey != null) {
            contentKey = parsedKey;
        }
        try {
            logger.info("starting events at {} for client from {}", channel, contentKey);
            EventOutput eventOutput = new EventOutput();
            eventsService.register(new ContentOutput(channel, eventOutput, contentKey, uriInfo.getBaseUri()));
            return eventOutput;
        } catch (Exception e) {
            logger.warn("unable to get events for " + channel, e);
            throw e;
        }
    }

    @Trace
    @Path("/{h}/{m}/{s}/{ms}/{hash}/{direction:[n|p].*}/{count}")
    @GET
    @Produces({MediaType.APPLICATION_JSON, "multipart/*", "application/zip"})
    public Response getDirectionCount(@PathParam("channel") String channel,
                                      @PathParam("Y") int year,
                                      @PathParam("M") int month,
                                      @PathParam("D") int day,
                                      @PathParam("h") int hour,
                                      @PathParam("m") int minute,
                                      @PathParam("s") int second,
                                      @PathParam("ms") int millis,
                                      @PathParam("hash") String hash,
                                      @PathParam("direction") String direction,
                                      @PathParam("count") int count,
                                      @QueryParam("stable") @DefaultValue("true") boolean stable,
                                      @QueryParam("trace") @DefaultValue("false") boolean trace,
                                      @QueryParam("location") @DefaultValue(Location.DEFAULT) String location,
                                      @QueryParam("epoch") @DefaultValue(Epoch.DEFAULT) String epoch,
                                      @QueryParam("batch") @DefaultValue("false") boolean batch,
                                      @QueryParam("bulk") @DefaultValue("false") boolean bulk,
                                      @QueryParam("order") @DefaultValue(Order.DEFAULT) String order,
                                      @QueryParam("inclusive") @DefaultValue("false") boolean inclusive,
                                      @QueryParam("tag") String tag,
                                      @HeaderParam("Accept") String accept) {
        ContentKey key = new ContentKey(year, month, day, hour, minute, second, millis, hash);
        boolean next = direction.startsWith("n");
        boolean descending = Order.isDescending(order);
        if (null != tag) {
            return tagContentResource.adjacentCount(tag, count, stable, trace, location, next, key, bulk || batch, accept, uriInfo, epoch, descending);
        }
        DirectionQuery query = DirectionQuery.builder()
                .channelName(channel)
                .startKey(key)
                .inclusive(inclusive)
                .next(next)
                .stable(stable)
                .location(Location.valueOf(location))
                .epoch(Epoch.valueOf(epoch))
                .count(count)
                .build();
        SortedSet<ContentKey> keys = channelService.query(query);
        if (bulk || batch) {
            return BulkBuilder.build(keys, channel, channelService, uriInfo, accept, descending, (builder) -> {
                if (!keys.isEmpty()) {
                    builder.header("Link", "<" + LinkBuilder.getDirection("previous", channel, uriInfo, keys.first(), count) +
                            ">;rel=\"" + "previous" + "\"");
                    builder.header("Link", "<" + LinkBuilder.getDirection("next", channel, uriInfo, keys.last(), count) +
                            ">;rel=\"" + "next" + "\"");
                }
            });
        } else {
            return LinkBuilder.directionalResponse(keys, count, query, mapper, uriInfo, true, trace, descending);
        }
    }

    @Trace
    @Path("/{h}/{m}/{s}/{ms}")
    @POST
    @Produces(MediaType.APPLICATION_JSON)
    public Response historicalInsert(@PathParam("channel") final String channelName,
                                     @PathParam("Y") int year,
                                     @PathParam("M") int month,
                                     @PathParam("D") int day,
                                     @PathParam("h") int hour,
                                     @PathParam("m") int minute,
                                     @PathParam("s") int second,
                                     @PathParam("ms") int millis,
                                     @HeaderParam("Content-Length") long contentLength,
                                     @HeaderParam("Content-Type") String contentType,
                                     @HeaderParam("Content-Language") String contentLanguage,
                                     final InputStream data) throws Exception {
        ContentKey key = new ContentKey(year, month, day, hour, minute, second, millis);
        Content content = Content.builder()
                .withContentKey(key)
                .withContentType(contentType)
                .withContentLength(contentLength)
                .withStream(data)
                .build();
        content.setHistorical(true);

        return historicalResponse(channelName, content);
    }

    @Trace
    @Path("/{h}/{m}/{s}/{ms}/{hash}")
    @POST
    @Produces(MediaType.APPLICATION_JSON)
    public Response historicalInsertHash(@PathParam("channel") final String channelName,
                                         @PathParam("Y") int year,
                                         @PathParam("M") int month,
                                         @PathParam("D") int day,
                                         @PathParam("h") int hour,
                                         @PathParam("m") int minute,
                                         @PathParam("s") int second,
                                         @PathParam("ms") int millis,
                                         @PathParam("hash") String hash,
                                         @HeaderParam("Content-Length") long contentLength,
                                         @HeaderParam("Content-Type") String contentType,
                                         @HeaderParam("Content-Language") String contentLanguage,
                                         final InputStream data) throws Exception {
        ContentKey key = new ContentKey(year, month, day, hour, minute, second, millis, hash);

        Content content = Content.builder()
                .withContentKey(key)
                .withContentType(contentType)
                .withContentLength(contentLength)
                .withStream(data)
                .build();
        content.setHistorical(true);
        return historicalResponse(channelName, content);
    }

    private Response historicalResponse(String channelName, Content content) throws Exception {
        if (!channelService.channelExists(channelName)) {
            throw new WebApplicationException(Response.Status.NOT_FOUND);
        }
        ContentKey key = content.getContentKey().get();
        try {
            boolean success = channelService.historicalInsert(channelName, content);
            if (!success) {
                return Response.status(400).entity("unable to insert historical item").build();
            }
            logger.trace("posted {}", key);
            InsertedContentKey insertionResult = new InsertedContentKey(key);
            URI payloadUri = uriInfo.getBaseUriBuilder()
                    .path("channel").path(channelName)
                    .path(key.toUrl())
                    .build();

            Linked<InsertedContentKey> linkedResult = linked(insertionResult)
                    .withLink("channel", LinkBuilder.buildChannelUri(channelName, uriInfo))
                    .withLink("self", payloadUri)
                    .build();

            Response.ResponseBuilder builder = Response.status(Response.Status.CREATED);
            builder.entity(linkedResult);
            builder.location(payloadUri);
            return builder.build();
        } catch (InvalidRequestException e) {
            return Response.status(400).entity(e.getMessage()).build();
        } catch (ConflictException e) {
            return Response.status(409).entity(e.getMessage()).build();
        } catch (ContentTooLargeException e) {
            return Response.status(413).entity(e.getMessage()).build();
        } catch (Exception e) {
            logger.warn("unable to POST to " + channelName + " key " + key, e);
            throw e;
        }
    }

    @Trace
    @Path("/{h}/{m}/{s}/{ms}")
    @GET
    public Response getMillis(@PathParam("channel") String channel,
                              @PathParam("Y") String year,
                              @PathParam("M") String month,
                              @PathParam("D") String day,
                              @PathParam("h") String hour,
                              @PathParam("m") String minute,
                              @PathParam("s") String second,
                              @PathParam("ms") String millis) {
        UriBuilder builder = UriBuilder.fromUri(uriInfo.getBaseUri())
                .path("channel")
                .path(channel)
                .path(year).path(month).path(day)
                .path(hour).path(minute).path(second);
        TimeLinkUtil.addQueryParams(uriInfo, builder);
        return Response.seeOther(builder.build()).build();
    }

    @Trace
    @Path("/{h}/{m}/{s}/{ms}/{hash}")
    @DELETE
    public Response delete(@PathParam("channel") String channel,
                           @PathParam("Y") int year,
                           @PathParam("M") int month,
                           @PathParam("D") int day,
                           @PathParam("h") int hour,
                           @PathParam("m") int minute,
                           @PathParam("s") int second,
                           @PathParam("ms") int millis,
                           @PathParam("hash") String hash
    ) {

        ContentKey key = new ContentKey(year, month, day, hour, minute, second, millis, hash);
        channelService.delete(channel, key);
        return Response.noContent().build();
    }
}
