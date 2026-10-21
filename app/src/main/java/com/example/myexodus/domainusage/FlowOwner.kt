package com.example.myexodus.domainusage

/**
 * Who owns the device-side socket behind a relayed flow (ADR #189). Website limits meter
 * browser traffic only: [NON_BROWSER] flows — the site's own app, background sync, any other
 * app — are forwarded untouched, never metered and never reset by a website rule's quota.
 * [UNKNOWN] is the fail-safe direction (API < 29, an unresolved 5-tuple, or a lookup error):
 * the flow is treated like [NON_BROWSER], so a lookup failure can under-report but can never
 * produce a false block.
 */
enum class FlowOwner { BROWSER, NON_BROWSER, UNKNOWN }
