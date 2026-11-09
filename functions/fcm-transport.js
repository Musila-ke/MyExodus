'use strict';
// AD-04/AD-11 + plan §8.12: FCM server transport for partner event wakes. Data-only generic
// sync trigger — no `notification` block (server-rendered text would need event content,
// which never leaves the device), and the data values are the versioned generic type plus an
// opaque message id only. `messageId` is not in §8.12's forbidden list and is what lets the
// client handle the triggering message first; every other field is deliberately absent.
//
// Priority/TTL/collapse: HIGH priority (owner ruling 2026-11-09 — "notifications must be seen
// seconds after sent"). The wake itself renders nothing, but its only outcome is a
// user-visible partner alert, and a NORMAL-priority wake is deferred outright while the
// receiving phone is in Doze — the observed "15 minutes to hours late". FCM's high-priority
// guidance permits a message whose delivery results in a user-visible notification, which is
// exactly this path. TTL stays 30 minutes: a wake older than that adds nothing the mailbox's
// next pass will not fetch, and the constant collapse key still coalesces a burst into one
// full pass without losing an event.

const { getMessaging } = require('firebase-admin/messaging');
const { registerTransport } = require('./dispatch');

const ANDROID_PACKAGE = 'com.example.myexodus';
const WAKE_TTL_MS = 30 * 60 * 1000;
const WAKE_COLLAPSE_KEY = 'sync';

/** The complete FCM message for one relay wake. Exported for the payload-inspection suite. */
function buildWakeMessage(installation, data) {
  return {
    token: installation.token,
    data,
    android: {
      priority: 'high',
      ttl: WAKE_TTL_MS,
      collapseKey: WAKE_COLLAPSE_KEY,
      restrictedPackageName: ANDROID_PACKAGE,
    },
  };
}

registerTransport('fcm', {
  async send(installation, data) {
    await getMessaging().send(buildWakeMessage(installation, data));
  },
  isTokenInvalid(err) {
    const code = (err && err.code) || '';
    return code === 'messaging/registration-token-not-registered' ||
      code === 'messaging/invalid-registration-token';
  },
});

module.exports = { buildWakeMessage, WAKE_TTL_MS, WAKE_COLLAPSE_KEY, ANDROID_PACKAGE };
