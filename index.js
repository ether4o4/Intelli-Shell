/**
 * @format
 */

// react-native-gesture-handler must be imported first, before anything else.
import 'react-native-gesture-handler';
import {AppRegistry} from 'react-native';
import App from './App';
import {name as appName} from './app.json';

AppRegistry.registerComponent(appName, () => App);
