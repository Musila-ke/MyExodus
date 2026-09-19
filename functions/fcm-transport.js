'use strict';
// AD-04/AD-11: FCM server transport. Data-only generic wake signal — no `notification`
// block (server-rendered text would need event content, which never leaves the device),
// and the data values are the messageId + a fixed type string only.

const { getMessaging } = require('firebase-admin/messaging');
const { registerTransport } = require('./dispatch');

registerTransport('fcm', {
  async send(installation, data) {
    await getMessaging().send({ token: installation.token, data });
  },
  isTokenInvalid(err) {
    const code = (err && err.code) || '';
    return code === 'messaging/registration-token-not-registered' ||
      code === 'messaging/invalid-registration-token';
  },
});
