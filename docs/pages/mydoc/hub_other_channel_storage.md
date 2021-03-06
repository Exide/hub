---
title: Channel Storage
keywords: 
last_updated: July 3, 2016
tags: []
summary: 
sidebar: mydoc_sidebar
permalink: hub_other_channel_storage.html
folder: hub
---


## Large Items

The Hub has a [configurable maximum for item size](hub_install_locally.md), the default is 40 MB.

One issue we have run into frequently at FlightStats is managing log files over time.  
Since storing data ordered by time is what the Hub does, it seems like a logical fit.
However, storing arbitrarily large files in Spoke could be problematic, quickly filling up the storage space with items which are intended more
for long term storage, and are less concerned with real time distribution.

## Current Storage Behavior

Currently, the Hub has two different types of deployment configurations.
In our standard deployment of 3 hub servers in a cluster, the [Hub uses a combination of Spoke and AWS S3 for storing items in channels](hub_other_technical.html).

It is also possible to run the Hub as a single instance which only uses files system storage, which is very similar to Spoke.
We use this [Docker image](https://hub.docker.com/r/flightstats/hub/) for repeatable tests for Hub clients.

## Proposed Changes

We want to make the Where and How of data storage configurable per channel.
Each Hub installation can configure the default values, and may disallow some options.

### Storage -> Strategy

The existing `storage` channel option will be renamed to `strategy` (`storage` will still be supported).   
Currently, this option only effects behavior with S3.     
Spoke ignores this option.

The available options are:

* SINGLE (default)
    * each item is compressed and stored individually in S3
    * items are written asynchronously into S3
    * cost effective for channels with volumes of less than 2 items per minute 
    * once outside of Spoke, can be faster for individual items reads
    
* BATCH
    * indexed items are batched into S3 every minute
    * cost effective for channels with volumes of more than 2 items per minute
    * once outside of Spoke, extremely efficient performance when combined with bulk reads
    * only works with backend `LOCAL-REMOTE`
    
* SINGLE-AND-BATCH
    * Uses both single and batch
    * Useful for transitioning between strategies
    * only works with backend `LOCAL-REMOTE`

### Backend

We will allow users to specify `backend` options:

* LOCAL

    * Only uses Spoke
    * extremely efficient when sized to fit entirely within the Operation System's File Cache
    * has an optional TTL which applies to all channels (default 60 minutes)
    * default option for ruuning tests using Docker 
    * max item default of 40 MB
    
* REMOTE

    * Only uses S3
    * Allows items up to 5 TB
* LOCAL-REMOTE

    * default for clustered installations
    * max item default of 40 MB
    * combines the efficiency of Spoke with the long term characteristics of S3

## Questions

* Better names for options?
* Concerns about these changes?


{% include links.html %}