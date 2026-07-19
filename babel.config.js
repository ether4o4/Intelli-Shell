module.exports = {
  presets: ['module:@react-native/babel-preset'],
  // react-native-worklets/plugin powers react-native-reanimated v4 worklets
  // and must be listed last.
  plugins: ['react-native-worklets/plugin'],
};
