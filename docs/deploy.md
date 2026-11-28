# Deploy

Production layout: **Railway** runs the API and Redis, **Cloudflare Pages** serves the frontend, **R2** stores files.
Replace `example.com` with your domain: frontend `example.com`, API `api.example.com`.

## 1. Cloudflare R2 (dashboard → R2)

1. Create bucket `dropcode-prod`.
2. **API token**: R2 → Manage API tokens → *Object Read & Write*, scoped to `dropcode-prod` only. Note the Access Key ID, Secret and Account ID.
3. **CORS** (bucket → Settings → CORS policy):
   ```json
   [
     {
       "AllowedOrigins": ["https://example.com"],
       "AllowedMethods": ["PUT", "GET"],
       "AllowedHeaders": ["*"],
       "MaxAgeSeconds": 3600
     }
   ]
   ```
4. **Lifecycle rule** (bucket → Settings → Object lifecycle rules): delete objects with prefix `objects/` after **8 days**. This is the safety net behind the cleanup job.

## 2. Cloudflare Turnstile (dashboard → Turnstile)

Add a widget for `example.com` (mode *Managed*). You get a **site key** (frontend) and a **secret key** (backend).

## 3. Railway (railway.com)

1. New project → **Add Redis**.
2. **New service → GitHub repo** `ema2d/dropcode`:
   - Settings → *Root directory*: `backend` (Railway finds `backend/Dockerfile`).
   - Settings → *Wait for CI*: on. Healthcheck path: `/actuator/health`.
   - Serverless: **off** (the cleanup job must keep running).
   - Usage limit: about $15.
3. Service **Variables**:

   | Name | Value |
   |---|---|
   | `R2_ACCOUNT_ID`, `R2_ACCESS_KEY_ID`, `R2_SECRET_ACCESS_KEY` | from step 1 |
   | `R2_BUCKET` | `dropcode-prod` |
   | `REDIS_HOST` | `${{Redis.RAILWAY_PRIVATE_DOMAIN}}` |
   | `REDIS_PORT` | `${{Redis.REDISPORT}}` |
   | `SPRING_DATA_REDIS_PASSWORD` | `${{Redis.REDISPASSWORD}}` |
   | `CORS_ALLOWED_ORIGINS` | `https://example.com` |
   | `TURNSTILE_SECRET_KEY` | secret from step 2 |
   | `TRUST_CLOUDFLARE_HEADER` | `true` only if `api.example.com` is proxied by Cloudflare (orange cloud) |

   `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75` is already set in the Dockerfile.
4. Settings → Networking → **Custom domain** `api.example.com`, then add the CNAME Railway shows in Cloudflare DNS.

> With `TRUST_CLOUDFLARE_HEADER=true`, make sure requests can only reach the API through Cloudflare. Otherwise a client could send a fake `CF-Connecting-IP` to dodge rate limits.

## 4. Cloudflare Pages (dashboard → Workers & Pages → Create → Pages → Connect to Git)

- Repository `ema2d/dropcode`, production branch `main`
- Root directory: `frontend`, build command `npm run build`, output `dist`
- Environment variables: `NODE_VERSION=24`, `VITE_API_URL=https://api.example.com`, `VITE_TURNSTILE_SITE_KEY=<site key>`
- Custom domain: `example.com`

Deep links such as `example.com/K7M2Q` work because Pages serves `index.html` for unknown paths when there is no `404.html`. Security headers come from `frontend/public/_headers`.

## 5. Check

```bash
curl https://api.example.com/actuator/health
```
should return `{"status":"UP"}`. Then upload a file on one device and download it with the code on another.
