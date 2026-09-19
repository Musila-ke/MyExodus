'use strict';
// AD-04: provider-selected push dispatch. Transports register themselves by provider name
// ('fcm' from fcm-transport.js; 'hms' registers with the HMS transport workstream). A
// provider with no registered transport is a configuration gap, not a transient failure —
// the relay records it FAILED_TERMINAL instead of burning retry attempts on it.

const transports = new Map();

class UnknownProviderError extends Error {
  constructor(provider) {
    super(`no push transport registered for provider "${provider}"`);
    this.name = 'UnknownProviderError';
    this.provider = provider;
  }
}

function registerTransport(provider, transport) {
  transports.set(provider, transport);
}

async function dispatchViaProvider(installation, wakeData) {
  const transport = transports.get(installation.provider);
  if (!transport) throw new UnknownProviderError(String(installation.provider));
  await transport.send(installation, wakeData);
}

/** Provider-specific stale-token classification (drives markInstallationStale, AD-18c). */
function isTokenInvalid(provider, err) {
  const transport = transports.get(provider);
  return !!(transport && typeof transport.isTokenInvalid === 'function' && transport.isTokenInvalid(err));
}

module.exports = { registerTransport, dispatchViaProvider, isTokenInvalid, UnknownProviderError };
