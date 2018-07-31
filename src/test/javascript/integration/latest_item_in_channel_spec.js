require('../integration_config');
const request = require('request');
const {
    fromObjectPath,
    getProp,
    hubClientPut,
} = require('../lib/helpers');

const channelName = utils.randomChannelName();
const channelResource = `${channelUrl}/${channelName}`;
const headers = { 'Content-Type': 'application/json' };
let posted = null;
/**
 * create a channel
 * post an item
 * does not get the item back out with latest - stable
 * get the item back out with latest - unstable
 */
describe(__filename, function () {
    beforeAll(async () => {
        const response = await hubClientPut(channelResource, headers, { name: channelName, ttlDays: 1 });
        expect(getProp('statusCode', response)).toEqual(201);
    });

    utils.addItem(channelResource, 201);

    it('posts item', function (done) {
        utils.postItemQ(channelResource)
            .then(function (value) {
                const location = fromObjectPath(['response', 'headers', 'location'], value);
                posted = location;
                done();
            });
    });

    it("gets latest stable in channel ", function (done) {
        request.get({url: `${channelResource}/latest`, followRedirect: false},
            function (err, response, body) {
                expect(err).toBeNull();
                expect(getProp('statusCode', response)).toBe(404);
                done();
            });
    });

    it("gets latest unstable in channel ", function (done) {
        request.get({url: `${channelResource}/latest?stable=false`, followRedirect: false},
            function (err, response, body) {
                expect(err).toBeNull();
                expect(getProp('statusCode', response)).toBe(303);
                const location = fromObjectPath(['headers', 'location'], response);
                expect(location).toBe(posted);
                done();
            });
    });

    it("gets latest N unstable in channel ", function (done) {
        request.get({url: `${channelResource}/latest/10?stable=false`, followRedirect: false},
            function (err, response, body) {
                expect(err).toBeNull();
                expect(getProp('statusCode', response)).toBe(200);
                const parsed = utils.parseJson(response, __filename);
                const uris = fromObjectPath(['_links', 'uris'], parsed) || [];
                expect(uris.length).toBe(2);
                expect(uris[1]).toBe(posted);
                done();
            });
    });

    utils.itSleeps(6000);
    utils.addItem(channelResource, 201);

    it("gets latest stable in channel ", function (done) {
        request.get({url: `${channelResource}/latest?stable=true&trace=true`, followRedirect: false},
            function (err, response, body) {
                expect(err).toBeNull();
                expect(getProp('statusCode', response)).toBe(303);
                const location = fromObjectPath(['headers', 'location'], response);
                expect(location).toBe(posted);
                done();
            });
    });
});
