# 1. Files go straight to R2 with presigned URLs

**Status:** accepted

## Context
The API runs on a small container (512 MB). Streaming 30 MB uploads through it would cost memory, bandwidth and money, and make it a bottleneck.

## Decision
The API never touches file bytes. It returns a presigned `PUT` URL (10 min, `Content-Length` signed so R2 rejects any other size) and later a presigned `GET` URL (60 s). The browser talks to R2 directly. `complete` checks the real size with `HeadObject`.

## Consequences
- The API only handles small JSON requests; it scales with requests, not bytes.
- R2 needs a CORS rule allowing `PUT`/`GET` from the frontend origin.
- Download URLs are bearer links for 60 s; files are encrypted, so a leaked URL leaks only ciphertext.
