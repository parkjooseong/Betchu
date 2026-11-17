const { releaseConfig } = require('./scripts/release-config.cjs');

module.exports = ({ config }) => releaseConfig(config, process.env);
