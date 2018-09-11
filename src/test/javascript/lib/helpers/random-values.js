
const randomChannelName = () => `TeSt_${Math.random().toString().replace(".", "_")}`;

const alpha = 'abcdefghijklmnopqrstuvwxyz';

const randomNumberBetweenInclusive = (min = 0, max) => {
    const minCalc = Math.ceil(min);
    const maxCalc = Math.floor(max);
    return Math.floor(Math.random() * (maxCalc - minCalc + 1)) + minCalc;
};

const randomString = (len) => {
    let str = '';
    for (let i = 0; i <= len; i++) {
        const charIndex = randomNumberBetweenInclusive(i, 26);
        const char = alpha.charAt(charIndex);
        str = `${str}${char}`;
    }
    return str;
};

const randomTag = () => `tag${Math.random().toString().replace(".", "")}`;

const randomItemsFromArrayByPercentage = (arr, percentage) => {
    const LIMIT = 60 * 1000;
    const resultArray = [];
    const amountToTake = Math.floor((arr.length * percentage) / 100);
    console.log(`taking ${percentage}% of the ${arr.length} items: `, amountToTake);
    do {
        resultArray.push(arr[Math.floor(Math.random() * arr.length)]);
    } while ((resultArray.length < amountToTake) && (resultArray.length < LIMIT));
    return resultArray;
};

module.exports = {
    randomChannelName,
    randomItemsFromArrayByPercentage,
    randomNumberBetweenInclusive,
    randomString,
    randomTag,
};
