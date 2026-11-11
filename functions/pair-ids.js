'use strict';
// AD-02 pair identity. First defined inside index.js for pairing registration; shared here
// because partner event ingress derives the same pairId when writing mailbox records — a
// second copy could silently diverge and route a mailbox to a nonexistent pairing.

/** AD-02: pairId = min(uidA, uidB) + "_" + max(uidA, uidB). Server-computed, never trusted. */
function pairIdOf(uidA, uidB) {
  const [min, max] = uidA < uidB ? [uidA, uidB] : [uidB, uidA];
  return `${min}_${max}`;
}

module.exports = { pairIdOf };
