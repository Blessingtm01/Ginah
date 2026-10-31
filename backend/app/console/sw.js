// Network-only service worker: lets Chrome and Edge install the console as a desktop app.
// Incident data is always fetched live and never cached.
self.addEventListener("install", () => self.skipWaiting());
self.addEventListener("activate", (e) => e.waitUntil(self.clients.claim()));
self.addEventListener("fetch", () => {});
