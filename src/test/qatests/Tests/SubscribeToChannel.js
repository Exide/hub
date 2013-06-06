"use strict";

var chai = require('chai');
var expect = chai.expect;
var superagent = require('superagent');
var request = require('request');
var moment = require('moment');
var async = require('async');
var fs = require('fs');
var WebSocket = require('ws');


var dhh = require('../DH_test_helpers/DHtesthelpers.js'),
    ranU = require('../randomUtils.js'),
    gu = require('../genericUtils.js');

var WAIT_FOR_CHANNEL_RESPONSE_MS = 10 * 1000,
    WAIT_FOR_SOCKET_CLOSURE_MS = 10 * 1000,
    URL_ROOT = dhh.URL_ROOT,
    DOMAIN = dhh.DOMAIN,
    FAKE_SOCKET_URI = 'ws://datahub-01.cloud-east.dev:8080/channel/sQODTvsYlLOLWTFPWNBBQ/ws',
    DEBUG = false;

// Test variables that are regularly overwritten
var agent, payload, req, uri;

var channelName,
    channelUri,
    wsUri;


describe('Channel Subscription:', function() {

    before(function(){
        gu.debugLog('\nURL_ROOT: '+ URL_ROOT);
        gu.debugLog('DOMAIN (for websockets): '+ DOMAIN);
        gu.debugLog('Debugging ENABLED', DEBUG);
    });

    beforeEach(function(myCallback){
        agent = superagent.agent();
        payload = uri = req = null;

        channelName = dhh.getRandomChannelName();

        dhh.createChannel(channelName, function(res){
            if ((res.error) || (!gu.isHTTPSuccess(res.status))) {
                myCallback(res.error);
            }
            var cnMetadata = new dhh.channelMetadata(res.body);
            channelUri = cnMetadata.getChannelUri();
            wsUri = cnMetadata.getWebSocketUri();

            myCallback();
        });
    });

    it('Acceptance: subscription works and updates are sent in order', function(done) {
        var socket,
            uriA,
            uriB;

        var afterOpen = function() {
            async.parallel([
                function(callback){
                    dhh.postData({channelUri: channelUri, data: dhh.getRandomPayload()}, function(res, uri) {
                        expect(gu.isHTTPSuccess(res.status)).to.equal(true);
                        gu.debugLog('Posted first value ', DEBUG);
                        uriA = uri;
                        callback(null, null);
                    });
                },
                function(callback){
                    dhh.postData({channelUri: channelUri, data: dhh.getRandomPayload()}, function(res, uri) {
                        expect(gu.isHTTPSuccess(res.status)).to.equal(true);
                        gu.debugLog('Posted second value ', DEBUG);
                        uriB = uri;
                        callback(null, null);
                    });
                }
            ])};

        var afterMessage = function() {
            if (socket.responseQueue.length == 2)  {
                confirmSocketData();
            }
        };

        var confirmSocketData = function() {
            dhh.getLatestUri(channelUri, function(latestUri) {
                var firstUri = (latestUri == uriA) ? uriB : uriA;

                expect(socket.responseQueue.length).to.equal(2);
                expect(socket.responseQueue[0]).to.equal(firstUri);
                expect(socket.responseQueue[1]).to.equal(latestUri);

                gu.debugLog('final socket state is '+ socket.ws.readyState, DEBUG);

                socket.ws.close();
                done();
            });
        };

        socket = new dhh.WSWrapper({
            'domain': DOMAIN,
            'uri': wsUri,
            'socketName': 'ws_01',
            'onOpenCB': afterOpen,
            'onMessageCB': afterMessage
        });
        socket.createSocket();

    });

    it('Returns 404 (as error) attempting to connect to a fake channel ws URI', function(done) {
        var socket;

        // Error is triggered and the message contains '404'
        var onError = function(msg) {
            expect(msg.toString().indexOf('404')).to.be.at.least(0);

            done();
        }

        socket = new dhh.WSWrapper({
            'domain': DOMAIN,
            'uri': FAKE_SOCKET_URI,
            'socketName': 'takeMeInDryTheRain',
            'onOpenCB': null,
            'onErrorCB': onError
        });

        socket.createSocket();
    })

    // Note, this test also ensures that all updates are correctly saved in the DH *and* that their
    //  relative links are correct.
    it('Multiple nigh-simultaneous updates are sent with order preserved.', function(done) {
        var actualResponseQueue = [], expectedResponseQueue = [], endWait, i;
        var numUpdates = 10, doDebug = false;
        this.timeout((numUpdates * WAIT_FOR_CHANNEL_RESPONSE_MS) + 45000);

        var mainTest = function() {
            async.times(numUpdates, function(n, next) {
                dhh.postData({channelUri: channelUri, data: dhh.getRandomPayload()}, function(res, uri) {
                    gu.debugLog('Posted data #'+ n, DEBUG);
                    next(null, uri);
                });
            }, function(err, uris) {
                // pass
            });
        };

        var confirmOrderOfResponses = function() {
            gu.debugLog('...entering confirmOrderOfResponses()');

            dhh.getListOfLatestUrisFromChannel(numUpdates, channelUri, function(allUris) {
                expectedResponseQueue = allUris;
                gu.debugLog('Expected response queue length: '+ expectedResponseQueue.length, DEBUG);

                expect(actualResponseQueue.length).to.equal(numUpdates);
                expect(expectedResponseQueue.length).to.equal(numUpdates);

                gu.debugLog('Expected and Actual queues are full. Comparing queues...', DEBUG);

                for (i = 0; i < numUpdates; i += 1) {
                    expect(actualResponseQueue[i]).to.equal(expectedResponseQueue[i]);
                    gu.debugLog('Matched queue number '+ i, DEBUG);
                }

                ws.close();
                done();
            });
        }

        var onOpen = function() {
            gu.debugLog('Open event fired!', DEBUG);
            gu.debugLog('Readystate: '+ ws.readyState, DEBUG);
            mainTest();
        };

        var ws = dhh.createWebSocket(wsUri, onOpen);

         ws.on('message', function(data, flags) {
            actualResponseQueue.push(data);
            gu.debugLog('Received message: '+ data, DEBUG);
            gu.debugLog('Response queue length: '+ actualResponseQueue.length, DEBUG);

             if (actualResponseQueue.length == numUpdates) {
                 confirmOrderOfResponses();
             }
        });
    });

    it('Multiple agents on a channel can be supported.', function(done) {
        // Channel created
        // create twelve agents that subscribe to the channel
        // channel pumps out three bits of data
        // each channel receives data in correct order
        var sockets = [],
            numAgents = 12,
            numReadySockets = 0,
            uri1,   // remember, the numbers do NOT necessarily reflect the order of creation
            uri2;

        // Called from newSocketIsReady() if all sockets are ready
        var mainTest = function() {
            // Post TWO messages to channel
            async.parallel([
                function(callback){
                    dhh.postData({channelUri: channelUri, data: dhh.getRandomPayload()}, function(res, uri) {
                        expect(gu.isHTTPSuccess(res.status)).to.equal(true);
                        gu.debugLog('Posted first value ', DEBUG);
                        uri1 = uri;
                        callback(null, null);
                    });
                },
                function(callback){
                    dhh.postData({channelUri: channelUri, data: dhh.getRandomPayload()}, function(res, uri) {
                        expect(gu.isHTTPSuccess(res.status)).to.equal(true);
                        gu.debugLog('Posted second value ', DEBUG);
                        uri2 = uri;
                        callback(null, null);
                    });
                }
            ],
                function(e, r){

                    // pass  (rewrote the stuff below and moved it out into testAllSockets() )
                    /*
                    gu.debugLog('In final part of async ', DEBUG);
                    dhh.getLatestUriFromChannel(channelName, function(latestUri) {
                        var firstUri = (latestUri == uri1) ? uri2 : uri1;

                        //console.log('First uri: '+ firstUri);
                        //console.log('Second uri: '+ latestUri);

                        // Wait for some period of time for both values
                        var endWait = Date.now() + (2 * WAIT_FOR_CHANNEL_RESPONSE_MS);

                        while((numFullSockets() < numAgents) && (Date.now() < endWait)) {
                            setTimeout(function () {
                                // pass
                            }, 100)
                        };

                        expect(numFullSockets()).to.equal(numAgents);

                        for (var i = 0; i < sockets.length; i += 1) {
                            var thisSocket = sockets[i];

                            expect(thisSocket.responseQueue.length).to.equal(2);
                            expect(thisSocket.responseQueue[0]).to.equal(firstUri);
                            expect(thisSocket.responseQueue[1]).to.equal(latestUri);

                            gu.debugLog('Final socket state for socket '+ thisSocket.name +' is '+ socket.ws.readyState, DEBUG);

                            thisSocket.ws.close();
                        }

                        done();
                    });
                    */
                });
        };

        // Called from afterOnMessage() if all sockets have received messages
        var testAllSockets = function() {
            dhh.getLatestUri(channelUri, function(latestUri) {
                var firstUri = (latestUri == uri1) ? uri2 : uri1;

                expect(numFullSockets()).to.equal(numAgents);

                for (var i = 0; i < sockets.length; i += 1) {
                    var thisSocket = sockets[i];

                    expect(thisSocket.responseQueue.length).to.equal(2);
                    expect(thisSocket.responseQueue[0]).to.equal(firstUri);
                    expect(thisSocket.responseQueue[1]).to.equal(latestUri);

                    gu.debugLog('Final socket state for socket '+ thisSocket.name +' is '+ socket.ws.readyState, DEBUG);

                    thisSocket.ws.close();
                }

                done();
            });
        }

        // Called when each socket is ready.
        var newSocketIsReady = function() {
            numReadySockets += 1;
            if (numAgents === numReadySockets) {
                mainTest();
            }
        };

        // Returns the number of sockets that received the expected number of messages
        var numFullSockets = function() {
            var full = 0;

            for (var i = 0; i < sockets.length; i += 1) {
                if (2 === sockets[i].responseQueue.length) {
                    full += 1;
                }
            }

            return full;
        }

        // called when a socket's onMessage() is done
        var afterOnMessage = function() {
            if (numFullSockets() == numAgents) {
                testAllSockets();
            }
        }

        // Create yon sockets
        for (var i = 0; i < numAgents; i += 1) {
            var socket = new dhh.WSWrapper({
                'domain': DOMAIN,
                'uri': wsUri,
                'socketName': 'ws_'+ i,
                'onOpenCB': newSocketIsReady,
                'onMessageCB': afterOnMessage
            });

            socket.createSocket();
            sockets[i] = socket;
        }
    });

    it('Disconnecting and then adding a new agent on same channel works.', function(done) {
        var socket1, socket2, uri1, uri2;

        var socket1OnOpen = function() {
            // broadcast message; confirm socket 1 received
            gu.debugLog('...entering socket1 Open function', DEBUG);

            dhh.postData({channelUri: channelUri, data: dhh.getRandomPayload()}, function(res, uri) {
                expect(gu.isHTTPSuccess(res.status)).to.equal(true);
                gu.debugLog('Posted first value ', DEBUG);
                uri1 = uri;
            });

        };

        var socket2OnOpen = function() {
            gu.debugLog('...entering socket2 Open function', DEBUG);

            expect(socket2.responseQueue.length).to.equal(0);

            dhh.postData({channelUri: channelUri, data: ranU.randomString(50)}, function(res, uri) {
                expect(gu.isHTTPSuccess(res.status)).to.equal(true);
                gu.debugLog('Posted second value ', DEBUG);
                uri2 = uri;
            });
        };

        // Called at end of onMessage event for socket 1
        var socket1Msg = function() {
            gu.debugLog('...entering socket1 Message');

            if (undefined == uri1) {
                setTimeout(function() {
                    finishSocket1();
                }, 3000)
            }
            else {
                finishSocket1()
            }
        }

        // Called at the end of the onMessage event for socket 2
        var socket2Msg = function() {
            gu.debugLog('...entering socket2Msg()');

            if (undefined == uri2) {
                setTimeout(function() {
                    finishSocket2();
                }, 3000)
            }
            else {
                finishSocket2();
            }
        }

        var finishSocket1 = function() {
            gu.debugLog('... entering finishSocket1');

            expect(socket1.responseQueue.length).to.equal(1);
            expect(uri1).to.equal(socket1.responseQueue[0]);
            gu.debugLog('Confirmed first socket received msg', DEBUG);

            expect(socket2.responseQueue.length).to.equal(0);   // socket2 not connected yet
            gu.debugLog('Confirmed second socket queue is empty', DEBUG);

            gu.debugLog('Calling socket1.close()', DEBUG);
            socket1.ws.close();
            gu.debugLog('About to create second socket', DEBUG);
            socket2.createSocket();

            socket2.ws.on('close', function(code, message) {
                gu.debugLog('Socket2 closed', DEBUG);
                done();
            });

        }

        var finishSocket2 = function() {
            gu.debugLog('...entering finishSocket2()');

            expect(socket2.responseQueue.length).to.equal(1);
            expect(uri2).to.equal(socket2.responseQueue[0]);
            gu.debugLog('Confirmed second socket received msg', DEBUG);

            socket2.ws.close();
        }

        socket1 = new dhh.WSWrapper({
            'domain': DOMAIN,
            'uri': wsUri,
            'socketName': 'ws_1',
            'onOpenCB': socket1OnOpen,
            'onMessageCB': socket1Msg
        });

        socket2 = new dhh.WSWrapper({
            'domain': DOMAIN,
            'uri': wsUri,
            'socketName': 'ws_2',
            'onOpenCB': socket2OnOpen,
            'onMessageCB': socket2Msg
        });

        socket1.createSocket();

        socket1.ws.on('close', function(code, message) {
            gu.debugLog('Socket1 closed', DEBUG);
        });
    });

    it('if one of two agents disconnects, the other will still get messages', function(done) {
        var fickleSocket,
            patientSocket,  // sockettomeh
            numReadySockets = 0,
            postedUri,
            VERBOSE = true;

        // Sequence:
        // when both sockets are ready, have one disconnect
        // on close event for the disconnecting socket, send a message
        // ensure the remaining socket gets the message

        // Called when each socket is ready.
        var newSocketIsReady = function() {
            numReadySockets += 1;
            if (2 === numReadySockets) {
                fickleSocket.ws.close();
                gu.debugLog('Calling close() on fickle socket', VERBOSE);
            }
        };

        var afterOnMessage = function() {

            // Allow a few seconds for the post to return with the uri
            if ('undefined' == typeof postedUri) {
                setTimeout(function() {
                    expect(patientSocket.responseQueue.length).to.equal(1);
                    expect(patientSocket.responseQueue[0]).to.equal(postedUri);
                    gu.debugLog('Message received', VERBOSE);

                    done();
                }, 5000);
            }
        }

        // Create both sockets
        fickleSocket = new dhh.WSWrapper({
            'domain': DOMAIN,
            'uri': wsUri,
            'socketName': 'fickle',
            'onOpenCB': newSocketIsReady,
            'onMessageCB': null
        });

        patientSocket = new dhh.WSWrapper({
            'domain': DOMAIN,
            'uri': wsUri,
            'socketName': 'patient',
            'onOpenCB': newSocketIsReady,
            'onMessageCB': afterOnMessage
        });

        patientSocket.createSocket();
        fickleSocket.createSocket();

        fickleSocket.ws.on('close', function(code, message) {
            gu.debugLog('Fickle socket closed', VERBOSE);

            dhh.postData({channelUri: channelUri, data: dhh.getRandomPayload()}, function(res, uri) {
                expect(gu.isHTTPSuccess(res.status)).to.equal(true);
                gu.debugLog('Posted value ', VERBOSE);
                postedUri = uri;
            });
        })
    })

    it.skip('<NOT WRITTEN> Multiple agents on multiple channels is handled appropriately.', function(done) {
        done();
    });

    it.skip('<NOT WRITTEN> Server recognizes when agent disconnects (in what time frame?)', function(done) {
        done();
    });



});
