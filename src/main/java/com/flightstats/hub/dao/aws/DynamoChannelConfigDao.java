package com.flightstats.hub.dao.aws;

import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClient;
import com.amazonaws.services.dynamodbv2.model.*;
import com.flightstats.hub.app.HubServices;
import com.flightstats.hub.dao.Dao;
import com.flightstats.hub.model.ChannelConfig;
import com.flightstats.hub.model.GlobalConfig;
import com.flightstats.hub.util.Sleeper;
import com.google.common.util.concurrent.AbstractIdleService;
import com.google.inject.Inject;
import org.apache.commons.lang3.StringUtils;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class DynamoChannelConfigDao implements Dao<ChannelConfig> {
    private final static Logger logger = LoggerFactory.getLogger(DynamoChannelConfigDao.class);
    public static final String INDEX_NAME = "tempLowerCaseName";

    @Inject
    private AmazonDynamoDBClient dbClient;
    @Inject
    private DynamoUtils dynamoUtils;

    @Inject
    public DynamoChannelConfigDao() {
        HubServices.register(new DynamoChannelConfigurationDaoInit());
    }

    @Override
    public void upsert(ChannelConfig config) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("key", new AttributeValue(config.getName().toLowerCase()));
        item.put("displayName", new AttributeValue(config.getDisplayName()));
        item.put("lowerCaseName", new AttributeValue(config.getLowerCaseName()));
        item.put("date", new AttributeValue().withN(String.valueOf(config.getCreationDate().getTime())));
        item.put("keepForever", new AttributeValue().withBOOL(config.getKeepForever()));
        item.put("ttlDays", new AttributeValue().withN(String.valueOf(config.getTtlDays())));
        item.put("maxItems", new AttributeValue().withN(String.valueOf(config.getMaxItems())));
        if (config.getMutableTime() != null) {
            item.put("mutableTime", new AttributeValue().withN(String.valueOf(config.getMutableTime().getMillis())));
        }
        item.put("protect", new AttributeValue().withBOOL(config.isProtect()));
        item.put("allowZeroBytes", new AttributeValue().withBOOL(config.isAllowZeroBytes()));
        if (!config.getTags().isEmpty()) {
            item.put("tags", new AttributeValue().withSS(config.getTags()));
        }
        if (StringUtils.isNotEmpty(config.getDescription())) {
            item.put("description", new AttributeValue(config.getDescription()));
        }
        if (StringUtils.isNotEmpty(config.getReplicationSource())) {
            item.put("replicationSource", new AttributeValue(config.getReplicationSource()));
        }
        if (StringUtils.isNotEmpty(config.getOwner())) {
            item.put("owner", new AttributeValue(config.getOwner()));
        }
        if (StringUtils.isNotEmpty(config.getStorage())) {
            item.put("storage", new AttributeValue(config.getStorage()));
        }
        if (config.isGlobal()) {
            GlobalConfig global = config.getGlobal();
            item.put("master", new AttributeValue(global.getMaster()));
            item.put("satellites", new AttributeValue().withSS(global.getSatellites()));
            item.put("isMaster", new AttributeValue().withBOOL(global.isMaster()));
        }
        PutItemRequest putItemRequest = new PutItemRequest()
                .withTableName(getTableName())
                .withItem(item);
        dbClient.putItem(putItemRequest);
    }

    void initialize() throws InterruptedException {
        //todo - gfm - inline code for now to see what works
        String tableName = getTableName();
        ProvisionedThroughput throughput = dynamoUtils.getProvisionedThroughput("channel");
        logger.info("creating table {} ", tableName);
        List<AttributeDefinition> attributes = new ArrayList<>();
        attributes.add(new AttributeDefinition("key", ScalarAttributeType.S));
        attributes.add(new AttributeDefinition("lowerCaseName", ScalarAttributeType.S));

        GlobalSecondaryIndex globalSecondaryIndex = new GlobalSecondaryIndex();
        globalSecondaryIndex
                .withIndexName(INDEX_NAME)
                .withProvisionedThroughput(throughput)
                .withKeySchema(new KeySchemaElement().withAttributeName("lowerCaseName").withKeyType(KeyType.HASH))
                .withProjection(new Projection().withProjectionType(ProjectionType.ALL));

        CreateTableRequest request = new CreateTableRequest()
                .withTableName(tableName)
                .withAttributeDefinitions(attributes)
                .withKeySchema(new KeySchemaElement("key", KeyType.HASH))
                .withGlobalSecondaryIndexes(globalSecondaryIndex)
                .withProvisionedThroughput(throughput);

        dynamoUtils.createTable(request);
        dynamoUtils.updateTable(tableName, throughput);

        //todo - gfm - this can be deleted once it is run everywhere
        temporaryUpdateToLowerCaseName();
    }

    private void temporaryUpdateToLowerCaseName() {
        ScanRequest scanRequest = new ScanRequest()
                .withConsistentRead(true)
                .withTableName(getTableName());

        ScanResult result = dbClient.scan(scanRequest);
        processItems(result);

        while (result.getLastEvaluatedKey() != null) {
            scanRequest.setExclusiveStartKey(result.getLastEvaluatedKey());
            result = dbClient.scan(scanRequest);
            processItems(result);
        }
    }

    private void processItems(ScanResult result) {
        for (Map<String, AttributeValue> item : result.getItems()) {
            String displayName = item.get("displayName").getS();
            String name = item.get("key").getS();
            if (!name.equals(displayName.toLowerCase())) {
                ChannelConfig channelConfig = mapItem(item);
                logger.info("updating {}", channelConfig);
                upsert(channelConfig);
                delete(name);
                Sleeper.sleep(10);
            }
        }
    }

    @Override
    public ChannelConfig get(String name) {
        HashMap<String, AttributeValue> keyMap = new HashMap<>();
        keyMap.put("key", new AttributeValue().withS(name));
        GetItemRequest getItemRequest = new GetItemRequest()
                .withConsistentRead(true)
                .withTableName(getTableName())
                .withKey(keyMap);
        try {
            GetItemResult result = dbClient.getItem(getItemRequest);
            if (result.getItem() == null) {
                return null;
            }
            return mapItem(result.getItem());
        } catch (ResourceNotFoundException e) {
            logger.info("channel not found " + e.getMessage());
            return null;
        }
    }

    private ChannelConfig mapItem(Map<String, AttributeValue> item) {
        String displayName = item.get("displayName").getS();
        ChannelConfig.ChannelConfigBuilder builder = ChannelConfig.builder()
                .creationDate(new Date(Long.parseLong(item.get("date").getN())))
                .name(displayName.toLowerCase())
                .displayName(displayName);
        if (item.get("ttlDays") != null) {
            builder.ttlDays(Long.parseLong(item.get("ttlDays").getN()));
        }
        if (item.get("keepForever") != null) {
            builder.keepForever(item.get("keepForever").getBOOL());
        }
        if (item.containsKey("description")) {
            builder.description(item.get("description").getS());
        }
        if (item.containsKey("tags")) {
            builder.tags(item.get("tags").getSS());
        }
        if (item.containsKey("replicationSource")) {
            builder.replicationSource(item.get("replicationSource").getS());
        }
        if (item.get("maxItems") != null) {
            builder.maxItems(Long.parseLong(item.get("maxItems").getN()));
        }
        if (item.containsKey("owner")) {
            builder.owner(item.get("owner").getS());
        }
        if (item.containsKey("storage")) {
            builder.storage(item.get("storage").getS());
        }
        if (item.containsKey("master")) {
            GlobalConfig global = new GlobalConfig();
            global.setMaster(item.get("master").getS());
            global.addSatellites(item.get("satellites").getSS());
            global.setIsMaster(item.get("isMaster").getBOOL());
            builder.global(global);
        }
        if (item.containsKey("protect")) {
            builder.protect(item.get("protect").getBOOL());
        }
        if (item.containsKey("allowZeroBytes")) {
            builder.allowZeroBytes(item.get("allowZeroBytes").getBOOL());
        }
        if (item.containsKey("mutableTime")) {
            builder.mutableTime(new DateTime(Long.parseLong(item.get("mutableTime").getN()), DateTimeZone.UTC));
        }
        return builder.build();
    }

    @Override
    public Collection<ChannelConfig> getAll(boolean useCache) {
        List<ChannelConfig> configurations = new ArrayList<>();
        ScanRequest scanRequest = new ScanRequest()
                .withConsistentRead(true)
                .withTableName(getTableName());

        ScanResult result = dbClient.scan(scanRequest);
        mapItems(configurations, result);

        while (result.getLastEvaluatedKey() != null) {
            scanRequest.setExclusiveStartKey(result.getLastEvaluatedKey());
            result = dbClient.scan(scanRequest);
            mapItems(configurations, result);
        }

        return configurations;
    }

    private void mapItems(List<ChannelConfig> configurations, ScanResult result) {
        for (Map<String, AttributeValue> item : result.getItems()) {
            configurations.add(mapItem(item));
        }
    }

    @Override
    public void delete(String name) {
        Map<String, AttributeValue> key = new HashMap<>();
        key.put("key", new AttributeValue().withS(name));
        dbClient.deleteItem(new DeleteItemRequest(getTableName(), key));
    }

    private String getTableName() {
        return dynamoUtils.getTableName("channelMetaData");
    }

    private class DynamoChannelConfigurationDaoInit extends AbstractIdleService {
        @Override
        protected void startUp() throws Exception {
            initialize();
        }

        @Override
        protected void shutDown() throws Exception {
        }

    }
}
