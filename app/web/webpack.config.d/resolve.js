// Workaround: On Windows CI, webpack fails to resolve @js-joda/core
// (transitive dep of kotlinx-datetime) because Node module resolution
// doesn't traverse from the package's kotlin/ subdirectory to the
// hoisted node_modules with backslash paths.  Explicitly add the root
// node_modules so webpack can always find hoisted packages.
const path = require("path");
const rootNodeModules = path.resolve(__dirname, "..", "..", "node_modules");
config.resolve = config.resolve || {};
config.resolve.modules = (config.resolve.modules || []).concat([rootNodeModules]);
