module.exports = {
  preset: 'react-native',
  resolver: 'react-native-worklets/jest/resolver.js',
  setupFiles: ['./jest.setup.js'],
  transformIgnorePatterns: [
    'node_modules/(?!(react-native|@react-native|react-native-.*|@shopify/react-native-skia)/)',
  ],
};
