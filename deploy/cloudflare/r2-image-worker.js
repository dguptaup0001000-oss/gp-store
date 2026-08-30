/**
 * Cloudflare Worker bound to the private GP-STORE R2 bucket.
 *
 * wrangler.toml:
 *   name = "gpstore-images"
 *   main = "r2-image-worker.js"
 *   [[r2_buckets]]
 *   binding = "IMAGES"
 *   bucket_name = "<private-bucket>"
 *
 * Custom domain (e.g. img.gpstore.co.in) → this Worker.
 * Set R2_IMAGE_WORKER_BASE_URL=https://img.gpstore.co.in on the VPS.
 * Do not enable public bucket access. Do not grant ListBucket.
 */
export default {
  async fetch(request, env) {
    if (request.method !== "GET" && request.method !== "HEAD") {
      return new Response("Method not allowed", { status: 405 });
    }
    const url = new URL(request.url);
    const key = url.pathname.replace(/^\/+/, "");
    if (
      !key.startsWith("gpstore/products/") &&
      !key.startsWith("gpstore/categories/")
    ) {
      return new Response("Not found", { status: 404 });
    }
    if (key.includes("..") || key.includes("staging")) {
      return new Response("Not found", { status: 404 });
    }
    const object = await env.IMAGES.get(key);
    if (object == null) {
      return new Response("Not found", { status: 404 });
    }
    const headers = new Headers();
    headers.set("Cache-Control", "public, max-age=31536000, immutable");
    headers.set("Content-Type", object.httpMetadata?.contentType || "image/jpeg");
    return new Response(object.body, { headers });
  },
};
