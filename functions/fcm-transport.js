'use strict';
// AD-04/AD-11 + plan §8.12: FCM server transport for partner event wakes. Data-only generic
// sync trigger — no `notification` block (server-rendered text would need event content,
// which never leaves the device), and the data values are the versioned generic type plus an
// opaque message id only. `messageId` is not in §8.12's forbidden list and is what lets the
// client handle the triggering message first; every other field is deliberately absent.
//
// Priority/TTL/collapse (§7 Step 9): a relay wake is ordinary sync, so it uses NORMAL priority
// (never high priority for background-only work) and expires after 30 minutes — a wake older
// than that is useless because the mailbox is the source of truth and the next periodic pass
// fetches everything. The constant collapse key means an offline device wakes once for a
// burst: any single wake runs the full mailbox pass, so no event is lost by coalescing.

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
      priority: 'normal',
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
