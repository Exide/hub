const ip = require('ip');

const timeoutInterval = 60 * 1000;
/* eslint-disable no-undef */
// linter understandably does not know that jasmine is defined
jasmine.DEFAULT_TIMEOUT_INTERVAL = timeoutInterval;
/* eslint-enable no-undef */

// const getRunEncrypted = () => process.env.runEncrypted || false;

const getCallBackPort = () => (process.env.callbackPort || 8888);

const getIp = () => (process.env.ipAddress || ip.address());

const getHubDomain = () => {
    const {
        hubDomain,
        isKube,
    } = process.env;
    if (isKube) return hubDomain;
    return hubDomain || getIp();
};

const getHubUrlBase = () => {
    const domain = getHubDomain();
    return domain.startsWith('http') ? domain : `http://${domain}`;
};

const getChannelUrl = () => `${getHubUrlBase()}/channel`;

const getCallBackDomain = () => `http://${getIp()}`;

console.log(`hubDomain:: ${getHubDomain()}`);
// console.log(`runEncrypted:: ${getRunEncrypted()}`);
console.log(`getCallBackDomain():: ${getCallBackDomain()}`);
console.log(`default timeout:: ${timeoutInterval}ms`);
console.log(`callBackPort:: ${getCallBackPort()}`);
console.log(`channelUrl:: ${getChannelUrl()}`);
console.log(`hubUrlBase:: ${getHubUrlBase()}`);
console.log(`ip address:: ${getIp()}`);

module.exports = {
    getCallBackDomain,
    getCallBackPort,
    getChannelUrl,
    getHubDomain,
    getHubUrlBase,
    // getRunEncrypted,
};
