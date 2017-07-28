require('./integration_config.js');

var request = require('request');
var channelName = utils.randomChannelName();
var channelResource = channelUrl + "/" + channelName;
var testName = __filename;


/**
 * create a channel
 * post two items
 * stream both items back with bulk
 */
describe(testName, function () {

    utils.putChannel(channelName, false, {"name": channelName, "ttlDays": 1});

    utils.addItem(channelResource, 201);

    var posted;

    it('posts item', function (done) {
        utils.postItemQ(channelResource)
            .then(function (value) {
                posted = value.response.headers.location;
                done();
            });
    });


    it("gets multipart items ", function (done) {
        request.get({
                url: channelResource + '/latest/10?stable=false&batch=true',
                followRedirect: false,
                headers: {Accept: "multipart/mixed"}
            },
            function (err, response, body) {
                expect(err).toBeNull();
                expect(response.statusCode).toBe(200);
                //todo - gfm - 8/19/15 - parse multipart
                console.log("headers", response.headers);
                console.log("body", response.body);
                done();
            });
    });
});
